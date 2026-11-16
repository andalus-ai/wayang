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

/// [v] moved to the nearest multiple of [grid] (a grid of 0 or less leaves it alone).
double snapToGrid(double v, double grid) => grid <= 0 ? v : (v / grid).roundToDouble() * grid;

/// The step by which an arrow key moves the selection: a grid cell, or one pixel with shift held for fine adjustment.
double nudgeStep({required bool fine, double grid = 20}) => fine ? 1 : grid;

/// Nodes whose label, id or element-type name contains every word of [query] (case-insensitive), best matches first: a label that starts with
/// the query, then other label matches, then id and type matches. An empty query lists every node in diagram order.
List<BpmnNode> findNodes(BpmnDiagram diagram, String query) {
  final words = query.toLowerCase().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).toList();
  if (words.isEmpty) return diagram.nodeList;
  int score(BpmnNode n) {
    final label = n.label.toLowerCase(), id = n.id.toLowerCase(), type = n.type.label.toLowerCase();
    final hay = '$label $id $type';
    if (!words.every(hay.contains)) return -1;
    if (label.startsWith(words.first)) return 3;
    if (words.every(label.contains)) return 2;
    return 1;
  }
  final scored = [for (final n in diagram.nodeList) (n, score(n))].where((e) => e.$2 >= 0).toList();
  // stable: Dart's sort is not, so break ties by original position
  final order = {for (var i = 0; i < diagram.nodeList.length; i++) diagram.nodeList[i].id: i};
  scored.sort((a, b) => a.$2 != b.$2 ? b.$2.compareTo(a.$2) : order[a.$1.id]!.compareTo(order[b.$1.id]!));
  return [for (final e in scored) e.$1];
}
