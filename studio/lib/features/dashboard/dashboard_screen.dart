import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';
import '../runs/runs_screen.dart';
import '../tasks/tasks_screen.dart';

/// Counts and recent activity from the real API; nothing here is computed from fabricated data.
class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final runs = ref.watch(runsProvider);
    final tasks = ref.watch(tasksProvider);
    final processes = ref.watch(definitionsProvider(DefinitionKind.process));
    final decisions = ref.watch(definitionsProvider(DefinitionKind.decision));
    final agents = ref.watch(definitionsProvider(DefinitionKind.agent));

    String count(AsyncValue<List<Object?>> v) => v.when(data: (l) => '${l.length}', loading: () => '…', error: (_, __) => '—');

    return Padding(
      padding: const EdgeInsets.all(20),
      child: ListView(
        children: [
          const ScreenHeader(title: 'Overview', subtitle: 'Wayang: design it, Gamelan runs it, Andalus thinks.', icon: Icons.space_dashboard_rounded),
          const SizedBox(height: 18),
          if (runs.hasError)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Text('Cannot reach the API: ${errText(runs.error!)}  Check Settings.', style: const TextStyle(color: AppColors.danger, fontSize: 12.5)),
            ),
          Wrap(spacing: 14, runSpacing: 14, children: [
            SizedBox(width: 220, child: KpiCard(label: 'Processes', value: count(processes), icon: Icons.account_tree_rounded)),
            SizedBox(width: 220, child: KpiCard(label: 'Decisions', value: count(decisions), icon: Icons.table_chart_rounded, accent: AppColors.secondary)),
            SizedBox(width: 220, child: KpiCard(label: 'Agents', value: count(agents), icon: Icons.smart_toy_outlined, accent: AppColors.tertiary)),
            SizedBox(width: 220, child: KpiCard(label: 'Open tasks', value: count(tasks), icon: Icons.inbox_rounded, accent: AppColors.warning)),
          ]),
          const SizedBox(height: 22),
          const PanelSectionLabel('Recent runs'),
          runs.when(
            loading: () => const LoadingView(),
            error: (_, __) => const SizedBox.shrink(),
            data: (list) => list.isEmpty
                ? const Text('No runs yet.', style: TextStyle(fontSize: 12.5))
                : Column(children: [
                    for (final r in list.take(8))
                      ListTile(
                        dense: true,
                        title: Text('${r.processId} v${r.version}'),
                        subtitle: Text(r.id, style: const TextStyle(fontSize: 11)),
                        trailing: StatusPill(label: r.status.name, color: runStatusColor(r.status), dense: true),
                        onTap: () {
                          ref.read(selectedRunIdProvider.notifier).state = r.id;
                          ref.read(selectedNavKeyProvider.notifier).state = 'runs';
                        },
                      ),
                  ]),
          ),
        ],
      ),
    );
  }
}
