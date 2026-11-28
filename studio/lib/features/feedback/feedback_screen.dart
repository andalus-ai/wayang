import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/feedback_model.dart';
import '../../providers/app_providers.dart';
import '../../design_system/wds.dart';
import 'feedback_dialog.dart';

/// Which kind the admin list is filtered to (null = all).
final feedbackKindFilterProvider = StateProvider<String?>((ref) => null);

final feedbackListProvider = FutureProvider.autoDispose<List<FeedbackItem>>(
    (ref) => ref.watch(repositoryProvider).listFeedback(kind: ref.watch(feedbackKindFilterProvider)));

final _feedbackQueryProvider = StateProvider.autoDispose<String>((ref) => '');
final _feedbackHideDoneProvider = StateProvider.autoDispose<bool>((ref) => false);

final feedbackSummaryProvider = FutureProvider.autoDispose<FeedbackSummary>((ref) => ref.watch(repositoryProvider).feedbackSummary());

/// What people sent from Studio, newest first. Reading needs the tenant ADMIN role; anyone else sees the server's refusal and can still
/// use "Send feedback" here.
class FeedbackScreen extends ConsumerWidget {
  const FeedbackScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final items = ref.watch(feedbackListProvider);
    final filter = ref.watch(feedbackKindFilterProvider);
    final query = ref.watch(_feedbackQueryProvider);
    final hideDone = ref.watch(_feedbackHideDoneProvider);
    final summary = ref.watch(feedbackSummaryProvider).valueOrNull;

    final header = WdsPageHeader(
      title: 'Feedback',
      description:
          'Ratings, comments, bugs, ideas and error reports from your users. Credentials are masked before they are stored.',
      actions: [
        WdsButton(
          label: 'Send feedback',
          icon: Icons.edit_outlined,
          variant: WdsButtonVariant.secondary,
          onPressed: () => showFeedbackDialog(context),
        ),
        WdsButton(
          label: 'Copy CSV',
          icon: Icons.content_copy_rounded,
          variant: WdsButtonVariant.secondary,
          onPressed: () async {
            try {
              final csv = await ref
                  .read(repositoryProvider)
                  .exportFeedbackCsv(kind: filter);
              await Clipboard.setData(ClipboardData(text: csv));
              if (context.mounted) {
                showToast(
                  context,
                  'Copied as CSV${filter == null ? '' : ' ($filter only)'}',
                );
              }
            } catch (e) {
              if (context.mounted) {
                showToast(context, errText(e), isError: true);
              }
            }
          },
        ),
        WdsButton(
          label: 'Export CSV',
          icon: Icons.download_rounded,
          variant: WdsButtonVariant.secondary,
          onPressed: () async {
            final command = feedbackExportCommand(
              ref.read(apiConfigProvider).baseUrl,
              kind: filter,
            );
            await Clipboard.setData(ClipboardData(text: command));
            if (context.mounted) {
              showToast(
                context,
                'Copied a command that downloads the CSV. It holds no token: set WAYANG_TOKEN in your shell.',
              );
            }
          },
        ),
        WdsIconButton(
          tooltip: 'Refresh',
          icon: Icons.refresh_rounded,
          onPressed: () {
            ref.invalidate(feedbackListProvider);
            ref.invalidate(feedbackSummaryProvider);
          },
        ),
      ],
    );

    final filterToolbar = WdsFilterToolbar(
      search: WdsSearchField(
        hint: 'Search feedback',
        onChanged: (value) {
          ref.read(_feedbackQueryProvider.notifier).state = value;
        },
      ),
      filters: [
        ChoiceChip(
          label: const Text('All'),
          selected: filter == null,
          onSelected: (_) {
            ref.read(feedbackKindFilterProvider.notifier).state = null;
          },
        ),
        for (final kind in FeedbackKind.values)
          ChoiceChip(
            label: Text(
              summary != null && summary.openOf(kind.api) > 0
                  ? '${kind.label} (${summary.openOf(kind.api)})'
                  : kind.label,
            ),
            selected: filter == kind.api,
            onSelected: (_) {
              ref.read(feedbackKindFilterProvider.notifier).state = kind.api;
            },
          ),
        FilterChip(
          label: const Text('Hide done'),
          selected: hideDone,
          onSelected: (value) {
            ref.read(_feedbackHideDoneProvider.notifier).state = value;
          },
        ),
      ],
    );

    final content = Expanded(
      child: items.when(
        loading: () => const LoadingView(),
        error: (error, _) => ErrorView(
          message: errText(error),
          needsSettings: needsSettings(error),
          onRetry: () {
            ref.invalidate(feedbackListProvider);
          },
        ),
        data: (list) {
          if (list.isEmpty) {
            return const WdsEmptyState(
              icon: Icons.feedback_outlined,
              title: 'No feedback yet',
              description: 'Feedback submitted by users will appear here.',
            );
          }

          final shown = filterFeedback(list, query, hideDone: hideDone);
          if (shown.isEmpty) {
            return const WdsEmptyState(
              icon: Icons.search_off_rounded,
              title: 'Nothing matches',
              description: 'Change the search or show done items.',
            );
          }
          return ListView(
            children: [for (final item in shown) _FeedbackCard(item: item)],
          );
        },
      ),
    );

    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          header,
          if (summary != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                summary.line,
                style: const TextStyle(fontSize: 12),
              ),
            ),
          const SizedBox(height: 12),
          filterToolbar,
          const SizedBox(height: 12),
          content,
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
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: WdsSurface(
        padding: EdgeInsets.zero,
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
                  ref.invalidate(feedbackSummaryProvider);
                } catch (e) {
                  if (context.mounted) showToast(context, errText(e), isError: true);
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
      ),
    );
  }
}
