import '../models/bpmn.dart';
import '../models/wayang.dart';

/// Thrown when a stored document cannot be shown in the designer (e.g. an element type this Studio does not know).
/// The message is meant for the person; the designer refuses to open the document instead of dropping data.
class DocumentFormatException implements Exception {
  const DocumentFormatException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// Designer model -> the server's native process document (`ProcessMaps` form + the `studio` envelope).
///
/// Layout, lanes and the project go into `studio` (never executed); everything else is the process itself.
/// Element and flow order follow the diagram's insertion order, which keeps documents diff-friendly.
Map<String, dynamic> bpmnToDocument(BpmnDiagram d) {
  final layout = <String, dynamic>{};
  final nodeLanes = <String, dynamic>{};
  final elements = <Map<String, dynamic>>[];
  for (final n in d.nodes.values) {
    elements.add({
      'id': n.id,
      'type': n.type.wireName,
      'name': n.label.isEmpty ? n.id : n.label,
      'config': Map<String, dynamic>.from(n.properties),
    });
    layout[n.id] = {'x': n.x, 'y': n.y};
    if (n.laneId != null) nodeLanes[n.id] = n.laneId;
  }
  final flows = <Map<String, dynamic>>[];
  for (final f in d.flows.values) {
    final c = f.condition?.trim() ?? '';
    flows.add({
      'id': f.id,
      'source': f.from,
      'target': f.to,
      if (c.isNotEmpty) 'condition': c,
      'default': f.isDefault,
    });
  }
  return {
    'id': d.id,
    'name': d.name,
    'elements': elements,
    'flows': flows,
    'studio': {
      if (d.projectId != null) 'projectId': d.projectId,
      if (d.mcp != null) 'mcp': d.mcp,
      'layout': layout,
      'meta': {
        'lanes': [
          for (final l in d.sortedLanes) {'id': l.id, 'name': l.name, 'order': l.order, 'height': l.height},
        ],
        'nodeLanes': nodeLanes,
      },
    },
  };
}

/// The server's process document -> designer model.
///
/// Elements without a stored position get a simple grid so documents written by API clients (or older drafts)
/// open readable. Throws [DocumentFormatException] for element types this Studio cannot represent.
BpmnDiagram bpmnFromDocument(Map<String, dynamic> doc, {int? revision}) {
  final studio = asMap(doc['studio']);
  final layout = asMap(studio['layout']);
  final meta = asMap(studio['meta']);
  final nodeLanes = asMap(meta['nodeLanes']);

  final lanes = <BpmnLane>[];
  var laneIndex = 0;
  for (final l in asList(meta['lanes'])) {
    final m = asMap(l);
    final id = m['id'];
    if (id is! String) continue;
    lanes.add(BpmnLane(
      id: id,
      name: (m['name'] as String?) ?? 'Lane ${laneIndex + 1}',
      order: (m['order'] as num?)?.toInt() ?? laneIndex,
      height: (m['height'] as num?)?.toDouble() ?? 220,
    ));
    laneIndex++;
  }

  final nodes = <String, BpmnNode>{};
  var i = 0;
  for (final e in asList(doc['elements'])) {
    final m = asMap(e);
    final id = m['id'];
    final wire = m['type'];
    if (id is! String || wire is! String) {
      throw const DocumentFormatException('An element has no id or type.');
    }
    final type = BpmnElementType.tryFromWire(wire);
    if (type == null) {
      throw DocumentFormatException('Element "$id" has type $wire, which this Studio version cannot display. '
          'Edit it with another client or update the Studio.');
    }
    final pos = asMap(layout[id]);
    final x = (pos['x'] as num?)?.toDouble() ?? 80.0 + (i % 5) * 200;
    final y = (pos['y'] as num?)?.toDouble() ?? 80.0 + (i ~/ 5) * 140;
    final laneId = nodeLanes[id];
    nodes[id] = BpmnNode(
      id: id,
      type: type,
      x: x,
      y: y,
      label: (m['name'] as String?) ?? id,
      laneId: laneId is String && lanes.any((l) => l.id == laneId) ? laneId : null,
      properties: asMap(m['config']),
    );
    i++;
  }

  final flows = <String, BpmnFlow>{};
  for (final f in asList(doc['flows'])) {
    final m = asMap(f);
    final id = m['id'];
    final source = m['source'];
    final target = m['target'];
    if (id is! String || source is! String || target is! String) {
      throw const DocumentFormatException('A flow has no id, source or target.');
    }
    final cond = m['condition'];
    flows[id] = BpmnFlow(
      id: id,
      from: source,
      to: target,
      condition: cond is String && cond.trim().isNotEmpty ? cond : null,
      isDefault: m['default'] == true,
    );
  }

  return BpmnDiagram(
    id: (doc['id'] as String?) ?? '',
    name: (doc['name'] as String?) ?? (doc['id'] as String?) ?? 'Untitled process',
    lanes: lanes,
    nodes: nodes,
    flows: flows,
    revision: revision,
    projectId: studio['projectId'] as String?,
    mcp: studio['mcp'] is Map ? asMap(studio['mcp']) : null,
  );
}
