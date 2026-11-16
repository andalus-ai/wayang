import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/wayang_repository.dart';
import 'package:wayang_studio/features/bpmn/selection.dart';
import 'package:wayang_studio/models/bpmn.dart';
import 'package:wayang_studio/providers/bpmn_provider.dart';

void main() {
  test('snapToGrid rounds to the nearest cell and leaves values alone without a grid', () {
    expect(snapToGrid(29, 20), 20);
    expect(snapToGrid(31, 20), 40);
    expect(snapToGrid(-9, 20), 0);
    expect(snapToGrid(-11, 20), -20);
    expect(snapToGrid(33.3, 0), 33.3);
  });

  test('the arrow-key step is a grid cell, or one pixel with shift', () {
    expect(nudgeStep(fine: false), 20);
    expect(nudgeStep(fine: true), 1);
    expect(nudgeStep(fine: false, grid: 10), 10);
  });

  test('nudging moves the whole selection, one undo step per press, and ignores an empty selection', () {
    final c = BpmnController(WayangRepository(ApiClient(const ApiConfig(baseUrl: 'http://127.0.0.1:1'))), '');
    final a = c.addNode(BpmnElementType.userTask, 100, 100);
    final b = c.addNode(BpmnElementType.userTask, 300, 100);
    c.clearSelection();
    c.nudgeSelected(20, 0);
    expect(c.state.diagram.node(a)!.x, 100, reason: 'nothing selected');
    c.selectNodes([a, b]);
    c.nudgeSelected(20, -1);
    c.nudgeSelected(20, -1);
    expect(c.state.diagram.node(a)!.x, 140);
    expect(c.state.diagram.node(b)!.y, 98);
    c.undo();
    expect(c.state.diagram.node(a)!.x, 120);
    c.undo();
    expect(c.state.diagram.node(a)!.x, 100);
  });
}
