import 'dart:convert';

import 'package:flutter/material.dart' hide DiagnosticLevel;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/widgets.dart';
import '../../models/bpmn.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';
import '../../providers/bpmn_provider.dart';
import '../common/schema_form.dart';
import 'bpmn_canvas.dart';
import 'node_test_section.dart';
import 'soap_section.dart';

// ---------------------------------------------------------------------------
// Palette (left panel) — grouped by the server's node catalog categories
// ---------------------------------------------------------------------------

class BpmnPalette extends ConsumerWidget {
  const BpmnPalette({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final catalog = ref.watch(nodeCatalogProvider);
    return Container(
      width: 220,
      decoration: const BoxDecoration(color: AppColors.darkSurface, border: Border(right: BorderSide(color: AppColors.darkBorder))),
      child: catalog.when(
        loading: () => const LoadingView(),
        // The palette needs only the type list; fall back to the built-in one so the designer stays usable
        // (offline editing) — the inspector then has no schema and says so.
        error: (e, _) => _list(_fallbackGroups()),
        data: (entries) {
          final groups = <String, List<BpmnElementType>>{};
          for (final e in entries) {
            final t = BpmnElementType.tryFromWire(e.type);
            if (t != null) groups.putIfAbsent(e.category, () => []).add(t);
          }
          return _list(groups);
        },
      ),
    );
  }

  Map<String, List<BpmnElementType>> _fallbackGroups() => {
        'Events': BpmnElementType.values.where((t) => t.isEvent).toList(),
        'Tasks': BpmnElementType.values.where((t) => t.isTask).toList(),
        'Gateways': BpmnElementType.values.where((t) => t.isGateway).toList(),
      };

  Widget _list(Map<String, List<BpmnElementType>> groups) {
    return ListView(
      padding: const EdgeInsets.all(14),
      children: [
        for (final g in groups.entries) ...[
          PanelSectionLabel(g.key),
          ...g.value.map((t) => _PaletteItem(type: t)),
          const SizedBox(height: 16),
        ],
      ],
    );
  }
}

class _PaletteItem extends StatelessWidget {
  const _PaletteItem({required this.type});
  final BpmnElementType type;

  @override
  Widget build(BuildContext context) {
    final color = bpmnNodeColor(type);
    final content = Container(
      margin: const EdgeInsets.only(bottom: 6),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
      decoration: BoxDecoration(
        color: AppColors.darkSurfaceAlt,
        borderRadius: BorderRadius.circular(AppRadius.md),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(6),
            decoration: BoxDecoration(color: color.withValues(alpha: 0.16), borderRadius: BorderRadius.circular(7)),
            child: Icon(type.icon, size: 14, color: color),
          ),
          const SizedBox(width: 10),
          Expanded(child: Text(type.label, style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600))),
        ],
      ),
    );

    return Draggable<BpmnElementType>(
      data: type,
      feedback: Material(color: Colors.transparent, child: Opacity(opacity: 0.85, child: SizedBox(width: 190, child: content))),
      childWhenDragging: Opacity(opacity: 0.35, child: content),
      child: content,
    );
  }
}

// Component identity is rendered in its own catalog-backed control rather than as
// three free-form JSON-schema fields.
Map<String, dynamic> _withoutComponentBinding(Map<String, dynamic> schema) {
  final copy = Map<String, dynamic>.from(schema);
  final properties = Map<String, dynamic>.from(asMap(schema['properties']));
  properties.remove('wayang.component.id');
  properties.remove('wayang.component.version');
  properties.remove('wayang.component.operation');
  copy['properties'] = properties;
  final required = asList(schema['required']).where((key) =>
      key != 'wayang.component.id' && key != 'wayang.component.version' && key != 'wayang.component.operation').toList();
  if (required.isEmpty) {
    copy.remove('required');
  } else {
    copy['required'] = required;
  }
  return copy;
}

class _ComponentBindingSection extends ConsumerWidget {
  const _ComponentBindingSection({super.key, required this.node, required this.onChanged});

  final BpmnNode node;
  final ValueChanged<Map<String, dynamic>> onChanged;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final catalog = ref.watch(componentCatalogProvider);
    final selectedId = node.properties['wayang.component.id'] as String?;
    final selectedVersion = node.properties['wayang.component.version'] as String?;
    final operation = node.properties['wayang.component.operation'] as String? ?? 'execute';

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const PanelSectionLabel('Runtime component'),
        catalog.when(
          loading: () => const LinearProgressIndicator(minHeight: 2),
          error: (error, _) => Text('Component catalog unavailable: $error',
              style: const TextStyle(fontSize: 11, color: AppColors.danger)),
          data: (components) {
            final ids = components.map((component) => component.id).toSet();
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                DropdownButtonFormField<String>(
                  value: selectedId != null && ids.contains(selectedId) ? selectedId : null,
                  isExpanded: true,
                  decoration: const InputDecoration(isDense: true, hintText: 'Use default node handler'),
                  items: [
                    const DropdownMenuItem<String>(value: '', child: Text('Default node handler')),
                    for (final component in components)
                      DropdownMenuItem<String>(
                        value: component.id,
                        child: Text('${component.displayName} · ${component.version}',
                            overflow: TextOverflow.ellipsis),
                      ),
                    if (selectedId != null && !ids.contains(selectedId))
                      DropdownMenuItem<String>(value: selectedId, child: Text('$selectedId (unavailable)')),
                  ],
                  onChanged: (value) {
                    final next = Map<String, dynamic>.from(node.properties);
                    if (value == null || value.isEmpty) {
                      next.remove('wayang.component.id');
                      next.remove('wayang.component.version');
                      next.remove('wayang.component.operation');
                    } else {
                      final component = components.where((item) => item.id == value).firstOrNull;
                      next['wayang.component.id'] = value;
                      if (component != null) next['wayang.component.version'] = component.version;
                    }
                    onChanged(next);
                  },
                ),
                if (selectedId != null && selectedId.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Text('Component ID: $selectedId',
                      style: const TextStyle(fontSize: 10.5, color: AppColors.textMutedDark, fontFamily: 'monospace')),
                  if (selectedVersion != null)
                    Text('Pinned version: $selectedVersion',
                        style: const TextStyle(fontSize: 10.5, color: AppColors.textMutedDark)),
                  const SizedBox(height: 6),
                  TextFormField(
                    key: ValueKey('component-operation-${node.id}-$operation'),
                    initialValue: operation,
                    decoration: const InputDecoration(isDense: true, labelText: 'Operation', hintText: 'execute'),
                    onFieldSubmitted: (value) {
                      final next = Map<String, dynamic>.from(node.properties);
                      next['wayang.component.operation'] = value.trim().isEmpty ? 'execute' : value.trim();
                      onChanged(next);
                    },
                  ),
                ],
              ],
            );
          },
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Inspector (right panel)
// ---------------------------------------------------------------------------

class BpmnInspector extends ConsumerWidget {
  const BpmnInspector({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(bpmnControllerProvider);

    return Container(
      width: 320,
      decoration: const BoxDecoration(color: AppColors.darkSurface, border: Border(left: BorderSide(color: AppColors.darkBorder))),
      child: state.selectedNode != null
          ? _NodeInspector(node: state.selectedNode!)
          : state.selectedFlow != null
              ? _FlowInspector(flow: state.selectedFlow!)
              : const _DiagramInspector(),
    );
  }
}

/// Config keys that hold retry/timeout/error policy (shown collapsed).
const _policyKeys = {'retries', 'retryDelay', 'retryMaxDelay', 'retryBackoff', 'timeout', 'onError'};

class _NodeInspector extends ConsumerStatefulWidget {
  const _NodeInspector({required this.node});
  final BpmnNode node;

  @override
  ConsumerState<_NodeInspector> createState() => _NodeInspectorState();
}

class _NodeInspectorState extends ConsumerState<_NodeInspector> {
  late TextEditingController _labelController;

  @override
  void initState() {
    super.initState();
    _labelController = TextEditingController(text: widget.node.label);
  }

  @override
  void didUpdateWidget(covariant _NodeInspector oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.node.id != widget.node.id) _labelController.text = widget.node.label;
  }

  @override
  void dispose() {
    _labelController.dispose();
    super.dispose();
  }

  /// A dropdown of published/draft definition ids of [kind]; keeps an unknown current value selectable so a
  /// reference to a not-yet-listed definition is never silently dropped.
  FieldBuilder _definitionPicker(DefinitionKind kind) {
    return (context, value, onChanged) => Consumer(builder: (context, ref, _) {
          final list = ref.watch(definitionsProvider(kind));
          return list.when(
            loading: () => const LinearProgressIndicator(minHeight: 2),
            error: (e, _) => Text('$e', style: const TextStyle(fontSize: 11, color: AppColors.danger)),
            data: (items) {
              final ids = items.map((d) => d.id).toList();
              final current = value is String ? value : null;
              if (value != null && value is! String) {
                return const Text('Inline spec — edit it in the Agents editor.', style: TextStyle(fontSize: 11, color: AppColors.textMutedDark));
              }
              return DropdownButtonFormField<String>(
                key: ValueKey('${kind.name}-$current'),
                value: current,
                isExpanded: true,
                decoration: const InputDecoration(isDense: true, hintText: 'Select'),
                items: [
                  for (final d in items) DropdownMenuItem(value: d.id, child: Text(d.name, overflow: TextOverflow.ellipsis)),
                  if (current != null && !ids.contains(current)) DropdownMenuItem(value: current, child: Text('$current (not found)')),
                ],
                onChanged: onChanged,
              );
            },
          );
        });
  }

  @override
  Widget build(BuildContext context) {
    final node = widget.node;
    final controller = ref.read(bpmnControllerProvider.notifier);
    final diagram = ref.watch(bpmnControllerProvider.select((s) => s.diagram));
    final catalog = ref.watch(nodeCatalogProvider);
    final color = bpmnNodeColor(node.type);

    NodeCatalogEntry? entry;
    for (final e in catalog.valueOrNull ?? const <NodeCatalogEntry>[]) {
      if (e.type == node.type.wireName) entry = e;
    }

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(color: color.withValues(alpha: 0.14), borderRadius: BorderRadius.circular(8)),
              child: Icon(node.type.icon, size: 16, color: color),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Element', style: Theme.of(context).textTheme.bodySmall),
                  Text(node.type.label, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700)),
                ],
              ),
            ),
            IconButton(
              tooltip: 'Delete',
              icon: const Icon(Icons.delete_outline_rounded, size: 18, color: AppColors.danger),
              onPressed: () => controller.removeSelected(),
            ),
          ],
        ),
        if (entry != null && entry.description.isNotEmpty) ...[
          const SizedBox(height: 8),
          Text(entry.description, style: const TextStyle(fontSize: 11, color: AppColors.textMutedDark)),
        ],
        const SizedBox(height: 18),
        const PanelSectionLabel('Label'),
        TextField(
          controller: _labelController,
          style: const TextStyle(fontSize: 13),
          decoration: const InputDecoration(isDense: true),
          onSubmitted: (v) => controller.updateNodeLabel(node.id, v),
          onEditingComplete: () => controller.updateNodeLabel(node.id, _labelController.text),
        ),
        if (diagram.lanes.isNotEmpty) ...[
          const SizedBox(height: 16),
          const PanelSectionLabel('Lane'),
          DropdownButtonFormField<String>(
            value: node.laneId,
            decoration: const InputDecoration(isDense: true),
            items: diagram.sortedLanes.map((l) => DropdownMenuItem(value: l.id, child: Text(l.name))).toList(),
            onChanged: (v) {
              if (v != null) controller.endDragNode(node.id, laneId: v);
            },
          ),
        ],
        if (node.type.isTask) ...[
          const SizedBox(height: 16),
          _ComponentBindingSection(
            key: ValueKey('component-binding-${node.id}'),
            node: node,
            onChanged: (props) => controller.updateNodeProperties(node.id, props),
          ),
        ],
        const SizedBox(height: 16),
        const PanelSectionLabel('Configuration'),
        if (entry == null)
          Text(
            catalog.isLoading ? 'Loading schema…' : 'The element schema is not available (is the server reachable?). Existing configuration is kept as is.',
            style: const TextStyle(fontSize: 11.5, color: AppColors.textMutedDark),
          )
        else if (entry.properties.isEmpty)
          const Text('Nothing to configure.', style: TextStyle(fontSize: 11.5, color: AppColors.textMutedDark))
        else
          SchemaForm(
            identity: node.id,
            schema: _withoutComponentBinding(entry.configSchema),
            value: node.properties,
            advancedKeys: _policyKeys,
            overrides: {
              if (node.type == BpmnElementType.businessRuleTask) 'model': _definitionPicker(DefinitionKind.decision),
              if (node.type == BpmnElementType.agentTask) 'agent': _definitionPicker(DefinitionKind.agent),
            },
            onChanged: (props) => controller.updateNodeProperties(node.id, props),
          ),
        if (node.type == BpmnElementType.serviceTask && node.properties['connector'] == 'soap')
          SoapSection(props: node.properties, onChanged: (props) => controller.updateNodeProperties(node.id, props)),
        if (node.type == BpmnElementType.serviceTask) NodeTestSection(key: ValueKey('test-${node.id}'), props: node.properties),
      ],
    );
  }
}

class _FlowInspector extends ConsumerStatefulWidget {
  const _FlowInspector({required this.flow});
  final BpmnFlow flow;

  @override
  ConsumerState<_FlowInspector> createState() => _FlowInspectorState();
}

class _FlowInspectorState extends ConsumerState<_FlowInspector> {
  late TextEditingController _conditionController;

  @override
  void initState() {
    super.initState();
    _conditionController = TextEditingController(text: widget.flow.condition ?? '');
  }

  @override
  void didUpdateWidget(covariant _FlowInspector oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.flow.id != widget.flow.id) _conditionController.text = widget.flow.condition ?? '';
  }

  @override
  void dispose() {
    _conditionController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final flow = widget.flow;
    final controller = ref.read(bpmnControllerProvider.notifier);

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(color: AppColors.primaryBright.withValues(alpha: 0.14), borderRadius: BorderRadius.circular(8)),
              child: const Icon(Icons.arrow_right_alt_rounded, size: 16, color: AppColors.primaryBright),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Sequence flow', style: Theme.of(context).textTheme.bodySmall),
                  Text('${flow.from} → ${flow.to}', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700)),
                ],
              ),
            ),
            IconButton(
              tooltip: 'Remove',
              icon: const Icon(Icons.link_off_rounded, size: 18, color: AppColors.danger),
              onPressed: () => controller.removeSelected(),
            ),
          ],
        ),
        const SizedBox(height: 18),
        const PanelSectionLabel('Condition expression'),
        TextField(
          controller: _conditionController,
          style: const TextStyle(fontSize: 13, fontFamily: 'monospace'),
          decoration: const InputDecoration(isDense: true, hintText: 'e.g. amount > 5000'),
          onSubmitted: (v) => controller.setFlowCondition(flow.id, v),
          onEditingComplete: () => controller.setFlowCondition(flow.id, _conditionController.text),
        ),
        const SizedBox(height: 16),
        Material(
          color: Colors.transparent,
          child: SwitchListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: const Text('Default flow', style: TextStyle(fontSize: 12.5)),
            subtitle: const Text('Taken when no other outgoing condition matches', style: TextStyle(fontSize: 10.5, color: AppColors.textMutedDark)),
            value: flow.isDefault,
            onChanged: (v) => controller.setFlowDefault(flow.id, v),
          ),
        ),
      ],
    );
  }
}

class _DiagramInspector extends ConsumerWidget {
  const _DiagramInspector();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(bpmnControllerProvider);
    final controller = ref.read(bpmnControllerProvider.notifier);

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(state.diagram.name, style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 4),
        Text('${state.diagram.nodeCount} elements · ${state.diagram.flowList.length} flows', style: Theme.of(context).textTheme.bodySmall),
        const SizedBox(height: 18),
        PanelSectionLabel(
          'Lanes (${state.diagram.lanes.length})',
          trailing: TextButton.icon(
            onPressed: controller.addLane,
            icon: const Icon(Icons.add_rounded, size: 14),
            label: const Text('Add', style: TextStyle(fontSize: 11.5)),
          ),
        ),
        ...state.diagram.sortedLanes.map((lane) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                children: [
                  Expanded(
                    child: Text(lane.name, style: const TextStyle(fontSize: 12.5)),
                  ),
                  IconButton(
                    tooltip: 'Rename',
                    icon: const Icon(Icons.edit_outlined, size: 14),
                    onPressed: () async {
                      final name = await _promptText(context, 'Rename lane', lane.name);
                      if (name != null && name.trim().isNotEmpty) controller.renameLane(lane.id, name.trim());
                    },
                  ),
                  IconButton(
                    tooltip: 'Delete',
                    icon: const Icon(Icons.delete_outline_rounded, size: 14, color: AppColors.danger),
                    onPressed: () => controller.removeLane(lane.id),
                  ),
                ],
              ),
            )),
        const SizedBox(height: 18),
        const PanelSectionLabel('MCP tool'),
        _McpSection(key: ValueKey(jsonEncode(state.diagram.mcp)), mcp: state.diagram.mcp, onChanged: controller.setMcp),
        const SizedBox(height: 18),
        PanelSectionLabel('Diagnostics (${state.diagnostics.length})'),
        if (state.diagnostics.isEmpty)
          const Text('No issues found.', style: TextStyle(fontSize: 12, color: AppColors.textSecondaryDark))
        else
          ...state.diagnostics.map((d) => Padding(
                padding: const EdgeInsets.symmetric(vertical: 5),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(d.severity.icon, size: 14, color: d.severity.color),
                    const SizedBox(width: 8),
                    Expanded(child: Text(d.message, style: const TextStyle(fontSize: 11.5, color: AppColors.textSecondaryDark))),
                  ],
                ),
              )),
      ],
    );
  }

  Future<String?> _promptText(BuildContext context, String title, String initial) async {
    final ctrl = TextEditingController(text: initial);
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurfaceRaised,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.lg)),
        title: Text(title),
        content: TextField(controller: ctrl, autofocus: true),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          ElevatedButton(onPressed: () => Navigator.pop(ctx, ctrl.text), child: const Text('Save')),
        ],
      ),
    );
  }
}

/// Opt-in exposure of the published process as an MCP tool (`studio.mcp`). Takes effect on the next publish.
class _McpSection extends StatefulWidget {
  const _McpSection({super.key, required this.mcp, required this.onChanged});

  final Map<String, dynamic>? mcp;
  final void Function(Map<String, dynamic>? mcp) onChanged;

  @override
  State<_McpSection> createState() => _McpSectionState();
}

class _McpSectionState extends State<_McpSection> {
  late final TextEditingController _desc;
  late final TextEditingController _wait;
  late final TextEditingController _schema;
  String? _error;

  Map<String, dynamic> get _cfg => widget.mcp ?? const <String, dynamic>{};
  bool get _on => _cfg['expose'] == true;

  @override
  void initState() {
    super.initState();
    _desc = TextEditingController(text: (_cfg['description'] as String?) ?? '');
    _wait = TextEditingController(text: _cfg['waitSeconds'] == null ? '' : '${_cfg['waitSeconds']}');
    _schema = TextEditingController(text: _cfg['inputSchema'] == null ? '' : const JsonEncoder.withIndent('  ').convert(_cfg['inputSchema']));
  }

  @override
  void dispose() {
    _desc.dispose();
    _wait.dispose();
    _schema.dispose();
    super.dispose();
  }

  void _apply(bool expose) {
    final out = <String, dynamic>{'expose': expose};
    final d = _desc.text.trim();
    if (d.isNotEmpty) {
      if (d.length > 1000) return setState(() => _error = 'Description is limited to 1000 characters.');
      out['description'] = d;
    }
    final w = _wait.text.trim();
    if (w.isNotEmpty) {
      final n = int.tryParse(w);
      if (n == null || n < 0 || n > 60) return setState(() => _error = 'Wait must be a whole number of seconds, 0 to 60.');
      out['waitSeconds'] = n;
    }
    final sc = _schema.text.trim();
    if (sc.isNotEmpty) {
      try {
        final v = jsonDecode(sc);
        if (v is! Map) throw const FormatException('must be a JSON object');
        out['inputSchema'] = v;
      } on FormatException catch (e) {
        return setState(() => _error = 'Input schema is not valid JSON: ${e.message}');
      }
    }
    setState(() => _error = null);
    widget.onChanged(out);
  }

  @override
  Widget build(BuildContext context) {
    const mono = TextStyle(fontFamily: 'monospace', fontSize: 11.5);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SwitchListTile(
          dense: true,
          contentPadding: EdgeInsets.zero,
          title: const Text('Expose as MCP tool', style: TextStyle(fontSize: 12.5)),
          subtitle: const Text('Agents and MCP clients can start this process after you publish. Needs run permission.', style: TextStyle(fontSize: 11)),
          value: _on,
          onChanged: _apply,
        ),
        if (_on) ...[
          TextField(
            controller: _desc,
            decoration: const InputDecoration(labelText: 'Tool description', isDense: true),
            onEditingComplete: () => _apply(true),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _wait,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: 'Wait for result (seconds, 0-60, default 20)', isDense: true),
            onEditingComplete: () => _apply(true),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _schema,
            maxLines: 6,
            style: mono,
            decoration: const InputDecoration(labelText: 'Input schema (JSON Schema; empty = any object)', isDense: true, alignLabelWithHint: true),
            onEditingComplete: () => _apply(true),
          ),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton(onPressed: () => _apply(true), child: const Text('Apply')),
          ),
          if (_error != null) Text(_error!, style: const TextStyle(fontSize: 11.5, color: AppColors.danger)),
        ],
      ],
    );
  }
}
