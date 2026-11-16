import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/bpmn/selection.dart';
import 'package:wayang_studio/models/bpmn.dart';

void main() {
  final d = BpmnDiagram.empty
      .addNode(const BpmnNode(id: 'n1', type: BpmnElementType.userTask, x: 0, y: 0, label: 'Review order'))
      .addNode(const BpmnNode(id: 'n2', type: BpmnElementType.serviceTask, x: 0, y: 0, label: 'Send order email'))
      .addNode(const BpmnNode(id: 'n3', type: BpmnElementType.endEvent, x: 0, y: 0, label: 'Done'));

  test('an empty query lists every node in order', () {
    expect(findNodes(d, '  ').map((n) => n.id), ['n1', 'n2', 'n3']);
  });

  test('every word must match, case-insensitively, across label, id and type', () {
    expect(findNodes(d, 'ORDER').map((n) => n.id), ['n1', 'n2']);
    expect(findNodes(d, 'order email').map((n) => n.id), ['n2']);
    expect(findNodes(d, 'n3').map((n) => n.id), ['n3']);
    expect(findNodes(d, 'nothing here'), isEmpty);
  });

  test('a label that starts with the query ranks first; ties keep diagram order', () {
    expect(findNodes(d, 'send').first.id, 'n2');
    expect(findNodes(d, 'review o').first.id, 'n1');
    final ties = findNodes(d, 'order');
    expect(ties.map((n) => n.id), ['n1', 'n2']);
  });
}
