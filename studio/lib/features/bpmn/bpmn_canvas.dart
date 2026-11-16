import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart' hide DiagnosticLevel;
import 'package:flutter/services.dart' hide DiagnosticLevel;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../models/bpmn.dart';
import '../../providers/bpmn_provider.dart';
import 'context_menu.dart';
import 'guides.dart';
import 'minimap.dart';
import 'selection.dart';

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

  // Group drag: where every selected node was when the drag began, and the diagram before it (for one undo step).
  Map<String, Offset>? _groupStarts;
  BpmnDiagram? _groupBefore;

  // Box select: while [_boxMode] is on, dragging on empty canvas draws a rectangle instead of panning.
  bool _boxMode = false;

  // Alignment guides shown while a drag is close to lining up with another node.
  Guides _guides = Guides.none;
  Offset? _boxStart;
  Rect? _box;
  final FocusNode _focus = FocusNode(debugLabel: 'bpmn-canvas');

  @override
  void dispose() {
    _transformController.dispose();
    _focus.dispose();
    super.dispose();
  }

  Offset _canvasPositionOf(Offset globalPosition) {
    final box = _contentKey.currentContext?.findRenderObject() as RenderBox?;
    if (box == null) return Offset.zero;
    return box.globalToLocal(globalPosition);
  }

  ({List<double> tops, double totalHeight}) _laneLayout(List<BpmnLane> lanes) => laneLayout(lanes);

  String? _laneIdForY(List<BpmnLane> lanes, List<double> tops, double y) => laneIdForY(lanes, tops, y);

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
    _viewportSize = constraints.biggest;
    return CallbackShortcuts(
      bindings: {
        const SingleActivator(LogicalKeyboardKey.delete): controller.removeSelected,
        const SingleActivator(LogicalKeyboardKey.backspace): controller.removeSelected,
        const SingleActivator(LogicalKeyboardKey.escape): controller.clearSelection,
        const SingleActivator(LogicalKeyboardKey.keyA, control: true): controller.selectAllNodes,
        const SingleActivator(LogicalKeyboardKey.keyA, meta: true): controller.selectAllNodes,
        const SingleActivator(LogicalKeyboardKey.arrowLeft): () => _nudge(-1, 0, false),
        const SingleActivator(LogicalKeyboardKey.arrowRight): () => _nudge(1, 0, false),
        const SingleActivator(LogicalKeyboardKey.arrowUp): () => _nudge(0, -1, false),
        const SingleActivator(LogicalKeyboardKey.arrowDown): () => _nudge(0, 1, false),
        const SingleActivator(LogicalKeyboardKey.arrowLeft, shift: true): () => _nudge(-1, 0, true),
        const SingleActivator(LogicalKeyboardKey.arrowRight, shift: true): () => _nudge(1, 0, true),
        const SingleActivator(LogicalKeyboardKey.arrowUp, shift: true): () => _nudge(0, -1, true),
        const SingleActivator(LogicalKeyboardKey.arrowDown, shift: true): () => _nudge(0, 1, true),
        const SingleActivator(LogicalKeyboardKey.keyF, control: true): _find,
        const SingleActivator(LogicalKeyboardKey.keyF, meta: true): _find,
        const SingleActivator(LogicalKeyboardKey.keyC, control: true): _copy,
        const SingleActivator(LogicalKeyboardKey.keyC, meta: true): _copy,
        const SingleActivator(LogicalKeyboardKey.keyX, control: true): _cut,
        const SingleActivator(LogicalKeyboardKey.keyX, meta: true): _cut,
        const SingleActivator(LogicalKeyboardKey.keyV, control: true): _paste,
        const SingleActivator(LogicalKeyboardKey.keyV, meta: true): _paste,
        const SingleActivator(LogicalKeyboardKey.keyZ, control: true): controller.undo,
        const SingleActivator(LogicalKeyboardKey.keyZ, meta: true): controller.undo,
        const SingleActivator(LogicalKeyboardKey.keyY, control: true): controller.redo,
        const SingleActivator(LogicalKeyboardKey.keyZ, control: true, shift: true): controller.redo,
        const SingleActivator(LogicalKeyboardKey.keyZ, meta: true, shift: true): controller.redo,
      },
      child: Focus(
    focusNode: _focus,
    child: ClipRect(
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
                  panEnabled: !_boxMode,
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
                            onTapUp: (d) {
                              _focus.requestFocus();
                              final before = ref.read(bpmnControllerProvider).selectedFlowId;
                              _handleFlowTap(d.localPosition, diagram, controller);
                              // A tap on empty canvas (no flow hit) clears the selection.
                              if (ref.read(bpmnControllerProvider).selectedFlowId == before) controller.clearSelection();
                            },
                            onSecondaryTapUp: (d) => _emptyOrFlowMenu(d.globalPosition, d.localPosition),
                            onPanStart: _boxMode ? (d) => setState(() => _boxStart = d.localPosition) : null,
                            onPanUpdate: _boxMode
                                ? (d) => setState(() => _box = _boxStart == null ? null : rectBetween(_boxStart!, d.localPosition))
                                : null,
                            onPanEnd: _boxMode
                                ? (_) {
                                    final r = _box;
                                    if (r != null) controller.selectNodes(nodesInRect(diagram, r));
                                    setState(() {
                                      _box = null;
                                      _boxStart = null;
                                    });
                                  }
                                : null,
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
                        if (!_guides.isEmpty)
                          Positioned.fill(
                            child: IgnorePointer(child: CustomPaint(painter: GuidesPainter(_guides, AppColors.warning.withValues(alpha: 0.8)))),
                          ),
                        if (_box != null)
                          Positioned.fromRect(
                            rect: _box!,
                            child: IgnorePointer(
                              child: DecoratedBox(
                                decoration: BoxDecoration(
                                  color: AppColors.primaryBright.withValues(alpha: 0.10),
                                  border: Border.all(color: AppColors.primaryBright, width: 1),
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
                              selected: state.selectedNodeIds.contains(node.id),
                              diagnostics: diagnosticsByNode[node.id] ?? const [],
                              highlighted: state.highlight.contains(node.id),
                              onTap: () {
                                _focus.requestFocus();
                                if (HardwareKeyboard.instance.isShiftPressed || HardwareKeyboard.instance.isControlPressed || HardwareKeyboard.instance.isMetaPressed) {
                                  controller.toggleNode(node.id);
                                } else {
                                  controller.selectNode(node.id);
                                }
                              },
                              onContextMenu: (pos) => _nodeMenu(pos, node.id),
                              onPanStart: (details) {
                                _dragNodeId = node.id;
                                _dragStartCanvasPos = _canvasPositionOf(details.globalPosition);
                                _dragStartNodeX = node.x;
                                _dragStartNodeY = node.y;
                                final current = ref.read(bpmnControllerProvider);
                                if (current.selectedNodeIds.length > 1 && current.selectedNodeIds.contains(node.id)) {
                                  // Dragging a member of a group moves the whole group.
                                  _groupBefore = current.diagram;
                                  _groupStarts = {
                                    for (final id in current.selectedNodeIds)
                                      if (current.diagram.node(id) case final n?) id: Offset(n.x, n.y),
                                  };
                                } else {
                                  _groupStarts = null;
                                  _groupBefore = null;
                                  controller.selectNode(node.id);
                                }
                              },
                              onPanUpdate: (details) {
                                if (_dragNodeId != node.id) return;
                                final now = _canvasPositionOf(details.globalPosition);
                                final dx = now.dx - _dragStartCanvasPos.dx;
                                final dy = now.dy - _dragStartCanvasPos.dy;
                                final snap = ref.read(bpmnSnapProvider) ? bpmnGrid : 0.0;
                                // Snapping puts the dragged node on the grid; a group keeps its shape and moves by the same amount.
                                var sx = snapToGrid(_dragStartNodeX + dx, snap) - _dragStartNodeX;
                                var sy = snapToGrid(_dragStartNodeY + dy, snap) - _dragStartNodeY;
                                // Alignment guides win over the grid: a node that lines up with a neighbour snaps to it.
                                final cur = ref.read(bpmnControllerProvider).diagram;
                                final movers = _groupStarts ?? {node.id: Offset(_dragStartNodeX, _dragStartNodeY)};
                                final g = alignmentGuides(cur, movers.keys.toSet(), boxOfMoved(cur, movers, sx, sy));
                                sx += g.dx;
                                sy += g.dy;
                                if (!listEquals(_guides.vertical, g.vertical) || !listEquals(_guides.horizontal, g.horizontal)) setState(() => _guides = g);
                                if (_groupStarts != null) {
                                  controller.dragGroup(_groupStarts!, sx, sy);
                                  return;
                                }
                                controller.dragNode(node.id, _dragStartNodeX + sx, _dragStartNodeY + sy);
                              },
                              onPanEnd: (_) {
                                if (!_guides.isEmpty) setState(() => _guides = Guides.none);
                                if (_groupStarts != null && _dragNodeId == node.id) {
                                  // Each moved node joins the lane it now sits in, like a single drag; one undo step for the whole move.
                                  controller.endDragGroup(_groupBefore!, lanes: lanesForNodes(ref.read(bpmnControllerProvider).diagram, _groupStarts!.keys));
                                  _groupStarts = null;
                                  _groupBefore = null;
                                } else if (_dragNodeId == node.id) {
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
          if (diagram.nodeCount > 0)
            Positioned(
              left: 16,
              bottom: 16,
              child: BpmnMinimap(
                diagram: diagram,
                controller: _transformController,
                viewportSize: constraints.biggest,
                selected: state.selectedNodeIds,
              ),
            ),
          Positioned(
            right: 16,
            bottom: 16,
            child: _BpmnZoomControls(
              onZoomIn: () => _zoomBy(1.2),
              onZoomOut: () => _zoomBy(1 / 1.2),
              onReset: _resetView,
              onFit: () => _zoomToFit(diagram, constraints.biggest),
              boxMode: _boxMode,
              onFind: _find,
              snap: ref.watch(bpmnSnapProvider),
              onToggleSnap: () => ref.read(bpmnSnapProvider.notifier).state = !ref.read(bpmnSnapProvider),
              onToggleBox: () => setState(() {
                _boxMode = !_boxMode;
                _box = null;
                _boxStart = null;
              }),
            ),
          ),
        ],
      ),
    ),
    ),
    );
    });
  }

  BpmnFlow? _flowAt(Offset pos, BpmnDiagram diagram) {
    BpmnFlow? closest;
    var best = 10.0;
    for (final f in diagram.flowList) {
      final from = diagram.node(f.from), to = diagram.node(f.to);
      if (from == null || to == null) continue;
      final dist = _pointToSegmentDistance(pos, _portCenter(from, true), _portCenter(to, false));
      if (dist < best) {
        best = dist;
        closest = f;
      }
    }
    return closest;
  }

  void _nudge(int sx, int sy, bool fine) {
    final step = nudgeStep(fine: fine, grid: bpmnGrid);
    ref.read(bpmnControllerProvider.notifier).nudgeSelected(sx * step, sy * step);
  }

  Size _viewportSize = Size.zero;

  /// Ctrl/Cmd+F: pick a node by name, id or type; it is selected and brought to the middle of the view.
  Future<void> _find() async {
    final diagram = ref.read(bpmnControllerProvider).diagram;
    final picked = await showDialog<BpmnNode>(context: context, builder: (_) => _FindNodeDialog(diagram: diagram));
    if (picked == null || !mounted) return;
    ref.read(bpmnControllerProvider.notifier).selectNode(picked.id);
    final s = bpmnNodeSize(picked.type);
    _transformController.value = matrixCenteredOn(_transformController.value, Offset(picked.x + s.width / 2, picked.y + s.height / 2), _viewportSize);
    _focus.requestFocus();
  }

  void _copy() {
    final clip = ref.read(bpmnControllerProvider.notifier).copySelection();
    if (clip != null) ref.read(bpmnClipboardProvider.notifier).state = clip;
  }

  void _cut() {
    _copy();
    if (ref.read(bpmnClipboardProvider) != null) ref.read(bpmnControllerProvider.notifier).removeSelected();
  }

  void _paste({Offset? at}) {
    final clip = ref.read(bpmnClipboardProvider);
    if (clip != null) ref.read(bpmnControllerProvider.notifier).pasteClip(clip, at: at);
  }

  void _nodeMenu(Offset global, String nodeId) {
    _focus.requestFocus();
    final controller = ref.read(bpmnControllerProvider.notifier);
    if (!ref.read(bpmnControllerProvider).selectedNodeIds.contains(nodeId)) controller.selectNode(nodeId);
    final count = ref.read(bpmnControllerProvider).selectedNodeIds.length;
    showBpmnContextMenu(context, global, [
      BpmnMenuItem(count > 1 ? '$count nodes' : 'Node'),
      BpmnMenuItem('Duplicate', icon: Icons.copy_rounded, onTap: controller.duplicateSelected),
      BpmnMenuItem('Copy', icon: Icons.content_copy_rounded, onTap: _copy),
      BpmnMenuItem('Cut', icon: Icons.content_cut_rounded, onTap: _cut),
      if (count > 1) ...[
        BpmnMenuItem('Tidy layout', icon: Icons.auto_fix_high_rounded, onTap: controller.autoLayout),
        BpmnMenuItem('Align left', icon: Icons.align_horizontal_left_rounded, onTap: () => controller.alignSelected(horizontal: true)),
        BpmnMenuItem('Align top', icon: Icons.align_vertical_top_rounded, onTap: () => controller.alignSelected(horizontal: false)),
      ],
      BpmnMenuItem(count > 1 ? 'Delete $count nodes' : 'Delete', icon: Icons.delete_outline_rounded, destructive: true, divider: true, onTap: controller.removeSelected),
    ]);
  }

  void _emptyOrFlowMenu(Offset global, Offset local) {
    _focus.requestFocus();
    final controller = ref.read(bpmnControllerProvider.notifier);
    final state = ref.read(bpmnControllerProvider);
    final flow = _flowAt(local, state.diagram);
    if (flow != null) {
      controller.selectFlow(flow.id);
      showBpmnContextMenu(context, global, [
        const BpmnMenuItem('Flow'),
        BpmnMenuItem(flow.isDefault ? 'Not the default flow' : 'Make default flow',
            icon: Icons.alt_route_rounded, onTap: () => controller.setFlowDefault(flow.id, !flow.isDefault)),
        BpmnMenuItem('Delete flow', icon: Icons.delete_outline_rounded, destructive: true, divider: true, onTap: controller.removeSelected),
      ]);
      return;
    }
    final lanes = state.diagram.sortedLanes;
    final layout = _laneLayout(lanes);
    final laneId = _laneIdForY(lanes, layout.tops, local.dy);
    showBpmnContextMenu(context, global, [
      const BpmnMenuItem('Add here'),
      for (final t in BpmnElementType.values)
        BpmnMenuItem(t.label, icon: t.icon, onTap: () => controller.addNode(t, local.dx, local.dy, laneId: laneId)),
      if (ref.read(bpmnClipboardProvider) != null)
        BpmnMenuItem('Paste here', icon: Icons.content_paste_rounded, divider: true, onTap: () => _paste(at: local)),
      BpmnMenuItem('Select all', icon: Icons.select_all_rounded, divider: true, onTap: controller.selectAllNodes),
      BpmnMenuItem('Tidy layout (all)', icon: Icons.auto_fix_high_rounded, onTap: () {
        controller.clearSelection();
        controller.autoLayout();
      }),
    ]);
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
  const _BpmnZoomControls({required this.onZoomIn, required this.onZoomOut, required this.onReset, required this.onFit, required this.boxMode, required this.onToggleBox, required this.snap, required this.onToggleSnap, required this.onFind});
  final VoidCallback onZoomIn;
  final VoidCallback onZoomOut;
  final VoidCallback onReset;
  final VoidCallback onFit;
  final bool boxMode;
  final VoidCallback onToggleBox;
  final bool snap;
  final VoidCallback onToggleSnap;
  final VoidCallback onFind;

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
          Tooltip(message: 'Find a node (Ctrl+F)', child: InkWell(onTap: onFind, borderRadius: BorderRadius.circular(AppRadius.sm), child: const Padding(padding: EdgeInsets.all(7), child: Icon(Icons.search_rounded, size: 17, color: AppColors.textSecondaryDark)))),
          Tooltip(message: snap ? 'Snap to grid on' : 'Snap to grid', child: InkWell(onTap: onToggleSnap, borderRadius: BorderRadius.circular(AppRadius.sm), child: Padding(padding: const EdgeInsets.all(7), child: Icon(Icons.grid_4x4_rounded, size: 17, color: snap ? AppColors.primaryBright : AppColors.textSecondaryDark)))),
          Tooltip(message: boxMode ? 'Box select on (drag on empty canvas)' : 'Box select', child: InkWell(onTap: onToggleBox, borderRadius: BorderRadius.circular(AppRadius.sm), child: Padding(padding: const EdgeInsets.all(7), child: Icon(Icons.select_all_rounded, size: 17, color: boxMode ? AppColors.primaryBright : AppColors.textSecondaryDark)))),
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
    required this.onContextMenu,
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
  final void Function(Offset globalPosition) onContextMenu;
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
            onSecondaryTapUp: (d) => onContextMenu(d.globalPosition),
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


class _FindNodeDialog extends StatefulWidget {
  const _FindNodeDialog({required this.diagram});
  final BpmnDiagram diagram;

  @override
  State<_FindNodeDialog> createState() => _FindNodeDialogState();
}

class _FindNodeDialogState extends State<_FindNodeDialog> {
  String _q = '';

  @override
  Widget build(BuildContext context) {
    final hits = findNodes(widget.diagram, _q);
    return AlertDialog(
      title: const Text('Find a node'),
      content: SizedBox(
        width: 420,
        height: 340,
        child: Column(children: [
          TextField(
            autofocus: true,
            decoration: const InputDecoration(prefixIcon: Icon(Icons.search_rounded), hintText: 'Name, id or type'),
            onChanged: (v) => setState(() => _q = v),
            onSubmitted: (_) {
              if (hits.isNotEmpty) Navigator.pop(context, hits.first);
            },
          ),
          const SizedBox(height: 8),
          Expanded(
            child: hits.isEmpty
                ? const Center(child: Text('No node matches.'))
                : ListView(children: [
                    for (final n in hits)
                      ListTile(
                        dense: true,
                        title: Text(n.label.isEmpty ? n.id : n.label),
                        subtitle: Text('${n.type.label} · ${n.id}', style: const TextStyle(fontSize: 11)),
                        onTap: () => Navigator.pop(context, n),
                      ),
                  ]),
          ),
        ]),
      ),
      actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Close'))],
    );
  }
}
