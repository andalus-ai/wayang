import 'dart:math' as math;
import 'dart:ui';

import '../../models/bpmn.dart';
import 'selection.dart';

const double layoutColumn = 220;
const double layoutRow = 120;
const double layoutMargin = 60;

/// Left-to-right layered layout, the same idea as the server's `AutoLayout`: a node sits in the column of its longest path from a source
/// (back edges are ignored, so cycles terminate) and nodes in a column are stacked in the order they were added.
///
/// With [only] the layout covers just those nodes and the flows between them, and the result is shifted so the group keeps its top-left
/// corner. With lanes, a node stays in its own lane band (or the first one when it has none), so a tidy-up never changes who owns what.
Map<String, Offset> layeredLayout(BpmnDiagram diagram, {Set<String>? only}) {
  final nodes = [for (final n in diagram.nodeList) if (only == null || only.contains(n.id)) n];
  if (nodes.isEmpty) return const {};
  final ids = {for (final n in nodes) n.id};
  final rank = {for (final n in nodes) n.id: 0};
  final flows = [for (final f in diagram.flowList) if (ids.contains(f.from) && ids.contains(f.to) && f.from != f.to) f];
  for (var pass = 0; pass < nodes.length; pass++) {
    var changed = false;
    for (final f in flows) {
      final rs = rank[f.from]!;
      if (rank[f.to]! < rs + 1 && rs + 1 <= nodes.length) {
        rank[f.to] = rs + 1;
        changed = true;
      }
    }
    if (!changed) break;
  }

  final lanes = diagram.sortedLanes;
  final tops = laneLayout(lanes).tops;
  final rows = <String, int>{};
  final out = <String, Offset>{};
  for (final n in nodes) {
    final col = math.min(rank[n.id]!, nodes.length);
    final laneIndex = lanes.isEmpty ? 0 : math.max(0, lanes.indexWhere((l) => l.id == n.laneId));
    final row = rows.update('$col/$laneIndex', (v) => v + 1, ifAbsent: () => 1) - 1;
    final top = lanes.isEmpty ? layoutMargin : tops[laneIndex] + 40;
    out[n.id] = Offset(layoutMargin + col * layoutColumn, top + row * layoutRow);
  }

  if (only != null) {
    final minX = nodes.map((n) => n.x).reduce(math.min), minY = nodes.map((n) => n.y).reduce(math.min);
    final laidMinX = out.values.map((o) => o.dx).reduce(math.min), laidMinY = out.values.map((o) => o.dy).reduce(math.min);
    final dx = minX - laidMinX;
    // With lanes the rows are already tied to their bands; only the horizontal position follows the group.
    final dy = lanes.isEmpty ? minY - laidMinY : 0.0;
    return {for (final e in out.entries) e.key: e.value.translate(dx, dy)};
  }
  return out;
}
