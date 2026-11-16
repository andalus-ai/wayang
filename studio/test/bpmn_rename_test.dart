import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/wayang_repository.dart';
import 'package:wayang_studio/models/bpmn.dart';
import 'package:wayang_studio/providers/bpmn_provider.dart';

BpmnController _controller() => BpmnController(WayangRepository(ApiClient(const ApiConfig(baseUrl: 'http://127.0.0.1:1'))), '');

void main() {
  test('a flow label is set in one undo step and an unchanged label records nothing', () {
    final c = _controller();
    final a = c.addNode(BpmnElementType.userTask, 10, 10);
    final b = c.addNode(BpmnElementType.userTask, 300, 10);
    c.startConnection(a, Offset.zero);
    c.completeConnection(b);
    final flow = c.state.diagram.flowList.single;
    c.setFlowLabel(flow.id, 'approved');
    expect(c.state.diagram.flows[flow.id]!.label, 'approved');
    c.setFlowLabel(flow.id, 'approved'); // no-op: must not add an undo step
    c.undo();
    expect(c.state.diagram.flows[flow.id]!.label, '');
    c.setFlowLabel('missing', 'x'); // unknown id is ignored
  });

  test('renaming a node to its current name records nothing; a real rename undoes', () {
    final c = _controller();
    final a = c.addNode(BpmnElementType.userTask, 10, 10);
    c.updateNodeLabel(a, 'Review');
    c.updateNodeLabel(a, 'Review');
    expect(c.state.diagram.node(a)!.label, 'Review');
    c.undo();
    expect(c.state.diagram.node(a)!.label, isNot('Review'));
  });
}
