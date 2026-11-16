import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/wayang_repository.dart';
import 'package:wayang_studio/features/bpmn/auto_layout.dart';
import 'package:wayang_studio/models/bpmn.dart';
import 'package:wayang_studio/providers/bpmn_provider.dart';

BpmnDiagram _chain() {
  var d = BpmnDiagram.empty;
  for (final id in ['s', 'a', 'b', 'e']) {
    d = d.addNode(BpmnNode(id: id, type: BpmnElementType.userTask, x: 900 - d.nodeCount * 10.0, y: 500));
  }
  d = d.connect(const BpmnFlow(id: 'f1', from: 's', to: 'a'));
  d = d.connect(const BpmnFlow(id: 'f2', from: 'a', to: 'b'));
  d = d.connect(const BpmnFlow(id: 'f3', from: 'b', to: 'e'));
  return d;
}

void main() {
  test('a chain lays out left to right, one column each', () {
    final p = layeredLayout(_chain());
    expect(p['s'], const Offset(layoutMargin, layoutMargin));
    expect(p['a']!.dx, layoutMargin + layoutColumn);
    expect(p['e']!.dx, layoutMargin + 3 * layoutColumn);
    expect(p['e']!.dy, layoutMargin);
  });

  test('a split stacks the branches in one column', () {
    var d = _chain().addNode(const BpmnNode(id: 'c', type: BpmnElementType.userTask, x: 0, y: 0));
    d = d.connect(const BpmnFlow(id: 'f4', from: 'a', to: 'c'));
    final p = layeredLayout(d);
    expect(p['b']!.dx, p['c']!.dx);
    expect(p['b']!.dy == p['c']!.dy, isFalse);
  });

  test('a cycle terminates', () {
    final d = _chain().connect(const BpmnFlow(id: 'back', from: 'e', to: 's'));
    final p = layeredLayout(d);
    expect(p.length, 4);
  });

  test('a subset keeps its top-left corner and ignores other nodes', () {
    final d = _chain();
    final p = layeredLayout(d, only: {'a', 'b'});
    expect(p.keys.toSet(), {'a', 'b'});
    final a = d.node('a')!, b = d.node('b')!;
    final minX = a.x < b.x ? a.x : b.x;
    expect(p['a']!.dx, minX);
    expect(p['b']!.dx, minX + layoutColumn);
    expect(p['a']!.dy, 500);
  });

  test('with lanes a node stays in its own band', () {
    var d = BpmnDiagram.empty.copyWith(lanes: const [
      BpmnLane(id: 'l1', name: 'A', order: 0, height: 200),
      BpmnLane(id: 'l2', name: 'B', order: 1, height: 200),
    ]);
    d = d.addNode(const BpmnNode(id: 'x', type: BpmnElementType.userTask, x: 0, y: 0, laneId: 'l1'));
    d = d.addNode(const BpmnNode(id: 'y', type: BpmnElementType.userTask, x: 0, y: 0, laneId: 'l2'));
    final p = layeredLayout(d);
    expect(p['x']!.dy, 40);
    expect(p['y']!.dy, 240);
  });

  test('the controller tidies everything in one undo step, or just the selection', () {
    final c = BpmnController(WayangRepository(ApiClient(const ApiConfig(baseUrl: 'http://127.0.0.1:1'))), '');
    final a = c.addNode(BpmnElementType.userTask, 400, 300);
    final b = c.addNode(BpmnElementType.userTask, 100, 50);
    c.startConnection(a, Offset.zero);
    c.completeConnection(b);
    c.clearSelection();
    c.autoLayout();
    expect(c.state.diagram.node(b)!.x - c.state.diagram.node(a)!.x, layoutColumn);
    c.undo();
    expect(c.state.diagram.node(a)!.x, 400);
    expect(c.state.diagram.node(b)!.x, 100);
  });
}
