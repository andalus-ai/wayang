import 'package:flutter/widgets.dart';

import '../../models/bpmn.dart';
import 'bpmn_canvas.dart';

/// Alignment guides for a drag: how far to nudge the moving box so an edge or centre meets one of another node's, and where to draw the lines.
class Guides {
  const Guides({this.dx = 0, this.dy = 0, this.vertical = const [], this.horizontal = const []});

  static const none = Guides();

  /// Extra shift to apply to the moving nodes (0 when nothing is close enough).
  final double dx;
  final double dy;

  /// x positions of vertical guide lines, and y positions of horizontal ones, in canvas coordinates.
  final List<double> vertical;
  final List<double> horizontal;

  bool get isEmpty => vertical.isEmpty && horizontal.isEmpty;
}

/// Guides for [moving] (the box the dragged nodes now occupy) against every node not in [movingIds]. Left/centre/right edges are compared on x
/// and top/middle/bottom on y; the closest match within [threshold] pixels on each axis wins, and every other-node line that lands exactly
/// on it is drawn.
Guides alignmentGuides(BpmnDiagram diagram, Set<String> movingIds, Rect moving, {double threshold = 6}) {
  final xs = [moving.left, moving.center.dx, moving.right];
  final ys = [moving.top, moving.center.dy, moving.bottom];
  double? bestDx, bestDy;
  final lineX = <double>[], lineY = <double>[];
  for (final n in diagram.nodeList) {
    if (movingIds.contains(n.id)) continue;
    final s = bpmnNodeSize(n.type);
    final r = Rect.fromLTWH(n.x, n.y, s.width, s.height);
    for (final target in [r.left, r.center.dx, r.right]) {
      for (final mine in xs) {
        final d = target - mine;
        if (d.abs() <= threshold && (bestDx == null || d.abs() < bestDx.abs())) bestDx = d;
      }
    }
    for (final target in [r.top, r.center.dy, r.bottom]) {
      for (final mine in ys) {
        final d = target - mine;
        if (d.abs() <= threshold && (bestDy == null || d.abs() < bestDy.abs())) bestDy = d;
      }
    }
  }
  final dx = bestDx ?? 0, dy = bestDy ?? 0;
  if (bestDx != null || bestDy != null) {
    final shifted = moving.shift(Offset(dx, dy));
    for (final n in diagram.nodeList) {
      if (movingIds.contains(n.id)) continue;
      final s = bpmnNodeSize(n.type);
      final r = Rect.fromLTWH(n.x, n.y, s.width, s.height);
      if (bestDx != null) {
        for (final t in [r.left, r.center.dx, r.right]) {
          if ([shifted.left, shifted.center.dx, shifted.right].any((m) => (m - t).abs() < 0.5)) lineX.add(t);
        }
      }
      if (bestDy != null) {
        for (final t in [r.top, r.center.dy, r.bottom]) {
          if ([shifted.top, shifted.center.dy, shifted.bottom].any((m) => (m - t).abs() < 0.5)) lineY.add(t);
        }
      }
    }
  }
  return Guides(dx: dx, dy: dy, vertical: lineX.toSet().toList()..sort(), horizontal: lineY.toSet().toList()..sort());
}

/// The box covering [ids] when each sits at its [starts] position moved by [dx],[dy].
Rect boxOfMoved(BpmnDiagram diagram, Map<String, Offset> starts, double dx, double dy) {
  Rect? box;
  for (final e in starts.entries) {
    final n = diagram.node(e.key);
    if (n == null) continue;
    final s = bpmnNodeSize(n.type);
    final r = Rect.fromLTWH(e.value.dx + dx, e.value.dy + dy, s.width, s.height);
    box = box == null ? r : box.expandToInclude(r);
  }
  return box ?? Rect.zero;
}

/// Paints [guides] as thin lines across the visible [area].
class GuidesPainter extends CustomPainter {
  GuidesPainter(this.guides, this.color);
  final Guides guides;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final p = Paint()
      ..color = color
      ..strokeWidth = 1;
    for (final x in guides.vertical) {
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), p);
    }
    for (final y in guides.horizontal) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), p);
    }
  }

  @override
  bool shouldRepaint(GuidesPainter old) => old.guides != guides;
}

