import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/material.dart' hide DiagnosticLevel;

import '../../core/theme.dart';
import '../../models/bpmn.dart';
import 'bpmn_canvas.dart';
import 'selection.dart';

/// Maps between canvas coordinates and a minimap of a given pixel [size]. The world shown is every node plus the visible [viewport],
/// padded, so the viewport rectangle is never off the map.
class MinimapGeometry {
  MinimapGeometry._(this.world, this.scale, this.offset, this.size);

  final Rect world;
  final double scale;
  final Offset offset; // centres the world inside the minimap when its aspect differs
  final Size size;

  factory MinimapGeometry.of(BpmnDiagram diagram, Rect viewport, Size size) {
    var world = viewport;
    for (final n in diagram.nodeList) {
      final s = bpmnNodeSize(n.type);
      world = world.expandToInclude(Rect.fromLTWH(n.x, n.y, s.width, s.height));
    }
    for (final n in diagram.noteList) {
      world = world.expandToInclude(Rect.fromLTWH(n.x, n.y, BpmnNote.width, BpmnNote.height));
    }
    world = world.inflate(40);
    final scale = math.min(size.width / world.width, size.height / world.height);
    final offset = Offset((size.width - world.width * scale) / 2, (size.height - world.height * scale) / 2);
    return MinimapGeometry._(world, scale, offset, size);
  }

  Rect toMap(Rect r) => Rect.fromLTWH(offset.dx + (r.left - world.left) * scale, offset.dy + (r.top - world.top) * scale, r.width * scale, r.height * scale);

  Offset toCanvas(Offset p) => Offset(world.left + (p.dx - offset.dx) / scale, world.top + (p.dy - offset.dy) / scale);
}

/// The part of the canvas the viewer currently shows, in canvas coordinates, for a pan/zoom [matrix] and the on-screen [viewportSize].
Rect visibleCanvasRect(Matrix4 matrix, Size viewportSize) {
  final s = matrix.getMaxScaleOnAxis();
  final t = matrix.getTranslation();
  return Rect.fromLTWH(-t.x / s, -t.y / s, viewportSize.width / s, viewportSize.height / s);
}

/// A pan/zoom matrix with the same scale as [current] that puts canvas point [center] in the middle of the viewport.
Matrix4 matrixCenteredOn(Matrix4 current, Offset center, Size viewportSize) {
  final s = current.getMaxScaleOnAxis();
  return Matrix4(s, 0, 0, 0, 0, s, 0, 0, 0, 0, 1, 0, viewportSize.width / 2 - center.dx * s, viewportSize.height / 2 - center.dy * s, 0, 1);
}

/// Small overview of the diagram. Tap or drag to move the view there.
class BpmnMinimap extends StatelessWidget {
  const BpmnMinimap({
    super.key,
    required this.diagram,
    required this.controller,
    required this.viewportSize,
    required this.selected,
    this.size = const Size(200, 120),
  });

  final BpmnDiagram diagram;
  final TransformationController controller;
  final Size viewportSize;
  final Set<String> selected;
  final Size size;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        final view = visibleCanvasRect(controller.value, viewportSize);
        final geo = MinimapGeometry.of(diagram, view, size);
        void jump(Offset local) => controller.value = matrixCenteredOn(controller.value, geo.toCanvas(local), viewportSize);
        return Tooltip(
          message: 'Overview: click or drag to move the view',
          child: GestureDetector(
            onTapDown: (d) => jump(d.localPosition),
            onPanUpdate: (d) => jump(d.localPosition),
            child: Container(
              width: size.width,
              height: size.height,
              clipBehavior: Clip.antiAlias,
              decoration: BoxDecoration(
                color: AppColors.darkSurfaceAlt,
                borderRadius: BorderRadius.circular(AppRadius.md),
                border: Border.all(color: AppColors.darkBorder),
              ),
              child: CustomPaint(painter: _MinimapPainter(diagram: diagram, geo: geo, view: view, selected: selected)),
            ),
          ),
        );
      },
    );
  }
}

class _MinimapPainter extends CustomPainter {
  _MinimapPainter({required this.diagram, required this.geo, required this.view, required this.selected});

  final BpmnDiagram diagram;
  final MinimapGeometry geo;
  final Rect view;
  final Set<String> selected;

  @override
  void paint(Canvas canvas, Size size) {
    final lanes = diagram.sortedLanes;
    if (lanes.isNotEmpty) {
      final tops = laneLayout(lanes).tops;
      for (var i = 0; i < lanes.length; i++) {
        final r = geo.toMap(Rect.fromLTRB(geo.world.left, tops[i], geo.world.right, tops[i] + lanes[i].height));
        if (i.isOdd) canvas.drawRect(r, Paint()..color = Colors.white.withValues(alpha: 0.04));
        canvas.drawLine(r.topLeft, r.topRight, Paint()..color = AppColors.darkBorder);
      }
    }
    final flow = Paint()
      ..color = AppColors.textSecondaryDark.withValues(alpha: 0.5)
      ..strokeWidth = 1;
    for (final f in diagram.flowList) {
      final a = diagram.node(f.from), b = diagram.node(f.to);
      if (a == null || b == null) continue;
      final sa = bpmnNodeSize(a.type), sb = bpmnNodeSize(b.type);
      canvas.drawLine(geo.toMap(Rect.fromLTWH(a.x, a.y, sa.width, sa.height)).center, geo.toMap(Rect.fromLTWH(b.x, b.y, sb.width, sb.height)).center, flow);
    }
    for (final n in diagram.nodeList) {
      final s = bpmnNodeSize(n.type);
      final r = geo.toMap(Rect.fromLTWH(n.x, n.y, s.width, s.height));
      canvas.drawRRect(RRect.fromRectAndRadius(r, const Radius.circular(2)), Paint()..color = bpmnNodeColor(n.type).withValues(alpha: selected.contains(n.id) ? 1 : 0.7));
      if (selected.contains(n.id)) {
        canvas.drawRRect(
          RRect.fromRectAndRadius(r.inflate(1.5), const Radius.circular(3)),
          Paint()
            ..style = PaintingStyle.stroke
            ..color = AppColors.primaryBright
            ..strokeWidth = 1.2,
        );
      }
    }
    for (final n in diagram.noteList) {
      canvas.drawRect(geo.toMap(Rect.fromLTWH(n.x, n.y, BpmnNote.width, BpmnNote.height)), Paint()..color = const Color(0xFFFFF3B0).withValues(alpha: 0.8));
    }
    final v = geo.toMap(view);
    canvas.drawRect(v, Paint()..color = AppColors.primaryBright.withValues(alpha: 0.12));
    canvas.drawRect(
      v,
      Paint()
        ..style = PaintingStyle.stroke
        ..color = AppColors.primaryBright
        ..strokeWidth = 1.2,
    );
  }

  @override
  bool shouldRepaint(_MinimapPainter old) => old.diagram != diagram || old.view != view || old.selected != selected || old.geo.scale != geo.scale;
}
