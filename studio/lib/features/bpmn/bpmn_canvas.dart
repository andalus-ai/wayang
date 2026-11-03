import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart' hide DiagnosticLevel;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../models/bpmn.dart';
import '../../providers/bpmn_provider.dart';

const double kLaneHeaderWidth = 120;
const double kBpmnCanvasWidth = 3200;
const double kDefaultLaneHeight = 220;

Size bpmnNodeSize(BpmnElementType type) {
  if (type.isEvent) return const Size(52, 52);
  if (type.isGateway) return const Size(52, 52);
  return const Size(176, 78);
}

Color bpmnNodeColor(BpmnElementType type) {
  if (type == BpmnElementType.startEvent) return AppColors.success;
  if (type == BpmnElementType.endEvent) return AppColors.danger;
  if (type.isEvent) return AppColors.warning;
  if (type.isGateway) return AppColors.forKindIndex(2);
  if (type == BpmnElementType.businessRuleTask) return AppColors.forKindIndex(4);
  if (type == BpmnElementType.userTask) return AppColors.info;
  if (type == BpmnElementType.serviceTask) return AppColors.forKindIndex(0);
  if (type == BpmnElementType.agentTask) return AppColors.forKindIndex(6);
  if (type == BpmnElementType.eipTask) return AppColors.forKindIndex(5);
  return AppColors.primaryBright;
}

class BpmnCanvas extends ConsumerStatefulWidget {
  const BpmnCanvas({super.key});

  @override
  ConsumerState<BpmnCanvas> createState() => _BpmnCanvasState();
}

class _BpmnCanvasState extends ConsumerState<BpmnCanvas> {
  final TransformationController _transformController = TransformationController();
  final GlobalKey _contentKey = GlobalKey();

  String? _dragNodeId;
  Offset _dragStartCanvasPos = Offset.zero;
  double _dragStartNodeX = 0;
  double _dragStartNodeY = 0;

  @override
  void dispose() {
    _transformController.dispose();
    super.dispose();
  }

  Offset _canvasPositionOf(Offset globalPosition) {
    final box = _contentKey.currentContext?.findRenderObject() as RenderBox?;
    if (box == null) return Offset.zero;
    return box.globalToLocal(globalPosition);
  }

  /// Cumulative top-offset for each lane, in sorted order, plus a total
  /// canvas height. Diagrams with no lanes render as one implicit band.
  ({List<double> tops, double totalHeight}) _laneLayout(List<BpmnLane> lanes) {
    if (lanes.isEmpty) return (tops: const [0], totalHeight: 600);
    final tops = <double>[];
    double y = 0;
    for (final lane in lanes) {
      tops.add(y);
      y += lane.height;
    }
    return (tops: tops, totalHeight: y);
  }

  String? _laneIdForY(List<BpmnLane> lanes, List<double> tops, double y) {
    if (lanes.isEmpty) return null;
    for (var i = 0; i < lanes.length; i++) {
      final top = tops[i];
      final bottom = i + 1 < tops.length ? tops[i + 1] : double.infinity;
      if (y >= top && y < bottom) return lanes[i].id;
    }
    return lanes.last.id;
  }

  void _zoomBy(double factor) {
    final matrix = _transformController.value.clone();
    final newScale = (matrix.getMaxScaleOnAxis() * factor).clamp(0.25, 2.5);
    final scaleFactor = newScale / matrix.getMaxScaleOnAxis();
    _transformController.value = matrix..scale(scaleFactor);
  }

  void _resetView() {
    _transformController.value = Matrix4.identity();
  }

  void _zoomToFit(BpmnDiagram diagram, Size viewportSize) {
    if (diagram.nodeCount == 0) {
      _resetView();
      return;
    }
    double minX = double.infinity, minY = double.infinity, maxX = -double.infinity, maxY = -double.infinity;
    for (final n in diagram.nodeList) {
      final size = bpmnNodeSize(n.type);
      minX = math.min(minX, n.x);
      minY = math.min(minY, n.y);
      maxX = math.max(maxX, n.x + size.width);
      maxY = math.max(maxY, n.y + size.height);
    }
    const margin = 80.0;
    final boundsW = (maxX - minX) + margin * 2 + kLaneHeaderWidth;
    final boundsH = (maxY - minY) + margin * 2;
    final scale = math.min(viewportSize.width / boundsW, viewportSize.height / boundsH).clamp(0.25, 2.5);
    final tx = -(minX - margin - kLaneHeaderWidth) * scale;
    final ty = -(minY - margin) * scale;
    _transformController.value = Matrix4.identity()
      ..translate(tx, ty)
      ..scale(scale);
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(bpmnControllerProvider);
    final controller = ref.read(bpmnControllerProvider.notifier);
    final diagram = state.diagram;
    final lanes = diagram.sortedLanes;
    final layout = _laneLayout(lanes);

    final diagnosticsByNode = <String, List<BpmnDiagnostic>>{};
    for (final d in state.diagnostics) {
      if (d.nodeId != null) diagnosticsByNode.putIfAbsent(d.nodeId!, () => []).add(d);
    }

    return LayoutBuilder(builder: (context, constraints) {
    return ClipRect(
      child: Stack(
        children: [
          Positioned.fill(
            child: DragTarget<BpmnElementType>(
              onWillAcceptWithDetails: (_) => true,
              onAcceptWithDetails: (details) {
                final pos = _canvasPositionOf(details.offset);
                final laneId = _laneIdForY(lanes, layout.tops, pos.dy);
                controller.addNode(details.data, pos.dx, pos.dy, laneId: laneId);
              },
              builder: (context, candidateData, rejectedData) => ColoredBox(
                color: AppColors.darkBg,
                child: InteractiveViewer(
                  transformationController: _transformController,
                  constrained: false,
                  minScale: 0.25,
                  maxScale: 2.5,
                  boundaryMargin: const EdgeInsets.all(1200),
                  child: SizedBox(
                    key: _contentKey,
                    width: kBpmnCanvasWidth,
                    height: math.max(layout.totalHeight, 500),
                    child: Stack(
                      clipBehavior: Clip.none,
                      children: [
                        Positioned.fill(
                          child: CustomPaint(painter: _LanesPainter(lanes: lanes, tops: layout.tops, width: kBpmnCanvasWidth)),
                        ),
                        Positioned.fill(
                          child: GestureDetector(
                            behavior: HitTestBehavior.translucent,
                            onTapUp: (d) => _handleFlowTap(d.localPosition, diagram, controller),
                            child: CustomPaint(
                              painter: _FlowsPainter(
                                diagram: diagram,
                                selectedFlowId: state.selectedFlowId,
                                connectingFrom: state.connectingFromNodeId,
                                connectingCursor: state.connectingCursor,
                              ),
                            ),
                          ),
                        ),
                        for (final node in diagram.nodeList)
                          Positioned(
                            left: node.x,
                            top: node.y,
                            child: _BpmnNodeWidget(
                              node: node,
                              selected: state.selectedNodeId == node.id,
                              diagnostics: diagnosticsByNode[node.id] ?? const [],
                              highlighted: state.highlight.contains(node.id),
                              onTap: () => controller.selectNode(node.id),
                              onPanStart: (details) {
                                _dragNodeId = node.id;
                                _dragStartCanvasPos = _canvasPositionOf(details.globalPosition);
                                _dragStartNodeX = node.x;
                                _dragStartNodeY = node.y;
                                controller.selectNode(node.id);
                              },
                              onPanUpdate: (details) {
                                if (_dragNodeId != node.id) return;
                                final now = _canvasPositionOf(details.globalPosition);
                                final dx = now.dx - _dragStartCanvasPos.dx;
                                final dy = now.dy - _dragStartCanvasPos.dy;
                                controller.dragNode(node.id, _dragStartNodeX + dx, _dragStartNodeY + dy);
                              },
                              onPanEnd: (_) {
                                if (_dragNodeId == node.id) {
                                  final n = diagram.node(node.id);
                                  final size = bpmnNodeSize(node.type);
                                  final centerY = (n?.y ?? node.y) + size.height / 2;
                                  final laneId = _laneIdForY(lanes, layout.tops, centerY);
                                  controller.endDragNode(node.id, laneId: laneId, clearLane: laneId == null);
                                }
                                _dragNodeId = null;
                              },
                              onPortPanStart: (details) =>
                                  controller.startConnection(node.id, _canvasPositionOf(details.globalPosition)),
                              onPortPanUpdate: (details) =>
                                  controller.updateConnectionCursor(_canvasPositionOf(details.globalPosition)),
                              onPortPanEnd: (details) {
                                final drop = _canvasPositionOf(details.globalPosition);
                                final target = _hitTestNode(diagram, drop, excludeId: node.id);
                                if (target != null) {
                                  controller.completeConnection(target.id);
                                } else {
                                  controller.cancelConnection();
                                }
                              },
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
          if (diagram.nodeCount == 0 && !state.loading)
            const Center(
              child: _HintBanner(text: 'Drag an element from the palette to start modeling this process'),
            ),
          Positioned(
            right: 16,
            bottom: 16,
            child: _BpmnZoomControls(
              onZoomIn: () => _zoomBy(1.2),
              onZoomOut: () => _zoomBy(1 / 1.2),
              onReset: _resetView,
              onFit: () => _zoomToFit(diagram, constraints.biggest),
            ),
          ),
        ],
      ),
    );
    });
  }

  void _handleFlowTap(Offset pos, BpmnDiagram diagram, BpmnController controller) {
    BpmnFlow? closest;
    double closestDist = 10;
    for (final f in diagram.flowList) {
      final from = diagram.node(f.from);
      final to = diagram.node(f.to);
      if (from == null || to == null) continue;
      final p0 = _portCenter(from, true);
      final p1 = _portCenter(to, false);
      final dist = _pointToSegmentDistance(pos, p0, p1);
      if (dist < closestDist) {
        closestDist = dist;
        closest = f;
      }
    }
    if (closest != null) controller.selectFlow(closest.id);
  }

  double _pointToSegmentDistance(Offset p, Offset a, Offset b) {
    final ab = b - a;
    final ap = p - a;
    final lenSq = ab.dx * ab.dx + ab.dy * ab.dy;
    final t = lenSq == 0 ? 0.0 : ((ap.dx * ab.dx + ap.dy * ab.dy) / lenSq).clamp(0.0, 1.0);
    final proj = Offset(a.dx + ab.dx * t, a.dy + ab.dy * t);
    return (p - proj).distance;
  }

  Offset _portCenter(BpmnNode n, bool output) {
    final size = bpmnNodeSize(n.type);
    return output ? Offset(n.x + size.width, n.y + size.height / 2) : Offset(n.x, n.y + size.height / 2);
  }

  BpmnNode? _hitTestNode(BpmnDiagram diagram, Offset point, {String? excludeId}) {
    for (final n in diagram.nodeList) {
      if (n.id == excludeId) continue;
      final size = bpmnNodeSize(n.type);
      if (Rect.fromLTWH(n.x, n.y, size.width, size.height).contains(point)) return n;
    }
    return null;
  }
}

class _BpmnZoomControls extends StatelessWidget {
  const _BpmnZoomControls({required this.onZoomIn, required this.onZoomOut, required this.onReset, required this.onFit});
  final VoidCallback onZoomIn;
  final VoidCallback onZoomOut;
  final VoidCallback onReset;
  final VoidCallback onFit;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: AppColors.darkSurfaceAlt,
        borderRadius: BorderRadius.circular(AppRadius.md),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Tooltip(message: 'Zoom out', child: InkWell(onTap: onZoomOut, borderRadius: BorderRadius.circular(AppRadius.sm), child: const Padding(padding: EdgeInsets.all(7), child: Icon(Icons.remove_rounded, size: 17, color: AppColors.textSecondaryDark)))),
          Tooltip(message: 'Reset view', child: InkWell(onTap: onReset, borderRadius: BorderRadius.circular(AppRadius.sm), child: const Padding(padding: EdgeInsets.all(7), child: Icon(Icons.center_focus_strong_rounded, size: 17, color: AppColors.textSecondaryDark)))),
          Tooltip(message: 'Zoom to fit', child: InkWell(onTap: onFit, borderRadius: BorderRadius.circular(AppRadius.sm), child: const Padding(padding: EdgeInsets.all(7), child: Icon(Icons.fit_screen_rounded, size: 17, color: AppColors.textSecondaryDark)))),
          Tooltip(message: 'Zoom in', child: InkWell(onTap: onZoomIn, borderRadius: BorderRadius.circular(AppRadius.sm), child: const Padding(padding: EdgeInsets.all(7), child: Icon(Icons.add_rounded, size: 17, color: AppColors.textSecondaryDark)))),
        ],
      ),
    );
  }
}

class _HintBanner extends StatelessWidget {
  const _HintBanner({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
        decoration: BoxDecoration(
          color: AppColors.darkSurfaceAlt.withValues(alpha: 0.9),
          borderRadius: BorderRadius.circular(AppRadius.lg),
          border: Border.all(color: AppColors.darkBorder),
        ),
        child: Text(text, style: Theme.of(context).textTheme.bodySmall),
      ),
    );
  }
}

class _LanesPainter extends CustomPainter {
  _LanesPainter({required this.lanes, required this.tops, required this.width});
  final List<BpmnLane> lanes;
  final List<double> tops;
  final double width;

  @override
  void paint(Canvas canvas, Size size) {
    if (lanes.isEmpty) return;
    for (var i = 0; i < lanes.length; i++) {
      final top = tops[i];
      final height = lanes[i].height;
      final bandColor = i.isEven ? AppColors.darkSurface : AppColors.darkBg;
      canvas.drawRect(Rect.fromLTWH(0, top, width, height), Paint()..color = bandColor);
      final linePaint = Paint()
        ..color = AppColors.darkBorder
        ..strokeWidth = 1;
      canvas.drawLine(Offset(0, top), Offset(width, top), linePaint);
      canvas.drawLine(Offset(kLaneHeaderWidth, top), Offset(kLaneHeaderWidth, top + height), linePaint);

      final tp = TextPainter(
        text: TextSpan(
          text: lanes[i].name,
          style: const TextStyle(color: AppColors.textSecondaryDark, fontSize: 12, fontWeight: FontWeight.w700),
        ),
        textDirection: ui.TextDirection.ltr,
      )..layout(maxWidth: kLaneHeaderWidth - 16);
      tp.paint(canvas, Offset(12, top + height / 2 - tp.height / 2));
    }
  }

  @override
  bool shouldRepaint(covariant _LanesPainter oldDelegate) => oldDelegate.lanes != lanes || oldDelegate.tops != tops;
}

class _FlowsPainter extends CustomPainter {
  _FlowsPainter({required this.diagram, this.selectedFlowId, this.connectingFrom, this.connectingCursor});
  final BpmnDiagram diagram;
  final String? selectedFlowId;
  final String? connectingFrom;
  final Offset? connectingCursor;

  Offset _portCenter(BpmnNode n, bool output) {
    final size = bpmnNodeSize(n.type);
    return output ? Offset(n.x + size.width, n.y + size.height / 2) : Offset(n.x, n.y + size.height / 2);
  }

  @override
  void paint(Canvas canvas, Size size) {
    for (final f in diagram.flowList) {
      final from = diagram.node(f.from);
      final to = diagram.node(f.to);
      if (from == null || to == null) continue;
      final selected = f.id == selectedFlowId;
      final color = selected ? AppColors.primaryBright : AppColors.darkBorderBright;
      _drawFlow(
        canvas,
        _portCenter(from, true),
        _portCenter(to, false),
        color,
        selected ? 2.6 : 1.8,
        label: f.label.isNotEmpty ? f.label : f.condition,
        isDefault: f.isDefault,
      );
    }
    if (connectingFrom != null && connectingCursor != null) {
      final from = diagram.node(connectingFrom!);
      if (from != null) {
        _drawFlow(canvas, _portCenter(from, true), connectingCursor!, AppColors.secondary, 2.0, dashed: true);
      }
    }
  }

  /// Routes a flow as horizontal → vertical → horizontal (a simple
  /// orthogonal BPMN-style connector), then draws an arrowhead oriented to
  /// match the direction of the final approach segment into [p1].
  void _drawFlow(Canvas canvas, Offset p0, Offset p1, Color color, double width,
      {String? label, bool dashed = false, bool isDefault = false}) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = width
      ..strokeCap = StrokeCap.round;

    final midX = (p0.dx + p1.dx) / 2;
    final path = Path()
      ..moveTo(p0.dx, p0.dy)
      ..lineTo(midX, p0.dy)
      ..lineTo(midX, p1.dy)
      ..lineTo(p1.dx, p1.dy);

    if (dashed) {
      _drawDashedPath(canvas, path, paint);
    } else {
      canvas.drawPath(path, paint);
    }

    // The last leg runs from (midX, p1.dy) to (p1.dx, p1.dy) — horizontal,
    // unless that leg has ~zero length (source/target already aligned on
    // x), in which case the real approach direction is vertical.
    final double approachAngle;
    if ((p1.dx - midX).abs() < 0.5) {
      approachAngle = p1.dy >= p0.dy ? math.pi / 2 : -math.pi / 2;
    } else {
      approachAngle = p1.dx >= midX ? 0.0 : math.pi;
    }

    final arrowPaint = Paint()..color = color;
    final arrowPath = Path()
      ..moveTo(p1.dx, p1.dy)
      ..lineTo(p1.dx - 9 * math.cos(approachAngle - 0.4), p1.dy - 9 * math.sin(approachAngle - 0.4))
      ..lineTo(p1.dx - 9 * math.cos(approachAngle + 0.4), p1.dy - 9 * math.sin(approachAngle + 0.4))
      ..close();
    canvas.drawPath(arrowPath, arrowPaint);

    if (isDefault) {
      // Small diagonal tick near the source — BPMN's "default flow" marker.
      final tickCenter = Offset(p0.dx + 14, p0.dy);
      canvas.drawLine(
        tickCenter.translate(-4, 4),
        tickCenter.translate(4, -4),
        Paint()
          ..color = color
          ..strokeWidth = 2,
      );
    }

    if (label != null && label.trim().isNotEmpty) {
      final tp = TextPainter(
        text: TextSpan(text: label, style: TextStyle(color: color, fontSize: 10.5, fontWeight: FontWeight.w700)),
        textDirection: ui.TextDirection.ltr,
      )..layout();
      final mid = Offset(midX, (p0.dy + p1.dy) / 2 - 10);
      final bg = Rect.fromCenter(center: mid, width: tp.width + 10, height: tp.height + 4);
      canvas.drawRRect(RRect.fromRectAndRadius(bg, const Radius.circular(3)), Paint()..color = AppColors.darkSurfaceAlt);
      tp.paint(canvas, Offset(mid.dx - tp.width / 2, mid.dy - tp.height / 2));
    }
  }

  void _drawDashedPath(Canvas canvas, Path path, Paint paint) {
    const dashWidth = 6.0;
    const dashSpace = 5.0;
    for (final metric in path.computeMetrics()) {
      double distance = 0;
      while (distance < metric.length) {
        final next = math.min(distance + dashWidth, metric.length);
        canvas.drawPath(metric.extractPath(distance, next), paint);
        distance = next + dashSpace;
      }
    }
  }

  @override
  bool shouldRepaint(covariant _FlowsPainter oldDelegate) {
    return oldDelegate.diagram != diagram ||
        oldDelegate.selectedFlowId != selectedFlowId ||
        oldDelegate.connectingFrom != connectingFrom ||
        oldDelegate.connectingCursor != connectingCursor;
  }
}

class _BpmnNodeWidget extends StatelessWidget {
  const _BpmnNodeWidget({
    required this.node,
    required this.selected,
    required this.diagnostics,
    required this.highlighted,
    required this.onTap,
    required this.onPanStart,
    required this.onPanUpdate,
    required this.onPanEnd,
    required this.onPortPanStart,
    required this.onPortPanUpdate,
    required this.onPortPanEnd,
  });

  final BpmnNode node;
  final bool selected;
  final List<BpmnDiagnostic> diagnostics;
  final bool highlighted;
  final VoidCallback onTap;
  final GestureDragStartCallback onPanStart;
  final GestureDragUpdateCallback onPanUpdate;
  final GestureDragEndCallback onPanEnd;
  final GestureDragStartCallback onPortPanStart;
  final GestureDragUpdateCallback onPortPanUpdate;
  final GestureDragEndCallback onPortPanEnd;

  @override
  Widget build(BuildContext context) {
    final size = bpmnNodeSize(node.type);
    final color = bpmnNodeColor(node.type);
    final hasError = diagnostics.any((d) => d.severity == DiagnosticLevel.error);
    final hasWarning = diagnostics.any((d) => d.severity == DiagnosticLevel.warning);

    Widget shape;
    if (node.type.isEvent) {
      shape = _EventShape(type: node.type, color: color, selected: selected);
    } else if (node.type.isGateway) {
      shape = _GatewayShape(type: node.type, color: color, selected: selected);
    } else {
      shape = _TaskShape(node: node, color: color, selected: selected);
    }

    return SizedBox(
      width: size.width + 22,
      height: size.height,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          SizedBox(width: 11, child: Center(child: _BpmnPortDot(color: color))),
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: onTap,
            onPanStart: onPanStart,
            onPanUpdate: onPanUpdate,
            onPanEnd: onPanEnd,
            child: SizedBox(
              width: size.width,
              height: size.height,
              child: Stack(
                clipBehavior: Clip.none,
                children: [
                  shape,
                  if (highlighted)
                    Positioned.fill(
                      child: IgnorePointer(
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(node.type.isTask ? 10 : 26),
                            border: Border.all(color: AppColors.success, width: 2.6),
                          ),
                        ),
                      ),
                    ),
                  if (hasError || hasWarning)
                    Positioned(
                      top: -4,
                      right: -4,
                      child: Icon(
                        hasError ? Icons.error_rounded : Icons.warning_rounded,
                        size: 15,
                        color: hasError ? AppColors.danger : AppColors.warning,
                      ),
                    ),
                  if (node.type.isTask)
                    Positioned.fill(
                      child: Center(
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          child: Text(
                            node.label,
                            textAlign: TextAlign.center,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w600),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
          SizedBox(
            width: 11,
            child: Center(
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onPanStart: onPortPanStart,
                onPanUpdate: onPortPanUpdate,
                onPanEnd: onPortPanEnd,
                child: _BpmnPortDot(color: color, isOutput: true),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _EventShape extends StatelessWidget {
  const _EventShape({required this.type, required this.color, required this.selected});
  final BpmnElementType type;
  final Color color;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    final isEnd = type == BpmnElementType.endEvent;
    return Container(
      decoration: BoxDecoration(
        color: AppColors.darkSurfaceAlt,
        shape: BoxShape.circle,
        border: Border.all(color: selected ? AppColors.primaryBright : color, width: isEnd ? 3.2 : 2),
        boxShadow: selected ? [BoxShadow(color: AppColors.primaryBright.withValues(alpha: 0.3), blurRadius: 12)] : null,
      ),
      child: Center(child: Icon(type.icon, size: 18, color: color)),
    );
  }
}

class _GatewayShape extends StatelessWidget {
  const _GatewayShape({required this.type, required this.color, required this.selected});
  final BpmnElementType type;
  final Color color;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    return Stack(
      alignment: Alignment.center,
      children: [
        CustomPaint(
          size: const Size(52, 52),
          painter: _DiamondPainter(color: selected ? AppColors.primaryBright : color, selected: selected),
        ),
        Icon(type.icon, size: 16, color: color),
      ],
    );
  }
}

class _DiamondPainter extends CustomPainter {
  _DiamondPainter({required this.color, required this.selected});
  final Color color;
  final bool selected;

  @override
  void paint(Canvas canvas, Size size) {
    final path = Path()
      ..moveTo(size.width / 2, 0)
      ..lineTo(size.width, size.height / 2)
      ..lineTo(size.width / 2, size.height)
      ..lineTo(0, size.height / 2)
      ..close();
    canvas.drawPath(path, Paint()..color = AppColors.darkSurfaceAlt);
    canvas.drawPath(
      path,
      Paint()
        ..color = color
        ..style = PaintingStyle.stroke
        ..strokeWidth = selected ? 2.4 : 1.8,
    );
  }

  @override
  bool shouldRepaint(covariant _DiamondPainter oldDelegate) => oldDelegate.color != color || oldDelegate.selected != selected;
}

class _TaskShape extends StatelessWidget {
  const _TaskShape({required this.node, required this.color, required this.selected});
  final BpmnNode node;
  final Color color;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.darkSurfaceAlt,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: selected ? AppColors.primaryBright : AppColors.darkBorder, width: selected ? 1.8 : 1),
        boxShadow: selected ? [BoxShadow(color: AppColors.primaryBright.withValues(alpha: 0.25), blurRadius: 12)] : null,
      ),
      child: Align(
        alignment: Alignment.topLeft,
        child: Container(
          margin: const EdgeInsets.all(6),
          padding: const EdgeInsets.all(3),
          decoration: BoxDecoration(color: color.withValues(alpha: 0.16), borderRadius: BorderRadius.circular(5)),
          child: Icon(node.type.icon, size: 12, color: color),
        ),
      ),
    );
  }
}

class _BpmnPortDot extends StatelessWidget {
  const _BpmnPortDot({required this.color, this.isOutput = false});
  final Color color;
  final bool isOutput;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 9,
      height: 9,
      decoration: BoxDecoration(
        color: isOutput ? color : AppColors.darkBg,
        shape: BoxShape.circle,
        border: Border.all(color: color, width: 1.4),
      ),
    );
  }
}
