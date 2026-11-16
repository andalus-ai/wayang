import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/wayang_repository.dart';
import 'package:wayang_studio/features/bpmn/selection.dart';
import 'package:wayang_studio/models/bpmn.dart';
import 'package:wayang_studio/providers/bpmn_provider.dart';

BpmnController _controller() =>
    // The repository points nowhere: validation after an edit fails quietly, which these tests do not look at.
    BpmnController(WayangRepository(ApiClient(const ApiConfig(baseUrl: 'http://127.0.0.1:1'))), '');

void main() {
  contextMenuActionTests();
  test('nodesInRect finds nodes whose box touches the rectangle, whichever way it was dragged', () {
    var d = BpmnDiagram.empty;
    d = d.addNode(const BpmnNode(id: 'a', type: BpmnElementType.userTask, x: 100, y: 100));
    d = d.addNode(const BpmnNode(id: 'b', type: BpmnElementType.userTask, x: 600, y: 100));
    expect(nodesInRect(d, rectBetween(const Offset(90, 90), const Offset(150, 130))), {'a'});
    expect(nodesInRect(d, rectBetween(const Offset(700, 400), const Offset(0, 0))), {'a', 'b'});
    expect(nodesInRect(d, rectBetween(const Offset(300, 300), const Offset(400, 400))), isEmpty);
  });

  test('toggle builds a group; the inspector node is set only for exactly one', () {
    final c = _controller();
    final a = c.addNode(BpmnElementType.userTask, 10, 10);
    final b = c.addNode(BpmnElementType.userTask, 300, 10);
    expect(c.state.selectedNodeIds, {b});
    expect(c.state.selectedNodeId, b);
    c.toggleNode(a);
    expect(c.state.selectedNodeIds, {a, b});
    expect(c.state.selectedNodeId, isNull);
    c.toggleNode(b);
    expect(c.state.selectedNodeIds, {a});
    expect(c.state.selectedNodeId, a);
    c.toggleNode(a);
    expect(c.state.selectedNodeIds, isEmpty);
    expect(c.state.selectedNodeId, isNull);
  });

  test('select all, unknown ids are ignored, clear empties both', () {
    final c = _controller();
    final a = c.addNode(BpmnElementType.userTask, 10, 10);
    final b = c.addNode(BpmnElementType.endEvent, 300, 10);
    c.selectAllNodes();
    expect(c.state.selectedNodeIds, {a, b});
    c.selectNodes(['nope', a]);
    expect(c.state.selectedNodeIds, {a});
    c.clearSelection();
    expect(c.state.selectedNodeIds, isEmpty);
  });

  test('a group drag moves every member and is one undo step', () {
    final c = _controller();
    final a = c.addNode(BpmnElementType.userTask, 10, 10);
    final b = c.addNode(BpmnElementType.userTask, 300, 10);
    c.selectAllNodes();
    final before = c.state.diagram;
    final starts = {for (final id in [a, b]) id: Offset(before.node(id)!.x, before.node(id)!.y)};
    c.dragGroup(starts, 50, 20);
    c.dragGroup(starts, 60, 25);
    c.endDragGroup(before);
    expect(c.state.diagram.node(a)!.x, 70);
    expect(c.state.diagram.node(b)!.y, 35);
    c.undo();
    expect(c.state.diagram.node(a)!.x, 10);
    expect(c.state.diagram.node(b)!.x, 300);
  });

  test('removing a group deletes all its nodes in one undo step', () {
    final c = _controller();
    final a = c.addNode(BpmnElementType.userTask, 10, 10);
    final b = c.addNode(BpmnElementType.userTask, 300, 10);
    c.addNode(BpmnElementType.endEvent, 600, 10);
    c.selectNodes([a, b]);
    c.removeSelected();
    expect(c.state.diagram.nodeCount, 1);
    expect(c.state.selectedNodeIds, isEmpty);
    c.undo();
    expect(c.state.diagram.nodeCount, 3);
  });
}

void contextMenuActionTests() {
  test('duplicate copies the selection and the flows between its members, and selects the copies', () {
    final c = _controller();
    final a = c.addNode(BpmnElementType.userTask, 10, 10);
    final b = c.addNode(BpmnElementType.userTask, 300, 10);
    final outside = c.addNode(BpmnElementType.endEvent, 600, 10);
    c.startConnection(a, Offset.zero);
    c.completeConnection(b);
    c.startConnection(b, Offset.zero);
    c.completeConnection(outside);
    c.selectNodes([a, b]);
    c.duplicateSelected();
    expect(c.state.diagram.nodeCount, 5);
    expect(c.state.diagram.flowList.length, 3, reason: 'a->b copied, b->outside not');
    expect(c.state.selectedNodeIds.length, 2);
    expect(c.state.selectedNodeIds.intersection({a, b, outside}), isEmpty);
    final copy = c.state.diagram.node(c.state.selectedNodeIds.first)!;
    expect(copy.x == 50 || copy.x == 340, isTrue);
    c.undo();
    expect(c.state.diagram.nodeCount, 3);
  });

  test('align needs two nodes and is one undo step', () {
    final c = _controller();
    final a = c.addNode(BpmnElementType.userTask, 10, 80);
    final b = c.addNode(BpmnElementType.userTask, 300, 20);
    c.selectNodes([a]);
    c.alignSelected(horizontal: true);
    expect(c.state.canUndo, isTrue); // only the two adds so far
    c.selectNodes([a, b]);
    c.alignSelected(horizontal: false);
    expect(c.state.diagram.node(a)!.y, 20);
    expect(c.state.diagram.node(b)!.y, 20);
    c.alignSelected(horizontal: true);
    expect(c.state.diagram.node(b)!.x, 10);
    c.undo();
    expect(c.state.diagram.node(b)!.x, 300);
    expect(c.state.diagram.node(a)!.y, 20);
  });
}
