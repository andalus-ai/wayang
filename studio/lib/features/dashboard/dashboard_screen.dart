import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../models/wayang.dart';
import '../../design_system/wds.dart';
import '../../providers/app_providers.dart';

WdsExecutionStatus dashboardRunStatus(RunStatus status) => switch (status) {
      RunStatus.running => WdsExecutionStatus.running,
      RunStatus.waiting => WdsExecutionStatus.queued,
      RunStatus.completed => WdsExecutionStatus.succeeded,
      RunStatus.failed => WdsExecutionStatus.failed,
      RunStatus.cancelled => WdsExecutionStatus.cancelled,
    };

Color runStatusColor(RunStatus status) => switch (status) {
      RunStatus.running => AppColors.primaryBright,
      RunStatus.waiting => AppColors.warning,
      RunStatus.completed => AppColors.success,
      RunStatus.failed => AppColors.danger,
      RunStatus.cancelled => AppColors.textMutedDark,
    };

/// One step of the first-run guide on the overview.
class FirstRunStep {
  const FirstRunStep(this.title, this.detail, this.done, this.navKey);
  final String title;
  final String detail;
  final bool done;
  final String navKey;
}

/// The guide for someone new: connect, design a process, run it. [connected] means at least one overview request succeeded.
/// A design-only server has no "run" step. Empty when everything is done (the guide then disappears).
List<FirstRunStep> firstRunSteps({required bool connected, required int processes, required int runs, bool executes = true}) {
  final steps = [
    FirstRunStep('Connect to a server', 'Set the API address and key in Settings.', connected, 'settings'),
    FirstRunStep('Design a process', 'Open the process designer and draw a first flow.', processes > 0, 'bpmn'),
    if (executes) FirstRunStep('Run it', 'Start a run and follow it in Runs.', runs > 0, 'runs'),
  ];
  return steps.every((s) => s.done) ? const [] : steps;
}

/// Compose-mode overview. Every metric and activity item comes from the API;
/// refresh revalidates the same providers used by the rest of Studio.
class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  void _refresh(WidgetRef ref) {
    ref.invalidate(runsProvider);
    ref.invalidate(tasksProvider);
    ref.invalidate(definitionsProvider(DefinitionKind.process));
    ref.invalidate(definitionsProvider(DefinitionKind.decision));
    ref.invalidate(definitionsProvider(DefinitionKind.agent));
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final runs = ref.watch(runsProvider);
    final tasks = ref.watch(tasksProvider);
    final processes = ref.watch(definitionsProvider(DefinitionKind.process));
    final decisions = ref.watch(definitionsProvider(DefinitionKind.decision));
    final agents = ref.watch(definitionsProvider(DefinitionKind.agent));

    String count(AsyncValue<List<Object?>> value) => value.when(
          data: (items) => '${items.length}',
          loading: () => '…',
          error: (_, __) => '—',
        );

    final hasError = runs.hasError || tasks.hasError || processes.hasError ||
        decisions.hasError || agents.hasError;
    final Object? firstError = [runs.error, tasks.error, processes.error, decisions.error, agents.error].whereType<Object>().firstOrNull;
    final connection = firstError != null && needsSettings(firstError);

    return Padding(
      padding: const EdgeInsets.all(20),
      child: RefreshIndicator(
        onRefresh: () async {
          _refresh(ref);
          await Future.wait([
            ref.refresh(runsProvider.future).then((_) {}),
            ref.refresh(tasksProvider.future).then((_) {}),
            ref.refresh(definitionsProvider(DefinitionKind.process).future).then((_) {}),
            ref.refresh(definitionsProvider(DefinitionKind.decision).future).then((_) {}),
            ref.refresh(definitionsProvider(DefinitionKind.agent).future).then((_) {}),
          ]);
        },
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            WdsPageHeader(
              title: 'Overview',
              description: 'Your workflow design workspace at a glance.',
              eyebrow: 'Studio',
              actions: [
                WdsIconButton(
                  tooltip: 'Refresh overview',
                  icon: Icons.refresh_rounded,
                  onPressed: () => _refresh(ref),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: const [
                WdsBadge(
                  label: 'COMPOSE MODE',
                  tone: WdsBadgeTone.brand,
                  icon: Icons.edit_note_rounded,
                ),
                SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Design and validate here. Deployments run in purpose-built Wayang runtimes.',
                    style: TextStyle(fontSize: 11.5, color: AppColors.textMutedDark),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            if (hasError) ...[
              WdsSurface(
                borderColor: AppColors.warning.withValues(alpha: 0.45),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(Icons.info_outline_rounded, color: AppColors.warning),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        connection
                            ? errText(firstError)
                            : 'Some overview data could not be loaded. Check the API connection in Settings; available sections remain usable.',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ),
                    if (connection)
                      TextButton(
                        onPressed: () => ref.read(selectedNavKeyProvider.notifier).state = 'settings',
                        child: const Text('Open Settings'),
                      ),
                    TextButton(
                      onPressed: () => _refresh(ref),
                      child: const Text('Retry'),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
            ],
            Builder(builder: (context) {
              final steps = firstRunSteps(
                connected: runs.hasValue || tasks.hasValue || processes.hasValue || decisions.hasValue || agents.hasValue,
                processes: processes.valueOrNull?.length ?? 0,
                runs: runs.valueOrNull?.length ?? 0,
                executes: ref.watch(executesProvider).maybeWhen(data: (v) => v, orElse: () => true),
              );
              // Wait for the answers so the guide does not flash up while the counts are still loading.
              if (steps.isEmpty || processes.isLoading || runs.isLoading) return const SizedBox.shrink();
              return Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: WdsSurface(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Getting started', style: Theme.of(context).textTheme.titleSmall),
                      const SizedBox(height: 8),
                      for (final step in steps)
                        ListTile(
                          dense: true,
                          contentPadding: EdgeInsets.zero,
                          leading: Icon(
                            step.done ? Icons.check_circle_rounded : Icons.radio_button_unchecked_rounded,
                            color: step.done ? AppColors.success : AppColors.textMutedDark,
                          ),
                          title: Text(step.title),
                          subtitle: Text(step.detail),
                          trailing: step.done ? null : const Icon(Icons.chevron_right_rounded),
                          onTap: step.done ? null : () => ref.read(selectedNavKeyProvider.notifier).state = step.navKey,
                        ),
                    ],
                  ),
                ),
              );
            }),
            LayoutBuilder(
              builder: (context, constraints) {
                final columns = constraints.maxWidth >= 1050
                    ? 4
                    : constraints.maxWidth >= 620
                        ? 2
                        : 1;
                final spacing = 12.0;
                final width = (constraints.maxWidth - spacing * (columns - 1)) / columns;
                return Wrap(
                  spacing: spacing,
                  runSpacing: spacing,
                  children: [
                    SizedBox(
                      width: width,
                      child: KpiCard(
                        label: 'Processes',
                        value: count(processes),
                        icon: Icons.account_tree_rounded,
                      ),
                    ),
                    SizedBox(
                      width: width,
                      child: KpiCard(
                        label: 'Decision tables',
                        value: count(decisions),
                        icon: Icons.rule_folder_rounded,
                        accent: AppColors.secondary,
                      ),
                    ),
                    SizedBox(
                      width: width,
                      child: KpiCard(
                        label: 'Agent definitions',
                        value: count(agents),
                        icon: Icons.smart_toy_outlined,
                        accent: AppColors.tertiary,
                      ),
                    ),
                    SizedBox(
                      width: width,
                      child: KpiCard(
                        label: 'Open tasks',
                        value: count(tasks),
                        icon: Icons.inbox_rounded,
                        accent: AppColors.warning,
                      ),
                    ),
                  ],
                );
              },
            ),
            const SizedBox(height: 26),
            Row(
              children: [
                const Expanded(child: WdsSectionHeader(title: 'Recent runs')),
                TextButton.icon(
                  onPressed: () => ref.read(selectedNavKeyProvider.notifier).state = 'runs',
                  icon: const Icon(Icons.arrow_forward_rounded, size: 15),
                  label: const Text('All runs'),
                ),
              ],
            ),
            const SizedBox(height: 8),
            runs.when(
              loading: () => const LoadingView(label: 'Loading recent runs…'),
              error: (error, _) => WdsSurface(
                child: Text(
                  'Runs are unavailable: ${errText(error)}',
                  style: const TextStyle(color: AppColors.textSecondaryDark, fontSize: 12),
                ),
              ),
              data: (items) => items.isEmpty
                  ? const WdsEmptyState(
                      icon: Icons.play_circle_outline_rounded,
                      title: 'No executions yet',
                      description: 'Run a workflow from a designer to see execution activity here.',
                    )
                  : WdsSurface(
                      padding: EdgeInsets.zero,
                      child: Column(
                        children: [
                          for (var index = 0; index < items.take(8).length; index++) ...[
                            if (index > 0)
                              const Divider(height: 1, indent: 16, endIndent: 16),
                            Builder(
                              builder: (context) {
                                final run = items[index];
                                return ListTile(
                                  contentPadding: const EdgeInsets.symmetric(
                                    horizontal: 16,
                                    vertical: 4,
                                  ),
                                  leading: Container(
                                    width: 34,
                                    height: 34,
                                    decoration: BoxDecoration(
                                      color: AppColors.primary.withValues(alpha: 0.12),
                                      borderRadius: BorderRadius.circular(9),
                                    ),
                                    child: const Icon(
                                      Icons.account_tree_outlined,
                                      size: 17,
                                      color: AppColors.primaryBright,
                                    ),
                                  ),
                                  title: Text(
                                    '${run.processId} · v${run.version}',
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                      fontSize: 12.5,
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                  subtitle: SelectableText(
                                    run.id,
                                    maxLines: 1,
                                    style: const TextStyle(
                                      fontSize: 10.5,
                                      color: AppColors.textMutedDark,
                                    ),
                                  ),
                                  trailing: WdsExecutionStatusIndicator(
                                    status: dashboardRunStatus(run.status),
                                    label: run.status.name,
                                    compact: true,
                                  ),
                                  onTap: () {
                                    ref.read(selectedRunIdProvider.notifier).state = run.id;
                                    ref.read(selectedNavKeyProvider.notifier).state = 'runs';
                                  },
                                );
                              },
                            ),
                          ],
                        ],
                      ),
                    ),
            ),
            const SizedBox(height: 20),
            const WdsSurface(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(Icons.lightbulb_outline_rounded, color: AppColors.secondary),
                  SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Compose once, deploy selectively',
                          style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w700),
                        ),
                        SizedBox(height: 4),
                        Text(
                          'Use the full component library while designing. Wayang packaging selects only the components and runtime capabilities required by each release.',
                          style: TextStyle(
                            fontSize: 11.5,
                            height: 1.45,
                            color: AppColors.textSecondaryDark,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
