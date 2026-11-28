import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../design_system/wds.dart';
import '../../data/api_client.dart';
import '../../data/feedback_model.dart';
import '../../data/run_events.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';
import '../../providers/bpmn_provider.dart';
import '../feedback/feedback_dialog.dart';
import '../surfaces/surface_view.dart';
import '../../data/surface_model.dart';

final runsProvider = FutureProvider.autoDispose<List<RunView>>((ref) => ref.watch(repositoryProvider).listRuns());

final selectedRunIdProvider = StateProvider<String?>((ref) => null);

/// Live events for one run over the WebSocket. Ends quietly when the socket is unavailable (older server, proxy, expired ticket).
final runEventsProvider = StreamProvider.autoDispose.family<RunEventView, String>((ref, id) {
  final repo = ref.watch(repositoryProvider);
  return RunEventsClient(config: ref.watch(apiConfigProvider), fetchTicket: repo.wsTicket).watch(id);
});

/// The run itself: refetched whenever an event arrives, and polled as a safety net (every 2 s without a live socket,
/// every 15 s with one) until it reaches a terminal state. Events are a hint to refetch, never the source of truth.
final runDetailProvider = StreamProvider.autoDispose.family<RunView, String>((ref, id) {
  final repo = ref.watch(repositoryProvider);
  final out = StreamController<RunView>();
  var live = false;
  var busy = false;
  var done = false;
  Timer? timer;

  Future<void> refresh() async {
    if (busy || done || out.isClosed) return;
    busy = true;
    try {
      final run = await repo.getRun(id);
      if (out.isClosed) return;
      out.add(run);
      if (run.status.isTerminal) {
        done = true;
        timer?.cancel();
      }
    } catch (e, st) {
      if (!out.isClosed) out.addError(e, st);
    } finally {
      busy = false;
    }
  }

  void schedule() {
    timer?.cancel();
    if (done) return;
    timer = Timer(Duration(seconds: live ? 15 : 2), () async {
      await refresh();
      schedule();
    });
  }

  ref.listen<AsyncValue<RunEventView>>(runEventsProvider(id), (prev, next) {
    if (next.hasValue) {
      live = true;
      refresh();
      schedule();
    }
  });
  ref.onDispose(() {
    done = true;
    timer?.cancel();
    out.close();
  });
  refresh().then((_) => schedule());
  return out.stream;
});

WdsExecutionStatus runExecutionStatus(RunStatus status) => switch (status) {
      RunStatus.running => WdsExecutionStatus.running,
      RunStatus.waiting => WdsExecutionStatus.queued,
      RunStatus.completed => WdsExecutionStatus.succeeded,
      RunStatus.failed => WdsExecutionStatus.failed,
      RunStatus.cancelled => WdsExecutionStatus.cancelled,
    };

WdsExecutionStatus runEventExecutionStatus(RunEventView event) {
  final type = event.type.toUpperCase();
  if (type.contains('FAIL') || type.contains('ERROR')) return WdsExecutionStatus.failed;
  if (type.contains('CANCEL')) return WdsExecutionStatus.cancelled;
  if (type.contains('SKIP')) return WdsExecutionStatus.skipped;
  if (type.endsWith('_COMPLETED') || type.endsWith('_SUCCEEDED') || type == 'RUN_COMPLETED') return WdsExecutionStatus.succeeded;
  if (type.contains('RETR')) return WdsExecutionStatus.retrying;
  if (type.contains('START')) return WdsExecutionStatus.running;
  if (type.contains('QUEUE') || type.contains('WAIT')) return WdsExecutionStatus.queued;
  return WdsExecutionStatus.pending;
}

Color runStatusColor(RunStatus s) => switch (s) {
      RunStatus.running => AppColors.info,
      RunStatus.waiting => AppColors.warning,
      RunStatus.completed => AppColors.success,
      RunStatus.failed => AppColors.danger,
      RunStatus.cancelled => AppColors.textMutedDark,
    };

/// Runs matching [status] (null = any) whose process id or run id contains every word of [query] (case-insensitive).
List<RunView> filterRuns(List<RunView> all, String query, RunStatus? status) {
  final words = query.toLowerCase().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).toList();
  return [
    for (final r in all)
      if ((status == null || r.status == status) && words.every('${r.processId} ${r.id}'.toLowerCase().contains)) r,
  ];
}

final _runQueryProvider = StateProvider.autoDispose<String>((ref) => '');
final _runStatusFilterProvider = StateProvider.autoDispose<RunStatus?>((ref) => null);

class RunsScreen extends ConsumerWidget {
  const RunsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final runs = ref.watch(runsProvider);
    final selected = ref.watch(selectedRunIdProvider);
    final query = ref.watch(_runQueryProvider);
    final statusFilter = ref.watch(_runStatusFilterProvider);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          WdsPageHeader(
            title: 'Runs',
            description: 'Process instances executed by the durable engine.',
            actions: [
              WdsIconButton(tooltip: 'Refresh runs', icon: Icons.refresh_rounded, onPressed: () => ref.invalidate(runsProvider)),
              WdsButton(label: 'Start run', icon: Icons.play_arrow_rounded, onPressed: () => _startDialog(context, ref)),
            ],
          ),
          const SizedBox(height: 12),
          WdsFilterToolbar(
            search: WdsSearchField(
              hint: 'Search process or run id',
              onChanged: (value) => ref.read(_runQueryProvider.notifier).state = value,
            ),
            filters: [
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final st in [null, ...RunStatus.values])
                    ChoiceChip(
                      label: Text(st == null ? 'All' : st.name),
                      selected: statusFilter == st,
                      onSelected: (_) => ref.read(_runStatusFilterProvider.notifier).state = st,
                    ),
                ],
              ),
            ],
          ),
          const SizedBox(height: 12),
          Expanded(
            child: runs.when(
              loading: () => const LoadingView(),
              error: (e, _) => ErrorView(message: errText(e), needsSettings: needsSettings(e), onRetry: () => ref.invalidate(runsProvider)),
              data: (all) => all.isEmpty
                  ? const WdsEmptyState(icon: Icons.play_circle_outline_rounded, title: 'No runs yet', description: 'Publish a process and start it.')
                  : Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          flex: 5,
                          child: ListView(
                            children: [
                              if (filterRuns(all, query, statusFilter).isEmpty)
                                const Padding(padding: EdgeInsets.all(16), child: Text('No run matches the filter.', style: TextStyle(fontSize: 12))),
                              for (final r in filterRuns(all, query, statusFilter))
                                WdsSurface(
                                  padding: EdgeInsets.zero,
                                  color: r.id == selected ? Theme.of(context).colorScheme.primaryContainer.withValues(alpha: 0.24) : null,
                                  onTap: () => ref.read(selectedRunIdProvider.notifier).state = r.id,
                                  child: ListTile(
                                    dense: true,
                                    title: Text('${r.processId} v${r.version}', style: const TextStyle(fontWeight: FontWeight.w600)),
                                    subtitle: Text('${r.id}${r.startedAt == null ? '' : ' · ${formatDateShort(r.startedAt!)}'}',
                                        style: const TextStyle(fontSize: 11)),
                                    trailing: WdsExecutionStatusIndicator(status: runExecutionStatus(r.status), compact: true),
                                  ),
                                ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          flex: 6,
                          child: selected == null
                              ? const WdsEmptyState(icon: Icons.touch_app_outlined, title: 'Pick a run', description: 'Select a run to inspect its execution details.')
                              : _RunDetail(id: selected),
                        ),
                      ],
                    ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _startDialog(BuildContext context, WidgetRef ref) async {
    final processes = await ref.read(definitionsProvider(DefinitionKind.process).future).catchError((Object _) => <DefinitionSummary>[]);
    if (!context.mounted) return;
    final published = processes.where((p) => p.isPublished).toList();
    String? processId = published.isEmpty ? null : published.first.id;
    final input = TextEditingController(text: '{}');
    String? error;
    var starting = false; // guards against a double click starting two runs
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          backgroundColor: AppColors.darkSurfaceRaised,
          title: const Text('Start a run'),
          content: SizedBox(
            width: 460,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (published.isEmpty)
                  const Text('No published process. Publish one in the designer first.')
                else
                  DropdownButtonFormField<String>(
                    value: processId,
                    isExpanded: true,
                    decoration: const InputDecoration(labelText: 'Process (latest published version)'),
                    items: [for (final p in published) DropdownMenuItem(value: p.id, child: Text('${p.name} (v${p.latestVersion})'))],
                    onChanged: (v) => setState(() => processId = v),
                  ),
                const SizedBox(height: 12),
                TextField(
                  controller: input,
                  minLines: 4,
                  maxLines: 10,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                  decoration: InputDecoration(labelText: 'Input (JSON object)', errorText: error),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
            WdsButton(
              label: 'Start',
              isLoading: starting,
              onPressed: processId == null || starting
                  ? null
                  : () async {
                      final parsed = tryParseJsonObject(input.text);
                      if (parsed == null) return setState(() => error = 'Not a JSON object');
                      setState(() {
                        starting = true;
                        error = null;
                      });
                      try {
                        final run = await ref.read(repositoryProvider).startRun(processId!, input: parsed);
                        ref.invalidate(runsProvider);
                        ref.read(selectedRunIdProvider.notifier).state = run.id;
                        if (ctx.mounted) Navigator.pop(ctx);
                      } catch (e) {
                        setState(() {
                          starting = false;
                          error = errText(e);
                        });
                      }
                    },
            ),
          ],
        ),
      ),
    );
  }
}

class _RunDetail extends ConsumerWidget {
  const _RunDetail({required this.id});
  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final run = ref.watch(runDetailProvider(id));
    return run.when(
      loading: () => const LoadingView(),
      error: (e, _) => ErrorView(message: errText(e), needsSettings: needsSettings(e)),
      data: (r) => SurfacePanel(
        padding: const EdgeInsets.all(16),
        child: ListView(
          children: [
            Row(children: [
              Expanded(child: SelectableText(r.id, style: const TextStyle(fontWeight: FontWeight.w700))),
              IconButton(
                tooltip: 'Copy run id',
                visualDensity: VisualDensity.compact,
                icon: const Icon(Icons.copy_rounded, size: 15),
                onPressed: () async {
                  await Clipboard.setData(ClipboardData(text: r.id));
                  if (context.mounted) showToast(context, 'Run id copied');
                },
              ),
              WdsExecutionStatusIndicator(status: runExecutionStatus(r.status)),
            ]),
            const SizedBox(height: 4),
            Text('${r.processId} v${r.version}', style: Theme.of(context).textTheme.bodySmall),
            if (r.error != null) ...[
              const SizedBox(height: 10),
              Text(r.error!, style: const TextStyle(color: AppColors.danger, fontSize: 12)),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  icon: const Icon(Icons.report_outlined, size: 16),
                  label: const Text('Report this error'),
                  // The run's id and process id only: never its variables or outputs.
                  onPressed: () => showFeedbackDialog(context,
                      kind: FeedbackKind.error, message: r.error, details: {'runId': r.id, 'processId': r.processId, 'processVersion': '${r.version}'}),
                ),
              ),
            ],
            const SizedBox(height: 14),
            Wrap(spacing: 8, children: [
              WdsButton(
                label: 'Show path on canvas',
                icon: Icons.account_tree_outlined,
                variant: WdsButtonVariant.secondary,
                onPressed: () {
                  ref.read(runHighlightProvider.notifier).state = r.executedNodes.toSet();
                  ref.read(activeBpmnDiagramIdProvider.notifier).state = r.processId;
                  ref.read(selectedNavKeyProvider.notifier).state = 'bpmn';
                },
              ),
              if (!r.status.isTerminal)
                WdsButton(
                  label: 'Cancel run',
                  icon: Icons.stop_circle_outlined,
                  variant: WdsButtonVariant.danger,
                  onPressed: () async {
                    try {
                      await ref.read(repositoryProvider).cancelRun(r.id);
                      ref.invalidate(runsProvider);
                      ref.invalidate(runDetailProvider(id));
                    } catch (e) {
                      if (context.mounted) showToast(context, errText(e), isError: true);
                    }
                  },
                ),
            ]),
            const SizedBox(height: 16),
            const WdsSectionHeader(title: 'Executed nodes'),
            if (r.executedNodes.isEmpty)
              const Text('—', style: TextStyle(fontSize: 12))
            else
              Wrap(
                spacing: 6,
                runSpacing: 4,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  for (var i = 0; i < r.executedNodes.length; i++) ...[
                    if (i > 0) const Icon(Icons.chevron_right_rounded, size: 14, color: AppColors.textMutedDark),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                      decoration: BoxDecoration(
                        color: i == r.executedNodes.length - 1 && r.status == RunStatus.failed
                            ? AppColors.danger.withValues(alpha: 0.18)
                            : AppColors.darkSurfaceRaised,
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(r.executedNodes[i], style: const TextStyle(fontSize: 11.5)),
                    ),
                  ],
                ],
              ),
            if (r.waiting.isNotEmpty) ...[
              const SizedBox(height: 14),
              const WdsSectionHeader(title: 'Waiting on'),
              for (final t in r.waiting)
                Text('${t.kind}: ${t.title ?? t.messageName ?? t.nodeId}', style: const TextStyle(fontSize: 12)),
            ],
            const SizedBox(height: 14),
            _EventLog(id: id),
            const SizedBox(height: 14),
            if (r.variables['surface'] is Map) ...[
              const WdsSectionHeader(title: 'Surface'),
              _RunSurface(runId: r.id, surface: Map<String, dynamic>.from(r.variables['surface'] as Map)),
              const SizedBox(height: 14),
            ],
            const WdsSectionHeader(title: 'Variables'),
            _VariablesView(variables: r.variables),
          ],
        ),
      ),
    );
  }
}

/// The first [max] characters of [text] for display, cut at a line end when there is one near the limit; the full text stays available to copy.
String previewText(String text, {int max = 6000}) {
  if (text.length <= max) return text;
  final cut = text.lastIndexOf('\n', max);
  return text.substring(0, cut > max ~/ 2 ? cut : max);
}

/// The run's variables as readable JSON: long ones are shortened until the person asks for all of it; the copy button always copies everything.
class _VariablesView extends StatefulWidget {
  const _VariablesView({required this.variables});
  final Map<String, dynamic> variables;

  @override
  State<_VariablesView> createState() => _VariablesViewState();
}

class _VariablesViewState extends State<_VariablesView> {
  bool _all = false;

  @override
  Widget build(BuildContext context) {
    final full = const JsonEncoder.withIndent('  ').convert(widget.variables);
    final shown = _all ? full : previewText(full);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Align(
          alignment: Alignment.centerRight,
          child: TextButton.icon(
            icon: const Icon(Icons.copy_rounded, size: 14),
            label: const Text('Copy JSON'),
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: full));
              if (context.mounted) showToast(context, 'Variables copied');
            },
          ),
        ),
        SelectableText(shown, style: const TextStyle(fontFamily: 'monospace', fontSize: 11.5)),
        if (shown.length < full.length)
          TextButton(onPressed: () => setState(() => _all = true), child: Text('Show all (${full.length} characters)')),
      ],
    );
  }
}

/// Live event log: shown only while the socket delivers events; the run panel works the same without it.
class _EventLog extends ConsumerStatefulWidget {
  const _EventLog({required this.id});
  final String id;

  @override
  ConsumerState<_EventLog> createState() => _EventLogState();
}

class _EventLogState extends ConsumerState<_EventLog> {
  WdsExecutionStep _eventStep(RunEventView event, int index) => WdsExecutionStep(
        id: '${event.runId}:${event.type}:${event.nodeId ?? 'event'}:$index',
        title: event.type,
        status: runEventExecutionStatus(event),
        description: event.nodeId,
        message: event.isOverflow ? event.data.toString() : null,
        startedAt: event.time,
      );

  static const _max = 200;
  final List<RunEventView> _events = [];

  @override
  Widget build(BuildContext context) {
    ref.listen<AsyncValue<RunEventView>>(runEventsProvider(widget.id), (prev, next) {
      final e = next.valueOrNull;
      if (e == null) return;
      setState(() {
        _events.add(e);
        if (_events.length > _max) _events.removeRange(0, _events.length - _max);
      });
    });
    final state = ref.watch(runEventsProvider(widget.id));
    final live = state.hasValue || state.isLoading;
    // StreamProvider may already hold its first value before this detail widget
    // subscribes. Include that value in the first frame rather than waiting for
    // a subsequent event callback to populate the local bounded history.
    final streamedEvent = state.valueOrNull;
    final visibleEvents = _events.isEmpty && streamedEvent != null
        ? [streamedEvent]
        : _events;
    final recentEvents = visibleEvents.reversed.take(50).toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          const Expanded(child: WdsSectionHeader(title: 'Live events')),
          const SizedBox(width: 8),
          WdsBadge(label: live ? 'live' : 'polling', tone: live ? WdsBadgeTone.success : WdsBadgeTone.neutral, dense: true),
        ]),
        if (recentEvents.isEmpty)
          Text(live ? 'Waiting for events…' : 'Live feed unavailable; the panel refreshes every 2 s.', style: const TextStyle(fontSize: 12))
        else
          WdsExecutionTimeline(
            steps: [
              for (var index = 0; index < recentEvents.length; index++)
                _eventStep(recentEvents[index], index),
            ],
            emptyTitle: 'Waiting for events',
            emptyDescription: live ? 'Run events will appear here.' : 'Live feed unavailable; the run detail refreshes by polling.',
          ),
      ],
    );
  }
}

/// A run's `surface` variable, checked by the server first and then drawn. A surface the server refuses is shown as its problems, never drawn.
class _RunSurface extends ConsumerStatefulWidget {
  const _RunSurface({required this.runId, required this.surface});
  final String runId;
  final Map<String, dynamic> surface;

  @override
  ConsumerState<_RunSurface> createState() => _RunSurfaceState();
}

class _RunSurfaceState extends ConsumerState<_RunSurface> {
  late Future<SurfaceCheck> _check;

  @override
  void initState() {
    super.initState();
    _check = ref.read(repositoryProvider).validateSurface(jsonEncode(widget.surface));
  }

  /// The task comes from the viewer's own task list (the server decides what they may see), must belong to this run, and is only completed
  /// after the person confirms a text taken from the task itself, not from the button.
  Future<void> _completeTask(SurfaceAction a) async {
    final repo = ref.read(repositoryProvider);
    try {
      final task = taskForAction(a, widget.runId, await repo.listTasks());
      if (task == null) {
        if (mounted) showToast(context, 'That task is not waiting any more.', isError: true);
        return;
      }
      if (!mounted) return;
      final what = a.outputs.isEmpty ? '' : '\nSends: ${a.outputs.entries.map((e) => '${e.key}=${e.value}').join(', ')}';
      final ok = await showConfirmDialog(context, title: 'Complete task', message: "Complete '${task.title ?? task.nodeId}'?$what", confirmLabel: 'Complete');
      if (!ok) return;
      await repo.completeTask(task.token, a.outputs);
      ref.invalidate(runsProvider);
      ref.invalidate(runDetailProvider(widget.runId));
      ref.invalidate(tasksProvider);
    } catch (e) {
      if (mounted) showToast(context, errText(e), isError: true);
    }
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<SurfaceCheck>(
        future: _check,
        builder: (context, snap) {
          if (snap.hasError) return Text(errText(snap.error!), style: const TextStyle(color: AppColors.danger, fontSize: 12));
          final c = snap.data;
          if (c == null) return const LinearProgressIndicator();
          if (!c.ok || c.surface == null) {
            return Text('Surface refused: ${c.problems.take(3).join('; ')}', style: const TextStyle(fontSize: 12));
          }
          return SurfaceView(
            surface: c.surface!,
            allowTaskActions: true,
            onAction: (a) async {
              if (a.kind == 'task.complete') return _completeTask(a);
              if (a.kind != 'navigate' || a.route == null) return;
              final key = a.route!.split('/').where((x) => x.isNotEmpty).firstOrNull;
              if (key != null && [platformSectionDashboard, ...platformSectionsAfterPlugins].any((x) => x.key == key)) {
                ref.read(selectedNavKeyProvider.notifier).state = key;
              }
            },
          );
        },
      );
}
