import 'package:flutter/material.dart';

import '../features/definitions/definitions_screen.dart';
import '../models/wayang.dart';
import 'plugin.dart';

/// Agents (executed by Andalus) edited as validated JSON until a visual builder exists.
class AgentsPlugin extends StudioPlugin {
  const AgentsPlugin();

  @override
  String get id => 'agents';

  @override
  String get name => 'Agents';

  @override
  String get description => 'Agent definitions (model, instructions, tools) executed by Andalus.';

  @override
  IconData get icon => Icons.smart_toy_outlined;

  @override
  PluginNavEntry get navEntry => PluginNavEntry(
        label: 'Agents',
        icon: Icons.smart_toy_outlined,
        builder: (context) => const DefinitionsScreen(kind: DefinitionKind.agent),
      );
}

/// Integration pipelines (EIP) edited as validated JSON until a visual builder exists.
class PipelinesPlugin extends StudioPlugin {
  const PipelinesPlugin();

  @override
  String get id => 'pipelines';

  @override
  String get name => 'Pipelines';

  @override
  String get description => 'Enterprise-integration pipelines run by EIP tasks.';

  @override
  IconData get icon => Icons.hub_outlined;

  @override
  PluginNavEntry get navEntry => PluginNavEntry(
        label: 'Pipelines',
        icon: Icons.hub_outlined,
        builder: (context) => const DefinitionsScreen(kind: DefinitionKind.pipeline),
      );
}
