import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/bpmn/bpmn_canvas.dart';
import 'package:wayang_studio/features/bpmn/guides.dart';
import 'package:wayang_studio/models/bpmn.dart';

void main() {
  final size = bpmnNodeSize(BpmnElementType.userTask);
  final d = BpmnDiagram.empty
      .addNode(const BpmnNode(id: 'still', type: BpmnElementType.userTask, x: 500, y: 300))
      .addNode(const BpmnNode(id: 'mover', type: BpmnElementType.userTask, x: 0, y: 0));

  Rect boxAt(double x, double y) => Rect.fromLTWH(x, y, size.width, size.height);

  test('a left edge within the threshold snaps and draws a vertical guide', () {
    final g = alignmentGuides(d, {'mover'}, boxAt(496, 100));
    expect(g.dx, 4);
    expect(g.vertical, contains(500));
    expect(g.dy, 0);
  });

  test('a top edge within the threshold snaps on y', () {
    final g = alignmentGuides(d, {'mover'}, boxAt(100, 303));
    expect(g.dy, -3);
    expect(g.horizontal, contains(300));
  });

  test('centres line up too', () {
    final g = alignmentGuides(d, {'mover'}, boxAt(500 + 5, 100));
    expect(g.dx, -5);
  });

  test('nothing close means no guides and no shift', () {
    final g = alignmentGuides(d, {'mover'}, boxAt(100, 100));
    expect(g.isEmpty, isTrue);
    expect((g.dx, g.dy), (0, 0));
  });

  test('moving nodes never guide against themselves', () {
    final g = alignmentGuides(d, {'mover', 'still'}, boxAt(0, 0));
    expect(g.isEmpty, isTrue);
  });

  test('the box of a moved group covers every member', () {
    final two = d.addNode(const BpmnNode(id: 'm2', type: BpmnElementType.userTask, x: 300, y: 50));
    final box = boxOfMoved(two, {'mover': const Offset(0, 0), 'm2': const Offset(300, 50)}, 10, 20);
    expect(box.left, 10);
    expect(box.top, 20);
    expect(box.right, 310 + size.width);
    expect(box.bottom, 70 + size.height);
  });
}
