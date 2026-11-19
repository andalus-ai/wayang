/// Declared inputs of a catalogue component (`uiHints.inputs`) and whether a node supplies them. "Mandatory" is advisory: a mandatory input
/// that is not supplied never stops a run, the node just gets nothing for it and reports a warning. No Flutter imports: unit-tested alone.
import '../models/wayang.dart';

enum InputState { connected, mapped, empty }

class ComponentInput {
  const ComponentInput({required this.name, required this.type, required this.mandatory, required this.description});
  final String name;
  final String type;
  final bool mandatory;
  final String description;
}

/// The inputs a component declares, mandatory ones first, otherwise in declared order.
List<ComponentInput> componentInputs(ComponentCatalogEntry entry) {
  final out = <ComponentInput>[];
  for (final raw in asList(entry.uiHints['inputs'])) {
    final m = asMap(raw);
    final name = m['name'];
    if (name is! String || name.isEmpty) continue;
    out.add(ComponentInput(
      name: name,
      type: (m['type'] as String?) ?? 'any',
      mandatory: m['mandatory'] == true,
      description: (m['description'] as String?) ?? '',
    ));
  }
  return [...out.where((i) => i.mandatory), ...out.where((i) => !i.mandatory)];
}

/// The catalogue component a task node runs: the one it is bound to, else the connector component named by its `connector`.
ComponentCatalogEntry? componentForNode(List<ComponentCatalogEntry> components, Map<String, dynamic> props) {
  final bound = props['wayang.component.id'];
  if (bound is String && bound.isNotEmpty) {
    for (final c in components) {
      if (c.id == bound) return c;
    }
  }
  final connector = props['connector'];
  if (connector is String && connector.isNotEmpty) {
    for (final c in components) {
      if (c.kind == 'CONNECTOR' && c.id.endsWith('.$connector')) return c;
    }
  }
  return null;
}

/// How a node supplies [input]: a direct `inputs` entry, an `inputMapping` (not analysed, so only "mapped"), or not at all.
InputState inputState(ComponentInput input, Map<String, dynamic> props) {
  final direct = asMap(props['inputs'])[input.name];
  if (direct != null && '$direct'.trim().isNotEmpty) return InputState.connected;
  final mapping = props['inputMapping'];
  if (mapping is Map && mapping.isNotEmpty) return InputState.mapped;
  if (mapping is String && mapping.trim().isNotEmpty) return InputState.mapped;
  return InputState.empty;
}

/// Mandatory inputs the node does not supply: the workflow still runs, these will be empty and reported as warnings.
List<ComponentInput> unsuppliedMandatory(List<ComponentInput> inputs, Map<String, dynamic> props) =>
    [for (final i in inputs) if (i.mandatory && inputState(i, props) == InputState.empty) i];
