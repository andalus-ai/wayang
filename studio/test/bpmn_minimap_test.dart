import 'dart:ui';

import 'package:flutter/material.dart' show Matrix4;
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/bpmn/minimap.dart';
import 'package:wayang_studio/models/bpmn.dart';

void main() {
  final diagram = BpmnDiagram.empty
      .addNode(const BpmnNode(id: 'a', type: BpmnElementType.userTask, x: 100, y: 100))
      .addNode(const BpmnNode(id: 'b', type: BpmnElementType.userTask, x: 2000, y: 400));

  test('the visible rectangle follows the pan and zoom matrix', () {
    final m = Matrix4(2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0, -400, -200, 0, 1); // scale 2, shifted
    final r = visibleCanvasRect(m, const Size(1000, 600));
    expect(r, const Rect.fromLTWH(200, 100, 500, 300));
  });

  test('centering on a point keeps the zoom and puts the point mid-screen', () {
    final m = Matrix4(2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
    final c = matrixCenteredOn(m, const Offset(1000, 500), const Size(800, 600));
    expect(c.getMaxScaleOnAxis(), 2);
    final r = visibleCanvasRect(c, const Size(800, 600));
    expect(r.center, const Offset(1000, 500));
  });

  test('every node and the viewport fit inside the map, and map and canvas coordinates round-trip', () {
    const view = Rect.fromLTWH(0, 0, 900, 600);
    final geo = MinimapGeometry.of(diagram, view, const Size(200, 120));
    const bounds = Rect.fromLTWH(0, 0, 200, 120);
    for (final n in diagram.nodeList) {
      final r = geo.toMap(Rect.fromLTWH(n.x, n.y, 176, 78));
      expect(bounds.contains(r.topLeft) && bounds.inflate(0.001).contains(r.bottomRight), isTrue, reason: n.id);
    }
    final v = geo.toMap(view);
    expect(bounds.inflate(0.001).contains(v.bottomRight), isTrue);
    final back = geo.toCanvas(geo.toMap(const Rect.fromLTWH(500, 300, 1, 1)).topLeft);
    expect((back - const Offset(500, 300)).distance < 0.001, isTrue);
  });

  test('a note far from the nodes is still inside the map', () {
    final withNote = diagram.putNote(const BpmnNote(id: 'n', x: 5000, y: 3000));
    final geo = MinimapGeometry.of(withNote, const Rect.fromLTWH(0, 0, 900, 600), const Size(200, 120));
    final r = geo.toMap(const Rect.fromLTWH(5000, 3000, BpmnNote.width, BpmnNote.height));
    expect(const Rect.fromLTWH(0, 0, 200, 120).inflate(0.001).contains(r.bottomRight), isTrue);
  });
}
