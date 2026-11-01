import 'package:flutter/material.dart';

/// A self-contained feature module. The app shell's navigation and the
/// Studio's bottom dock are both *assembled* from the registered plugin
/// list (see `plugins/registry.dart`) rather than hard-coded — adding a
/// new integration paradigm means adding one plugin here, not touching the
/// shell or the Studio.
///
/// A plugin can contribute either or both of:
/// - a top-level navigation section with its own full screen ([navEntry]) —
///   this is how BPMN and DMN plug in as independent designers;
/// - extra tabs inside the Studio's bottom dock ([dockTabs]) — this is how
///   the agentic/n8n layer plugs *into* the existing Studio canvas instead
///   of opening a separate screen, since agent/tool nodes live on the same
///   graph as every other capability.
abstract class StudioPlugin {
  const StudioPlugin();

  /// Stable, unique identifier — used as the nav/tab key, so keep it
  /// constant across versions even if [name] changes.
  String get id;
  String get name;
  String get description;
  IconData get icon;

  /// Non-null when this plugin owns a top-level screen in the shell.
  PluginNavEntry? get navEntry => null;

  /// Extra tabs this plugin adds to the Studio's bottom dock, alongside the
  /// core Diagnostics/Preview/Explain/Compile tabs.
  List<StudioDockTab> get dockTabs => const [];
}

/// A plugin's top-level navigation section: what the shell's nav rail shows
/// and which screen it opens.
class PluginNavEntry {
  const PluginNavEntry({required this.label, required this.icon, required this.builder});
  final String label;
  final IconData icon;
  final WidgetBuilder builder;
}

/// One tab a plugin contributes to the Studio's bottom dock.
class StudioDockTab {
  const StudioDockTab({required this.id, required this.label, required this.icon, required this.builder});
  final String id;
  final String label;
  final IconData icon;
  final WidgetBuilder builder;
}
