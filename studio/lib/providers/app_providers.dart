import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/api_client.dart';
import '../data/wayang_repository.dart';
import '../models/wayang.dart';

/// Where the API lives. Build with `--dart-define=WAYANG_API=https://wayang.example.com` to preset it; the token
/// is entered in Settings and kept in memory only (reloading the page asks again).
const _defaultApi = String.fromEnvironment('WAYANG_API', defaultValue: 'http://localhost:8080');

final apiConfigProvider = StateProvider<ApiConfig>((ref) => const ApiConfig(baseUrl: _defaultApi));

/// The single seam between the UI and the server.
final repositoryProvider = Provider<WayangRepository>((ref) {
  return WayangRepository(ApiClient(ref.watch(apiConfigProvider)));
});

/// The node catalog drives the palette and the schema-based property forms.
final nodeCatalogProvider = FutureProvider<List<NodeCatalogEntry>>((ref) {
  return ref.watch(repositoryProvider).nodeCatalog();
});

/// Optional project filter for the explorers (null = all).
final activeProjectIdProvider = StateProvider<String?>((ref) => null);

final projectsProvider = FutureProvider.autoDispose<List<Project>>((ref) {
  return ref.watch(repositoryProvider).listProjects();
});

/// Definition summaries per kind (honouring the project filter).
final definitionsProvider = FutureProvider.autoDispose.family<List<DefinitionSummary>, DefinitionKind>((ref, kind) {
  final project = ref.watch(activeProjectIdProvider);
  return ref.watch(repositoryProvider).listDefinitions(kind, projectId: project);
});

/// A fixed, platform-level navigation section. Plugin-owned sections (Process Designer, Decision Tables, Agents)
/// come from `plugins/registry.dart` and are merged in by the shell.
class PlatformSection {
  const PlatformSection({required this.key, required this.label, required this.icon});
  final String key;
  final String label;
  final IconData icon;
}

const platformSectionDashboard = PlatformSection(key: 'dashboard', label: 'Overview', icon: Icons.space_dashboard_rounded);

/// Sections shown *after* the plugin entries, in order.
const platformSectionsAfterPlugins = [
  PlatformSection(key: 'runs', label: 'Runs', icon: Icons.play_circle_outline_rounded),
  PlatformSection(key: 'tasks', label: 'Task inbox', icon: Icons.inbox_rounded),
  PlatformSection(key: 'projects', label: 'Projects', icon: Icons.folder_copy_rounded),
  PlatformSection(key: 'channels', label: 'Channels', icon: Icons.forum_rounded),
  PlatformSection(key: 'mapper', label: 'Mapper', icon: Icons.swap_horiz_rounded),
  PlatformSection(key: 'mcp', label: 'MCP servers', icon: Icons.hub_rounded),
  PlatformSection(key: 'secrets', label: 'Secrets', icon: Icons.key_rounded),
  PlatformSection(key: 'config', label: 'Configuration', icon: Icons.tune_rounded),
  PlatformSection(key: 'workspace', label: 'Workspace', icon: Icons.inventory_2_outlined),
  PlatformSection(key: 'settings', label: 'Settings', icon: Icons.settings_rounded),
];

/// The nav key of the section currently shown: a [PlatformSection.key] or a plugin's id.
final selectedNavKeyProvider = StateProvider<String>((ref) => platformSectionDashboard.key);

final themeModeProvider = StateProvider<ThemeMode>((ref) => ThemeMode.dark);


/// Reusable runtime component contracts exposed by the active Wayang profile.
final componentCatalogProvider = FutureProvider<List<ComponentCatalogEntry>>((ref) {
  return ref.watch(repositoryProvider).componentCatalog();
});
