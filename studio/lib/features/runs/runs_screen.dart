import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/run_events.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';
import '../../providers/bpmn_provider.dart';

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
              error: (e, _) => ErrorView(message: errText(e), onRetry: () => ref.invalidate(runsProvider)),
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
              onPressed: processId == null
                  ? null
                  : () async {
                      final parsed = tryParseJsonObject(input.text);
                      if (parsed == null) return setState(() => error = 'Not a JSON object');
                      try {
                        final run = await ref.read(repositoryProvider).startRun(processId!, input: parsed);
                        ref.invalidate(runsProvider);
                        ref.read(selectedRunIdProvider.notifier).state = run.id;
                        if (ctx.mounted) Navigator.pop(ctx);
                      } catch (e) {
                        setState(() => error = errText(e));
                      }
                    },
              child: const Text('Start'),
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
      error: (e, _) => ErrorView(message: errText(e)),
      data: (r) => SurfacePanel(
        padding: const EdgeInsets.all(16),
        child: ListView(
          children: [
            Row(children: [
              Expanded(child: Text(r.id, style: const TextStyle(fontWeight: FontWeight.w700))),
              StatusPill(label: r.status.name, color: runStatusColor(r.status)),
            ]),
            const SizedBox(height: 4),
            Text('${r.processId} v${r.version}', style: Theme.of(context).textTheme.bodySmall),
            if (r.error != null) ...[
              const SizedBox(height: 10),
              Text(r.error!, style: const TextStyle(color: AppColors.danger, fontSize: 12)),
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
            Text(r.executedNodes.isEmpty ? '—' : r.executedNodes.join(' → '), style: const TextStyle(fontSize: 12)),
            if (r.waiting.isNotEmpty) ...[
              const SizedBox(height: 14),
              const PanelSectionLabel('Waiting on'),
              for (final t in r.waiting)
                Text('${t.kind}: ${t.title ?? t.messageName ?? t.nodeId}', style: const TextStyle(fontSize: 12)),
            ],
            const SizedBox(height: 14),
            _EventLog(id: id),
            const SizedBox(height: 14),
            const PanelSectionLabel('Variables'),
            SelectableText(const JsonEncoder.withIndent('  ').convert(r.variables), style: const TextStyle(fontFamily: 'monospace', fontSize: 11.5)),
          ],
        ),
      ),
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
