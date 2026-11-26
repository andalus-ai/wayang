import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/utils.dart';
import '../../core/widgets.dart';
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

Color runStatusColor(RunStatus s) => switch (s) {
      RunStatus.running => AppColors.info,
      RunStatus.waiting => AppColors.warning,
      RunStatus.completed => AppColors.success,
      RunStatus.failed => AppColors.danger,
      RunStatus.cancelled => AppColors.textMutedDark,
    };

class RunsScreen extends ConsumerWidget {
  const RunsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final runs = ref.watch(runsProvider);
    final selected = ref.watch(selectedRunIdProvider);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ScreenHeader(
            title: 'Runs',
            subtitle: 'Process instances executed by the durable engine.',
            icon: Icons.play_circle_outline_rounded,
            actions: [
              IconButton(tooltip: 'Refresh', icon: const Icon(Icons.refresh_rounded), onPressed: () => ref.invalidate(runsProvider)),
              const SizedBox(width: 8),
              ElevatedButton.icon(
                onPressed: () => _startDialog(context, ref),
                icon: const Icon(Icons.play_arrow_rounded, size: 18),
                label: const Text('Start run'),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Expanded(
            child: runs.when(
              loading: () => const LoadingView(),
              error: (e, _) => ErrorView(message: errText(e), needsSettings: needsSettings(e), onRetry: () => ref.invalidate(runsProvider)),
              data: (list) => list.isEmpty
                  ? const EmptyState(icon: Icons.play_circle_outline_rounded, title: 'No runs yet', message: 'Publish a process and start it.')
                  : Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          flex: 5,
                          child: ListView(
                            children: [
                              for (final r in list)
                                Card(
                                  color: r.id == selected ? AppColors.darkSurfaceRaised : null,
                                  child: ListTile(
                                    dense: true,
                                    title: Text('${r.processId} v${r.version}', style: const TextStyle(fontWeight: FontWeight.w600)),
                                    subtitle: Text('${r.id}${r.startedAt == null ? '' : ' · ${formatDateShort(r.startedAt!)}'}',
                                        style: const TextStyle(fontSize: 11)),
                                    trailing: StatusPill(label: r.status.name, color: runStatusColor(r.status), dense: true),
                                    onTap: () => ref.read(selectedRunIdProvider.notifier).state = r.id,
                                  ),
                                ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          flex: 6,
                          child: selected == null
                              ? const EmptyState(icon: Icons.touch_app_outlined, title: 'Pick a run')
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
            ElevatedButton(
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
              child: starting
                  ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Start'),
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
              StatusPill(label: r.status.name, color: runStatusColor(r.status)),
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
              OutlinedButton.icon(
                icon: const Icon(Icons.account_tree_outlined, size: 16),
                label: const Text('Show path on canvas'),
                onPressed: () {
                  ref.read(runHighlightProvider.notifier).state = r.executedNodes.toSet();
                  ref.read(activeBpmnDiagramIdProvider.notifier).state = r.processId;
                  ref.read(selectedNavKeyProvider.notifier).state = 'bpmn';
                },
              ),
              if (!r.status.isTerminal)
                OutlinedButton.icon(
                  icon: const Icon(Icons.stop_circle_outlined, size: 16, color: AppColors.danger),
                  label: const Text('Cancel run'),
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
            const PanelSectionLabel('Executed nodes'),
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
              const PanelSectionLabel('Waiting on'),
              for (final t in r.waiting)
                Text('${t.kind}: ${t.title ?? t.messageName ?? t.nodeId}', style: const TextStyle(fontSize: 12)),
            ],
            const SizedBox(height: 14),
            _EventLog(id: id),
            const SizedBox(height: 14),
            if (r.variables['surface'] is Map) ...[
              const PanelSectionLabel('Surface'),
              _RunSurface(runId: r.id, surface: Map<String, dynamic>.from(r.variables['surface'] as Map)),
              const SizedBox(height: 14),
            ],
            const PanelSectionLabel('Variables'),
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
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          const PanelSectionLabel('Live events'),
          const SizedBox(width: 8),
          StatusPill(label: live ? 'live' : 'polling', color: live ? AppColors.success : AppColors.textMutedDark, dense: true),
        ]),
        if (_events.isEmpty)
          Text(live ? 'Waiting for events…' : 'Live feed unavailable; the panel refreshes every 2 s.', style: const TextStyle(fontSize: 12))
        else
          for (final e in _events.reversed.take(50))
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 1),
              child: Text(
                '${e.time == null ? '' : formatDateShort(e.time!)}  ${e.type}${e.nodeId == null ? '' : '  ${e.nodeId}'}'
                '${e.isOverflow ? '  (${e.data})' : ''}',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
              ),
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
