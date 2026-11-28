import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../design_system/wds.dart';
import '../../data/api_client.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';

final tasksProvider = FutureProvider.autoDispose<List<TaskView>>((ref) => ref.watch(repositoryProvider).listTasks());

/// The inbox filter: free text (title, message, node, run, assignee, key) and a kind (`all`, `human`, `timer`, `message`).
class TaskFilter {
  const TaskFilter({this.query = '', this.kind = 'all'});
  final String query;
  final String kind;

  TaskFilter copyWith({String? query, String? kind}) => TaskFilter(query: query ?? this.query, kind: kind ?? this.kind);
}

final taskFilterProvider = StateProvider.autoDispose<TaskFilter>((ref) => const TaskFilter());

/// A task is overdue when it has a due time that has passed.
bool isOverdue(TaskView t, DateTime now) => t.dueAt != null && t.dueAt!.isBefore(now);

/// Tasks matching [f], overdue first, then by due time (soonest first), then those without a due time in their given order.
List<TaskView> visibleTasks(List<TaskView> all, TaskFilter f, DateTime now) {
  final words = f.query.toLowerCase().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).toList();
  bool matches(TaskView t) {
    if (f.kind != 'all' && t.kind != f.kind) return false;
    final hay = [t.title, t.messageName, t.nodeId, t.runId, t.assignee, t.correlationKey].whereType<String>().join(' ').toLowerCase();
    return words.every(hay.contains);
  }

  final hits = all.where(matches).toList();
  int rank(TaskView t) => isOverdue(t, now) ? 0 : t.dueAt != null ? 1 : 2;
  final indexed = {for (var i = 0; i < hits.length; i++) hits[i]: i};
  hits.sort((a, b) {
    final r = rank(a).compareTo(rank(b));
    if (r != 0) return r;
    if (a.dueAt != null && b.dueAt != null) return a.dueAt!.compareTo(b.dueAt!);
    return indexed[a]!.compareTo(indexed[b]!);
  });
  return hits;
}

class TasksScreen extends ConsumerWidget {
  const TasksScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tasks = ref.watch(tasksProvider);
    final filter = ref.watch(taskFilterProvider);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          WdsPageHeader(
            title: 'Task inbox',
            description: 'Open human tasks and runs waiting for a message.',
            actions: [
              WdsIconButton(tooltip: 'Refresh tasks', icon: Icons.refresh_rounded, onPressed: () => ref.invalidate(tasksProvider)),
              WdsButton(label: 'Send message', icon: Icons.mail_outline_rounded, variant: WdsButtonVariant.secondary, onPressed: () => _messageDialog(context, ref)),
            ],
          ),
          const SizedBox(height: 12),
          WdsFilterToolbar(
            search: WdsSearchField(
              hint: 'Search title, run, assignee, key',
              onChanged: (value) => ref.read(taskFilterProvider.notifier).state = filter.copyWith(query: value),
            ),
            filters: [
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final k in const ['all', 'human', 'timer', 'message'])
                    ChoiceChip(
                      label: Text(k == 'all' ? 'All' : '${k[0].toUpperCase()}${k.substring(1)}'),
                      selected: filter.kind == k,
                      onSelected: (_) => ref.read(taskFilterProvider.notifier).state = filter.copyWith(kind: k),
                    ),
                ],
              ),
            ],
          ),
          const SizedBox(height: 12),
          Expanded(
            child: tasks.when(
              loading: () => const LoadingView(),
              error: (e, _) => ErrorView(message: errText(e), needsSettings: needsSettings(e), onRetry: () => ref.invalidate(tasksProvider)),
              data: (all) {
                final now = DateTime.now();
                final list = visibleTasks(all, filter, now);
                if (all.isEmpty) return const WdsEmptyState(icon: Icons.done_all_rounded, title: 'Nothing waiting', description: 'There are no open human tasks or message waits.');
                if (list.isEmpty) return const WdsEmptyState(icon: Icons.filter_alt_off_rounded, title: 'No task matches', description: 'Clear the search or choose All.');
                return ListView(children: [
                      for (final t in list)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: WdsSurface(
                            padding: EdgeInsets.zero,
                            child: ListTile(
                            leading: Icon(t.kind == 'human' ? Icons.person_outline_rounded : t.kind == 'timer' ? Icons.timer_outlined : Icons.mail_outline_rounded),
                            title: Row(
                              children: [
                                Expanded(child: Text(t.title ?? t.messageName ?? t.nodeId, style: const TextStyle(fontWeight: FontWeight.w600))),
                                WdsBadge(
                                  label: t.kind,
                                  tone: t.kind == 'human' ? WdsBadgeTone.info : t.kind == 'timer' ? WdsBadgeTone.warning : WdsBadgeTone.neutral,
                                  dense: true,
                                ),
                                if (isOverdue(t, now)) ...[
                                  const SizedBox(width: 6),
                                  const WdsBadge(label: 'OVERDUE', tone: WdsBadgeTone.danger, dense: true, icon: Icons.warning_amber_rounded),
                                ],
                              ],
                            ),
                            subtitle: Text(
                              [
                                'run ${t.runId}',
                                if (t.assignee != null) 'assignee ${t.assignee}',
                                if (t.dueAt != null) '${isOverdue(t, now) ? 'OVERDUE, due' : 'due'} ${formatDateShort(t.dueAt!)}',
                                if (t.correlationKey != null) 'key ${t.correlationKey}',
                              ].join(' · '),
                              style: TextStyle(fontSize: 11, color: isOverdue(t, now) ? AppColors.danger : null),
                            ),
                            trailing: t.kind == 'human'
                                ? WdsButton(label: 'Complete', onPressed: () => _completeDialog(context, ref, t))
                                : null,
                            ),
                          ),
                        ),
                    ]);
              },
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _completeDialog(BuildContext context, WidgetRef ref, TaskView t) async {
    final ctrl = TextEditingController(text: '{}');
    String? error;
    var completing = false; // a double click must not complete the task twice
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          backgroundColor: AppColors.darkSurfaceRaised,
          title: Text('Complete "${t.title ?? t.nodeId}"'),
          content: SizedBox(
            width: 460,
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              if (t.info.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: Text(const JsonEncoder.withIndent('  ').convert(t.info), style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
                ),
              TextField(
                controller: ctrl,
                minLines: 4,
                maxLines: 10,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                decoration: InputDecoration(labelText: 'Outputs (JSON object)', errorText: error),
              ),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
            ElevatedButton(
              onPressed: completing
                  ? null
                  : () async {
                      final parsed = tryParseJsonObject(ctrl.text);
                      if (parsed == null) return setState(() => error = 'Not a JSON object');
                      setState(() {
                        completing = true;
                        error = null;
                      });
                      try {
                        await ref.read(repositoryProvider).completeTask(t.token, parsed);
                        ref.invalidate(tasksProvider);
                        if (ctx.mounted) Navigator.pop(ctx);
                      } catch (e) {
                        setState(() {
                          completing = false;
                          error = errText(e);
                        });
                      }
                    },
              child: completing
                  ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Complete'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _messageDialog(BuildContext context, WidgetRef ref) async {
    final name = TextEditingController();
    final key = TextEditingController();
    final payload = TextEditingController(text: '{}');
    String? error;
    String? result;
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          backgroundColor: AppColors.darkSurfaceRaised,
          title: const Text('Send a message'),
          content: SizedBox(
            width: 460,
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              TextField(controller: name, decoration: const InputDecoration(labelText: 'Message name')),
              const SizedBox(height: 10),
              TextField(controller: key, decoration: const InputDecoration(labelText: 'Correlation key')),
              const SizedBox(height: 10),
              TextField(
                controller: payload,
                minLines: 3,
                maxLines: 8,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                decoration: InputDecoration(labelText: 'Payload (JSON object)', errorText: error),
              ),
              if (result != null) Padding(padding: const EdgeInsets.only(top: 10), child: Text(result!, style: const TextStyle(color: AppColors.success))),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Close')),
            ElevatedButton(
              onPressed: () async {
                final parsed = tryParseJsonObject(payload.text);
                if (parsed == null) return setState(() => error = 'Not a JSON object');
                try {
                  final r = await ref.read(repositoryProvider).sendMessage(name.text.trim(), key.text.trim(), parsed);
                  ref.invalidate(tasksProvider);
                  setState(() {
                    error = null;
                    result = r == 'BUFFERED' ? 'Buffered: no run is waiting yet; it is delivered when one arrives.' : 'Delivered.';
                  });
                } catch (e) {
                  setState(() => error = errText(e));
                }
              },
              child: const Text('Send'),
            ),
          ],
        ),
      ),
    );
  }
}
