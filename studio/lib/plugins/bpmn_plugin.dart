import 'package:flutter/material.dart';

import '../features/bpmn/bpmn_screen.dart';
import 'plugin.dart';

/// Visual process designer over the Wayang process definition: swimlanes (stored as studio layout), the node
/// catalog's element types with schema-driven properties, server-side validation, publish and BPMN 2.0 XML
/// import/export. Business-rule tasks reference decisions by id.
class BpmnPlugin extends StudioPlugin {
  const BpmnPlugin();

  @override
  String get id => 'bpmn';

  @override
  String get name => 'Process Designer';

  @override
  String get description => 'BPMN processes: design, validate, publish, import and export.';

  @override
  IconData get icon => Icons.account_tree_rounded;

  @override
  PluginNavEntry get navEntry => PluginNavEntry(
        label: 'Process Designer',
        icon: Icons.account_tree_rounded,
        builder: (context) => const BpmnScreen(),
      );
}
