import 'dart:convert';

import 'package:flutter/material.dart' hide DiagnosticLevel;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart' show errText;
import '../../data/run_events.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';
import '../../providers/bpmn_provider.dart';
import '../runs/runs_screen.dart' show runDetailProvider, runEventsProvider, runStatusColor;
import 'debug_steps.dart';

/// Whether the debug dock under the BPMN canvas is open.
final bpmnDebugOpenProvider = StateProvider<bool>((ref) => false);

/// The run being inspected in the debug dock, or null.
final bpmnDebugRunIdProvider = StateProvider<String?>((ref) => null);

/// Recent runs of the open process.
final processRunsProvider = FutureProvider.autoDispose.family<List<RunView>, String>((ref, processId) {
  return ref.watch(repositoryProvider).listRuns(processId: processId, limit: 20);
});

/// Read-only debug dock: pick a run of this process, step through the nodes it executed (the canvas follows), and watch its variables and
/// live events. It changes nothing on the server and nothing in the diagram.
class BpmnDebugPanel extends ConsumerStatefulWidget {
  const BpmnDebugPanel({super.key, required this.processId});
  final String processId;

  @override
  ConsumerState<BpmnDebugPanel> createState() => _BpmnDebugPanelState();
}

class _BpmnDebugPanelState extends ConsumerState<BpmnDebugPanel> {
  DebugSteps _steps = const DebugSteps([]);
  final List<RunEventView> _events = [];
  String? _eventsFor;

  void _apply(DebugSteps steps) {
    setState(() => _steps = steps);
    final controller = ref.read(bpmnControllerProvider.notifier);
    controller.setHighlight(steps.marked);
    final cur = steps.current;
    if (cur != null) controller.selectNode(cur);
  }

  @override
  Widget build(BuildContext context) {
    final runId = ref.watch(bpmnDebugRunIdProvider);
    final runs = ref.watch(processRunsProvider(widget.processId));
    return Container(
      height: 280,
      decoration: const BoxDecoration(color: AppColors.darkSurface, border: Border(top: BorderSide(color: AppColors.darkBorder))),
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Icon(Icons.bug_report_outlined, size: 16, color: AppColors.textSecondaryDark),
          const SizedBox(width: 8),
          const Text('Debug', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
          const SizedBox(width: 14),
          runs.when(
            loading: () => const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2)),
            error: (e, _) => Text(errText(e), style: const TextStyle(color: AppColors.danger, fontSize: 12)),
            data: (items) => items.isEmpty
                ? const Text('No runs of this process yet.', style: TextStyle(fontSize: 12))
                : DropdownButton<String>(
                    value: items.any((r) => r.id == runId) ? runId : null,
                    hint: const Text('Pick a run', style: TextStyle(fontSize: 12.5)),
                    underline: const SizedBox.shrink(),
                    dropdownColor: AppColors.darkSurfaceRaised,
                    style: const TextStyle(fontSize: 12.5, color: AppColors.textPrimaryDark),
                    items: [
                      for (final r in items)
                        DropdownMenuItem(value: r.id, child: Text('${r.id.length > 10 ? r.id.substring(0, 10) : r.id}  v${r.version}  ${r.status.name}')),
                    ],
                    onChanged: (v) {
                      setState(() {
                        _events.clear();
                        _eventsFor = null;
                      });
                      ref.read(bpmnDebugRunIdProvider.notifier).state = v;
                      _apply(const DebugSteps([]));
                    },
                  ),
          ),
          IconButton(
            tooltip: 'Refresh runs',
            icon: const Icon(Icons.refresh_rounded, size: 16),
            onPressed: () => ref.invalidate(processRunsProvider(widget.processId)),
          ),
          const Spacer(),
          IconButton(
            tooltip: 'Close debug',
            icon: const Icon(Icons.close_rounded, size: 16),
            onPressed: () {
              ref.read(bpmnDebugOpenProvider.notifier).state = false;
              ref.read(bpmnControllerProvider.notifier).setHighlight(const {});
            },
          ),
        ]),
        const SizedBox(height: 4),
        Expanded(child: runId == null ? const Text('Pick a run to step through the path it took.', style: TextStyle(fontSize: 12)) : _body(runId)),
      ]),
    );
  }

  Widget _body(String runId) {
    final run = ref.watch(runDetailProvider(runId));
    if (_eventsFor != runId) {
      _eventsFor = runId;
      _events.clear();
    }
    ref.listen<AsyncValue<RunEventView>>(runEventsProvider(runId), (_, next) {
      final e = next.valueOrNull;
      if (e == null) return;
      setState(() {
        _events.add(e);
        if (_events.length > 200) _events.removeRange(0, _events.length - 200);
      });
    });
    return run.when(
      loading: () => const LoadingView(),
      error: (e, _) => ErrorView(message: errText(e), needsSettings: needsSettings(e)),
      data: (r) {
        if (_steps.executed.length != r.executedNodes.length || !_sameList(_steps.executed, r.executedNodes)) {
          final next = _steps.withExecuted(r.executedNodes);
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted) _apply(next);
          });
        }
        final cur = _steps.current;
        return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SizedBox(
            width: 280,
            child: ListView(children: [
              Row(children: [
                StatusPill(label: r.status.name, color: runStatusColor(r.status), dense: true),
                const SizedBox(width: 8),
                Text('${r.processId} v${r.version}', style: Theme.of(context).textTheme.bodySmall),
              ]),
              if (r.error != null) Padding(padding: const EdgeInsets.only(top: 6), child: Text(r.error!, style: const TextStyle(color: AppColors.danger, fontSize: 12))),
              const SizedBox(height: 8),
              Row(children: [
                IconButton(tooltip: 'Previous step', icon: const Icon(Icons.skip_previous_rounded, size: 18), onPressed: _steps.length == 0 ? null : () => _apply(_steps.previous())),
                IconButton(tooltip: 'Next step', icon: const Icon(Icons.skip_next_rounded, size: 18), onPressed: _steps.length == 0 ? null : () => _apply(_steps.next())),
                TextButton(onPressed: _steps.length == 0 || _steps.all ? null : () => _apply(_steps.showAll()), child: const Text('Whole path')),
              ]),
              Text(_steps.length == 0 ? 'No node has run yet.' : (cur == null ? '${_steps.length} steps (whole path)' : 'Step ${_steps.index + 1} of ${_steps.length}: $cur'), style: const TextStyle(fontSize: 12)),
              if (r.waiting.isNotEmpty) ...[
                const SizedBox(height: 8),
                const PanelSectionLabel('Waiting on'),
                for (final t in r.waiting) Text('${t.kind}: ${t.title ?? t.messageName ?? t.nodeId}', style: const TextStyle(fontSize: 12)),
              ],
            ]),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: ListView(children: [
              const PanelSectionLabel('Variables'),
              SelectableText(const JsonEncoder.withIndent('  ').convert(r.variables), style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
            ]),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: ListView(children: [
              const PanelSectionLabel('Live events (click a node to select it)'),
              if (_events.isEmpty) const Text('Waiting for events…', style: TextStyle(fontSize: 12)),
              for (final e in _events.reversed.take(60))
                InkWell(
                  onTap: e.nodeId == null ? null : () => ref.read(bpmnControllerProvider.notifier).selectNode(e.nodeId!),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 1),
                    child: Text('${e.time == null ? '' : formatDateShort(e.time!)}  ${e.type}${e.nodeId == null ? '' : '  ${e.nodeId}'}',
                        style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
                  ),
                ),
            ]),
          ),
        ]);
      },
    );
  }

  static bool _sameList(List<String> a, List<String> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}
