import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../plugins/plugin.dart';
import '../../plugins/registry.dart';
import '../../providers/app_providers.dart';
import '../dashboard/dashboard_screen.dart';
import '../projects/projects_screen.dart';
import '../runs/runs_screen.dart';
import '../channels/channels_screen.dart';
import '../mapper/mapper_screen.dart';
import '../mcp/mcp_screen.dart';
import '../secrets/secrets_screen.dart';
import '../workspace/config_screen.dart';
import '../workspace/workspace_screen.dart';
import '../settings/settings_screen.dart';
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
  'runs': _runsBuilder,
  'tasks': _tasksBuilder,
  'projects': _projectsBuilder,
  'channels': _channelsBuilder,
  'mapper': _mapperBuilder,
  'mcp': _mcpBuilder,
  'secrets': _secretsBuilder,
  'config': _configBuilder,
  'workspace': _workspaceBuilder,
  'settings': _settingsBuilder,
};

Widget _runsBuilder(BuildContext context) => const RunsScreen();
Widget _tasksBuilder(BuildContext context) => const TasksScreen();
Widget _projectsBuilder(BuildContext context) => const ProjectsScreen();
Widget _mapperBuilder(BuildContext context) => const MapperScreen();
Widget _mcpBuilder(BuildContext context) => const McpScreen();
Widget _channelsBuilder(BuildContext context) => const ChannelsScreen();
Widget _secretsBuilder(BuildContext context) => const SecretsScreen();
Widget _configBuilder(BuildContext context) => const ConfigScreen();
Widget _workspaceBuilder(BuildContext context) => const WorkspaceScreen();
Widget _settingsBuilder(BuildContext context) => const SettingsScreen();

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
    final navItems = _buildNavItems(plugins);
    final selectedKey = ref.watch(selectedNavKeyProvider);
    final selectedIndex = navItems.indexWhere((n) => n.key == selectedKey);
    final effectiveIndex = selectedIndex >= 0 ? selectedIndex : 0;

    final width = MediaQuery.sizeOf(context).width;
    final extended = width >= 1200;
    final compact = width < 820;

    return Scaffold(
      body: Row(
        children: [
          if (!compact)
            _NavRail(items: navItems, selectedKey: navItems[effectiveIndex].key, extended: extended)
          else
            const SizedBox.shrink(),
          Expanded(
            child: Column(
              children: [
                _TopBar(title: navItems[effectiveIndex].label),
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
      bottomNavigationBar: compact ? _CompactNav(items: navItems, selectedKey: navItems[effectiveIndex].key) : null,
    );
  }
}

class _NavRail extends ConsumerWidget {
  const _NavRail({required this.items, required this.selectedKey, required this.extended});
  final List<_NavItem> items;
  final String selectedKey;
  final bool extended;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Container(
      decoration: const BoxDecoration(
        color: AppColors.darkSurface,
        border: Border(right: BorderSide(color: AppColors.darkBorder)),
      ),
      child: SafeArea(
        child: SizedBox(
          width: extended ? 232 : 84,
          child: Column(
            children: [
              const SizedBox(height: 8),
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 18),
                child: Row(
                  mainAxisAlignment: extended ? MainAxisAlignment.start : MainAxisAlignment.center,
                  children: [
                    if (extended) const SizedBox(width: 20),
                    Container(
                      width: 34,
                      height: 34,
                      decoration: BoxDecoration(
                        gradient: const LinearGradient(colors: AppColors.brandGradient),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: const Icon(Icons.hub_rounded, color: Colors.white, size: 19),
                    ),
                    if (extended) ...[
                      const SizedBox(width: 12),
                      const Text('Wayang', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
                    ],
                  ],
                ),
              ),
              const Divider(height: 1),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 10),
                  children: items.map((s) {
                    final selected = s.key == selectedKey;
                    return Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: Material(
                        color: Colors.transparent,
                        child: InkWell(
                          borderRadius: BorderRadius.circular(AppRadius.md),
                          onTap: () => ref.read(selectedNavKeyProvider.notifier).state = s.key,
                          child: Container(
                            padding: EdgeInsets.symmetric(horizontal: extended ? 12 : 0, vertical: 11),
                            alignment: extended ? Alignment.centerLeft : Alignment.center,
                            decoration: BoxDecoration(
                              color: selected ? AppColors.primary.withValues(alpha: 0.16) : null,
                              borderRadius: BorderRadius.circular(AppRadius.md),
                              border: selected
                                  ? Border.all(color: AppColors.primary.withValues(alpha: 0.4))
                                  : null,
                            ),
                            child: Row(
                              mainAxisSize: extended ? MainAxisSize.max : MainAxisSize.min,
                              children: [
                                Icon(s.icon,
                                    size: 19, color: selected ? AppColors.primaryBright : AppColors.textMutedDark),
                                if (extended) ...[
                                  const SizedBox(width: 12),
                                  Text(
                                    s.label,
                                    style: TextStyle(
                                      fontSize: 13,
                                      fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                                      color: selected ? AppColors.textPrimaryDark : AppColors.textMutedDark,
                                    ),
                                  ),
                                ],
                              ],
                            ),
                          ),
                        ),
                      ),
                    );
                  }).toList(),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CompactNav extends ConsumerWidget {
  const _CompactNav({required this.items, required this.selectedKey});
  final List<_NavItem> items;
  final String selectedKey;

  static const _visibleKeys = ['dashboard', 'bpmn', 'runs', 'tasks', 'settings'];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final visible = [
      for (final key in _visibleKeys) ...items.where((i) => i.key == key),
    ];
    final activeIndex = visible.indexWhere((i) => i.key == selectedKey);
    return NavigationBar(
      backgroundColor: AppColors.darkSurface,
      selectedIndex: activeIndex >= 0 ? activeIndex : 0,
      onDestinationSelected: (i) => ref.read(selectedNavKeyProvider.notifier).state = visible[i].key,
      destinations: visible
          .map((s) => NavigationDestination(icon: Icon(s.icon, size: 20), label: s.label))
          .toList(),
    );
  }
}

class _TopBar extends ConsumerWidget {
  const _TopBar({required this.title});
  final String title;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final project = ref.watch(activeProjectIdProvider);
    final themeMode = ref.watch(themeModeProvider);

    return Container(
      height: 58,
      padding: const EdgeInsets.symmetric(horizontal: 20),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: AppColors.darkBorder)),
      ),
      child: Row(
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(width: 14),
          _EnvPill(label: project == null ? 'All projects' : 'Project: $project'),
          const Spacer(),
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
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: AppColors.darkSurfaceRaised,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 6,
            height: 6,
            decoration: const BoxDecoration(color: AppColors.success, shape: BoxShape.circle),
          ),
          const SizedBox(width: 6),
          Text(label, style: const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w600)),
        ],
      ),
    );
  }
}
