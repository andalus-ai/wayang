import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/feedback_model.dart';
import '../../providers/app_providers.dart';
import 'feedback_dialog.dart';

/// Which kind the admin list is filtered to (null = all).
final feedbackKindFilterProvider = StateProvider<String?>((ref) => null);

final feedbackListProvider = FutureProvider.autoDispose<List<FeedbackItem>>(
    (ref) => ref.watch(repositoryProvider).listFeedback(kind: ref.watch(feedbackKindFilterProvider)));

/// What people sent from Studio, newest first. Reading needs the tenant ADMIN role; anyone else sees the server's refusal and can still
/// use "Send feedback" here.
class FeedbackScreen extends ConsumerWidget {
  const FeedbackScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final items = ref.watch(feedbackListProvider);
    final filter = ref.watch(feedbackKindFilterProvider);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ScreenHeader(
            title: 'Feedback',
            subtitle: 'Ratings, comments, bugs, ideas and error reports from your users. Credentials are masked before they are stored.',
            icon: Icons.feedback_outlined,
            actions: [
              OutlinedButton.icon(
                onPressed: () => showFeedbackDialog(context),
                icon: const Icon(Icons.edit_outlined, size: 16),
                label: const Text('Send feedback'),
              ),
              const SizedBox(width: 8),
              OutlinedButton.icon(
                onPressed: () async {
                  try {
                    final csv = await ref.read(repositoryProvider).exportFeedbackCsv(kind: filter);
                    await Clipboard.setData(ClipboardData(text: csv));
                    if (context.mounted) showToast(context, 'Copied as CSV${filter == null ? '' : ' ($filter only)'}');
                  } catch (e) {
                    if (context.mounted) showToast(context, errText(e));
                  }
                },
                icon: const Icon(Icons.content_copy_rounded, size: 16),
                label: const Text('Copy CSV'),
              ),
              const SizedBox(width: 8),
              OutlinedButton.icon(
                onPressed: () async {
                  final command = feedbackExportCommand(ref.read(apiConfigProvider).baseUrl, kind: filter);
                  await Clipboard.setData(ClipboardData(text: command));
                  if (context.mounted) showToast(context, 'Copied a command that downloads the CSV. It holds no token: set WAYANG_TOKEN in your shell.');
                },
                icon: const Icon(Icons.download_rounded, size: 16),
                label: const Text('Export CSV'),
              ),
              const SizedBox(width: 8),
              IconButton(tooltip: 'Refresh', onPressed: () => ref.invalidate(feedbackListProvider), icon: const Icon(Icons.refresh_rounded, size: 18)),
            ],
          ),
          const SizedBox(height: 12),
          Wrap(spacing: 8, children: [
            ChoiceChip(label: const Text('All'), selected: filter == null, onSelected: (_) => ref.read(feedbackKindFilterProvider.notifier).state = null),
            for (final k in FeedbackKind.values)
              ChoiceChip(
                label: Text(k.label),
                selected: filter == k.api,
                onSelected: (_) => ref.read(feedbackKindFilterProvider.notifier).state = k.api,
              ),
          ]),
          const SizedBox(height: 12),
          Expanded(
            child: items.when(
              loading: () => const LoadingView(),
              error: (e, _) => ErrorView(message: errText(e), onRetry: () => ref.invalidate(feedbackListProvider)),
              data: (list) => list.isEmpty
                  ? const EmptyState(icon: Icons.feedback_outlined, title: 'No feedback yet')
                  : ListView(children: [for (final f in list) _FeedbackCard(item: f)]),
            ),
          ),
        ],
      ),
    );
  }
}

class _FeedbackCard extends ConsumerWidget {
  const _FeedbackCard({required this.item});
  final FeedbackItem item;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stack = item.context['stack'];
    final details = {for (final e in item.context.entries) if (e.key != 'stack') e.key: e.value};
    return Card(
      child: ExpansionTile(
        leading: Icon(
          item.rating == 'up'
              ? Icons.thumb_up_alt_rounded
              : item.rating == 'down'
                  ? Icons.thumb_down_alt_rounded
                  : item.kind == 'error'
                      ? Icons.report_problem_rounded
                      : item.kind == 'bug'
                          ? Icons.bug_report_outlined
                          : Icons.chat_bubble_outline_rounded,
          size: 18,
          color: item.kind == 'error' || item.rating == 'down' ? AppColors.danger : null,
        ),
        title: Text(item.message.isEmpty ? item.kindLabel : item.message, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 13)),
        subtitle: Text('${item.kindLabel} · ${item.user}${item.time == null ? '' : ' · ${formatDateShort(item.time!)}'}${item.isDone ? ' · done' : ''}', style: const TextStyle(fontSize: 11)),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        expandedCrossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              icon: Icon(item.isDone ? Icons.undo_rounded : Icons.check_circle_outline_rounded, size: 16),
              label: Text(item.isDone ? 'Reopen' : 'Mark as done'),
              onPressed: () async {
                try {
                  await ref.read(repositoryProvider).setFeedbackStatus(item.id, done: !item.isDone);
                  ref.invalidate(feedbackListProvider);
                } catch (e) {
                  if (context.mounted) showToast(context, errText(e));
                }
              },
            ),
          ),
          if (item.message.isNotEmpty) SelectableText(item.message, style: const TextStyle(fontSize: 12.5)),
          if (details.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: SelectableText([for (final e in details.entries) '${e.key}: ${e.value}'].join('\n'), style: const TextStyle(fontSize: 11, fontFamily: 'monospace')),
            ),
          if (stack != null && stack.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: SelectableText(stack, style: const TextStyle(fontSize: 10.5, fontFamily: 'monospace')),
            ),
        ],
      ),
    );
  }
}
