import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';

final tasksProvider = FutureProvider.autoDispose<List<TaskView>>((ref) => ref.watch(repositoryProvider).listTasks());

class TasksScreen extends ConsumerWidget {
  const TasksScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tasks = ref.watch(tasksProvider);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ScreenHeader(
            title: 'Task inbox',
            subtitle: 'Open human tasks and runs waiting for a message.',
            icon: Icons.inbox_rounded,
            actions: [
              IconButton(tooltip: 'Refresh', icon: const Icon(Icons.refresh_rounded), onPressed: () => ref.invalidate(tasksProvider)),
              const SizedBox(width: 8),
              OutlinedButton.icon(
                onPressed: () => _messageDialog(context, ref),
                icon: const Icon(Icons.mail_outline_rounded, size: 16),
                label: const Text('Send message'),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Expanded(
            child: tasks.when(
              loading: () => const LoadingView(),
              error: (e, _) => ErrorView(message: errText(e), onRetry: () => ref.invalidate(tasksProvider)),
              data: (list) => list.isEmpty
                  ? const EmptyState(icon: Icons.done_all_rounded, title: 'Nothing waiting')
                  : ListView(children: [
                      for (final t in list)
                        Card(
                          child: ListTile(
                            leading: Icon(t.kind == 'human' ? Icons.person_outline_rounded : t.kind == 'timer' ? Icons.timer_outlined : Icons.mail_outline_rounded),
                            title: Text(t.title ?? t.messageName ?? t.nodeId, style: const TextStyle(fontWeight: FontWeight.w600)),
                            subtitle: Text(
                              [
                                'run ${t.runId}',
                                if (t.assignee != null) 'assignee ${t.assignee}',
                                if (t.dueAt != null) 'due ${formatDateShort(t.dueAt!)}',
                                if (t.correlationKey != null) 'key ${t.correlationKey}',
                              ].join(' · '),
                              style: const TextStyle(fontSize: 11),
                            ),
                            trailing: t.kind == 'human'
                                ? ElevatedButton(onPressed: () => _completeDialog(context, ref, t), child: const Text('Complete'))
                                : null,
                          ),
                        ),
                    ]),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _completeDialog(BuildContext context, WidgetRef ref, TaskView t) async {
    final ctrl = TextEditingController(text: '{}');
    String? error;
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
              onPressed: () async {
                final parsed = tryParseJsonObject(ctrl.text);
                if (parsed == null) return setState(() => error = 'Not a JSON object');
                try {
                  await ref.read(repositoryProvider).completeTask(t.token, parsed);
                  ref.invalidate(tasksProvider);
                  if (ctx.mounted) Navigator.pop(ctx);
                } catch (e) {
                  setState(() => error = errText(e));
                }
              },
              child: const Text('Complete'),
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
