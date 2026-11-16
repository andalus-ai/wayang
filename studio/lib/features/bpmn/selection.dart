import 'dart:ui';

import '../../models/bpmn.dart';
import 'bpmn_canvas.dart';

/// Ids of the nodes whose box touches [rect] (canvas coordinates).
Set<String> nodesInRect(BpmnDiagram diagram, Rect rect) => {
      for (final n in diagram.nodeList)
        if (rect.overlaps(Rect.fromLTWH(n.x, n.y, bpmnNodeSize(n.type).width, bpmnNodeSize(n.type).height))) n.id,
    };

/// The rectangle between two corners, whichever way it was dragged.
Rect rectBetween(Offset a, Offset b) => Rect.fromPoints(a, b);

/// Top offset of each lane in order, and the total height. Diagrams with no lanes are one implicit 600px band.
({List<double> tops, double totalHeight}) laneLayout(List<BpmnLane> lanes) {
  if (lanes.isEmpty) return (tops: const [0], totalHeight: 600);
  final tops = <double>[];
  double y = 0;
  for (final lane in lanes) {
    tops.add(y);
    y += lane.height;
  }
  return (tops: tops, totalHeight: y);
}

/// The lane whose band contains [y], the last lane for anything below them, or null when the diagram has no lanes.
String? laneIdForY(List<BpmnLane> lanes, List<double> tops, double y) {
  if (lanes.isEmpty) return null;
  for (var i = 0; i < lanes.length; i++) {
    final bottom = i + 1 < tops.length ? tops[i + 1] : double.infinity;
    if (y >= tops[i] && y < bottom) return lanes[i].id;
  }
  return lanes.last.id;
}

/// The lane each node in [ids] now belongs to, going by the vertical centre of its box.
Map<String, String?> lanesForNodes(BpmnDiagram diagram, Iterable<String> ids) {
  final lanes = diagram.sortedLanes;
  final tops = laneLayout(lanes).tops;
  return {
    for (final id in ids)
      if (diagram.node(id) case final n?) id: laneIdForY(lanes, tops, n.y + bpmnNodeSize(n.type).height / 2),
  };
}
