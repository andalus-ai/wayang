import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'bpmn_plugin.dart';
import 'dmn_plugin.dart';
import 'definitions_plugins.dart';
import 'plugin.dart';

/// Every plugin the app ships with, in the order their nav entries and
/// dock tabs should appear. Adding a new feature module — a new
/// integration paradigm, a new Studio dock panel — means adding one entry
/// here; nothing in the shell or the Studio needs to change.
final pluginRegistryProvider = Provider<List<StudioPlugin>>((ref) => const [
      BpmnPlugin(),
      DmnPlugin(),
      AgentsPlugin(),
      PipelinesPlugin(),
    ]);

/// Every dock tab contributed by any plugin, in registration order.
List<StudioDockTab> allDockTabs(List<StudioPlugin> plugins) => [for (final p in plugins) ...p.dockTabs];

/// Every plugin that owns a top-level nav section, paired with its entry.
List<MapEntry<String, PluginNavEntry>> allNavEntries(List<StudioPlugin> plugins) => [
      for (final p in plugins)
        if (p.navEntry != null) MapEntry(p.id, p.navEntry!),
    ];
