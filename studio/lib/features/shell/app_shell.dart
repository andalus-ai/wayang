import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../design_system/navigation.dart';
import '../../design_system/components.dart';
import '../../plugins/plugin.dart';
import '../../plugins/registry.dart';
import '../../providers/app_providers.dart';
import '../dashboard/dashboard_screen.dart';
import '../feedback/feedback_dialog.dart';
import 'go_to.dart';
import '../../core/unsaved_edits.dart';
import '../feedback/feedback_screen.dart';
import '../components/component_catalog_screen.dart';
import '../projects/projects_screen.dart';
import '../runs/runs_screen.dart';
import '../channels/channels_screen.dart';
import '../mapper/mapper_screen.dart';
import '../mcp/mcp_screen.dart';
import '../secrets/secrets_screen.dart';
import '../workspace/config_screen.dart';
import '../workspace/workspace_screen.dart';
import '../settings/settings_screen.dart';
import '../surfaces/surfaces_screen.dart';
import '../tasks/tasks_screen.dart';

/// One entry in the assembled navigation — either a fixed platform section
/// or a plugin's [PluginNavEntry], unified so the rail/compact-nav/top-bar
/// don't need to know which.
class _NavItem {
  const _NavItem({required this.key, required this.label, required this.icon, required this.builder});
  final String key;
  final String label;
  final IconData icon;
  final WidgetBuilder builder;
}

const _platformScreenBuilders = <String, WidgetBuilder>{
  'components': _componentsBuilder,
  'runs': _runsBuilder,
  'tasks': _tasksBuilder,
  'projects': _projectsBuilder,
  'channels': _channelsBuilder,
  'mapper': _mapperBuilder,
  'mcp': _mcpBuilder,
  'secrets': _secretsBuilder,
  'config': _configBuilder,
  'workspace': _workspaceBuilder,
  'surfaces': _surfacesBuilder,
  'feedback': _feedbackBuilder,
  'settings': _settingsBuilder,
};

Widget _componentsBuilder(BuildContext context) => const ComponentCatalogScreen();
Widget _runsBuilder(BuildContext context) => const RunsScreen();
Widget _tasksBuilder(BuildContext context) => const TasksScreen();
Widget _projectsBuilder(BuildContext context) => const ProjectsScreen();
Widget _mapperBuilder(BuildContext context) => const MapperScreen();
Widget _mcpBuilder(BuildContext context) => const McpScreen();
Widget _channelsBuilder(BuildContext context) => const ChannelsScreen();
Widget _secretsBuilder(BuildContext context) => const SecretsScreen();
Widget _configBuilder(BuildContext context) => const ConfigScreen();
Widget _workspaceBuilder(BuildContext context) => const WorkspaceScreen();
Widget _surfacesBuilder(BuildContext context) => const SurfacesScreen();
Widget _feedbackBuilder(BuildContext context) => const FeedbackScreen();
Widget _settingsBuilder(BuildContext context) => const SettingsScreen();

const _executionKeys = {'runs', 'tasks'};

/// Assembles the full nav list: Dashboard, then every installed plugin's
/// nav entry (Studio, Process Designer, Decision Tables, ...), then the
/// remaining fixed platform sections. This is the one place plugin
/// screens and platform screens are merged into a single ordered list.
List<_NavItem> _buildNavItems(List<StudioPlugin> plugins) {
  final items = <_NavItem>[
    _NavItem(
      key: platformSectionDashboard.key,
      label: platformSectionDashboard.label,
      icon: platformSectionDashboard.icon,
      builder: (context) => const DashboardScreen(),
    ),
  ];
  for (final entry in allNavEntries(plugins)) {
    items.add(_NavItem(key: entry.key, label: entry.value.label, icon: entry.value.icon, builder: entry.value.builder));
  }
  for (final s in platformSectionsAfterPlugins) {
    items.add(_NavItem(key: s.key, label: s.label, icon: s.icon, builder: _platformScreenBuilders[s.key]!));
  }
  return items;
}

class AppShell extends ConsumerWidget {
  const AppShell({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final plugins = ref.watch(pluginRegistryProvider);
    // Until the answer arrives (or when the server is older) everything is shown; a design-only server hides what it would refuse.
    final executes = ref.watch(executesProvider).maybeWhen(data: (v) => v, orElse: () => true);
    final navItems = _buildNavItems(plugins).where((n) => executes || !_executionKeys.contains(n.key)).toList();
    final selectedKey = ref.watch(selectedNavKeyProvider);
    final selectedIndex = navItems.indexWhere((n) => n.key == selectedKey);
    final effectiveIndex = selectedIndex >= 0 ? selectedIndex : 0;

    final width = MediaQuery.sizeOf(context).width;
    final extended = width >= 1200;
    final compact = width < 820;

    ref.listen<Set<String>>(unsavedEditorsProvider, (_, next) => setBrowserCloseGuard(hasUnsaved(next)));
    final goTo = [for (final n in navItems) GoToEntry(n.key, n.label, n.icon)];
    void openGoTo() => showGoTo(context, ref, goTo);

    return CallbackShortcuts(
      bindings: {
        const SingleActivator(LogicalKeyboardKey.keyK, control: true): openGoTo,
        const SingleActivator(LogicalKeyboardKey.keyK, meta: true): openGoTo,
      },
      child: Focus(
        autofocus: true,
        child: Scaffold(
      body: Row(
        children: [
          if (!compact)
            WdsNavigationRail(
              items: [for (final item in navItems)
                WdsNavigationItem(key: item.key, label: item.label, icon: item.icon)],
              selectedKey: navItems[effectiveIndex].key,
              extended: extended,
              sectionLabel: 'Compose workspace',
              onSelected: (key) => ref.read(selectedNavKeyProvider.notifier).state = key,
            )
          else
            const SizedBox.shrink(),
          Expanded(
            child: Column(
              children: [
                _TopBar(title: navItems[effectiveIndex].label, onGoTo: openGoTo),
                Expanded(
                  child: IndexedStack(
                    index: effectiveIndex,
                    children: [for (final item in navItems) Builder(builder: item.builder)],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
      bottomNavigationBar: compact
          ? WdsCompactNavigationBar(
              items: [
                for (final key in _compactVisibleKeys)
                  for (final item in navItems.where((item) => item.key == key))
                    WdsNavigationItem(key: item.key, label: item.label, icon: item.icon),
              ],
              selectedKey: navItems[effectiveIndex].key,
              onSelected: (key) => ref.read(selectedNavKeyProvider.notifier).state = key,
            )
          : null,
        ),
      ),
    );
  }
}

const _compactVisibleKeys = ['dashboard', 'bpmn', 'runs', 'tasks', 'settings'];

class _TopBar extends ConsumerWidget {
  const _TopBar({required this.title, required this.onGoTo});
  final String title;
  final VoidCallback onGoTo;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final project = ref.watch(activeProjectIdProvider);
    final themeMode = ref.watch(themeModeProvider);

    return Container(
      height: 58,
      padding: const EdgeInsets.symmetric(horizontal: 20),
      decoration: BoxDecoration(
        border: Border(bottom: BorderSide(color: Theme.of(context).colorScheme.outlineVariant)),
      ),
      child: Row(
        children: [
          Flexible(
            child: Text(
              title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
          const SizedBox(width: 10),
          const WdsBadge(
            label: 'COMPOSE',
            tone: WdsBadgeTone.brand,
            dense: true,
            icon: Icons.edit_note_rounded,
          ),
          const SizedBox(width: 10),
          if (MediaQuery.sizeOf(context).width >= 680)
            Flexible(
              child: _EnvPill(
                label: project == null ? 'All projects' : 'Project: $project',
              ),
            ),
          const Spacer(),
          IconButton(tooltip: 'Go to… (Ctrl/Cmd+K)', onPressed: onGoTo, icon: const Icon(Icons.search_rounded)),
          Builder(builder: (context) {
            final caught = ref.watch(lastErrorProvider);
            return IconButton(
              tooltip: caught == null ? 'Send feedback' : 'Something went wrong: report it',
              icon: Icon(caught == null ? Icons.feedback_outlined : Icons.report_problem_rounded, size: 19, color: caught == null ? null : AppColors.danger),
              onPressed: () => showFeedbackDialog(context, error: caught),
            );
          }),
          IconButton(
            tooltip: 'Toggle theme',
            icon: Icon(themeMode == ThemeMode.dark ? Icons.dark_mode_rounded : Icons.light_mode_rounded, size: 19),
            onPressed: () => ref.read(themeModeProvider.notifier).state =
                themeMode == ThemeMode.dark ? ThemeMode.light : ThemeMode.dark,
          ),
        ],
      ),
    );
  }
}

class _EnvPill extends StatelessWidget {
  const _EnvPill({required this.label});
  final String label;

  @override
  Widget build(BuildContext context) {
    return WdsBadge(
      label: label,
      tone: WdsBadgeTone.neutral,
      dense: true,
      icon: Icons.folder_open_outlined,
    );
  }
}
