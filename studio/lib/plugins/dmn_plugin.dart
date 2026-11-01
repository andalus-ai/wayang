import 'package:flutter/material.dart';

import '../features/dmn/dmn_screen.dart';
import 'plugin.dart';

/// Business rules as decision tables, tested, validated and published through the server's DMN engine.
class DmnPlugin extends StudioPlugin {
  const DmnPlugin();

  @override
  String get id => 'dmn';

  @override
  String get name => 'DMN Decision Tables';

  @override
  String get description => 'Decision tables: hit policies, server-side test and validation, publish.';

  @override
  IconData get icon => Icons.table_chart_rounded;

  @override
  PluginNavEntry get navEntry => PluginNavEntry(
        label: 'Decision Tables',
        icon: Icons.table_chart_rounded,
        builder: (context) => const DmnScreen(),
      );
}
