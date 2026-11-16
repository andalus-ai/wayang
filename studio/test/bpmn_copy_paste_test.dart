import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/wayang_repository.dart';
import 'package:wayang_studio/models/bpmn.dart';
import 'package:wayang_studio/providers/bpmn_provider.dart';

BpmnController _controller() => BpmnController(WayangRepository(ApiClient(const ApiConfig(baseUrl: 'http://127.0.0.1:1'))), '');

void main() {
  test('copy takes the selection and only the flows between its members', () {
    final c = _controller();
    final a = c.addNode(BpmnElementType.userTask, 10, 10);
    final b = c.addNode(BpmnElementType.userTask, 300, 10);
    final out = c.addNode(BpmnElementType.endEvent, 600, 10);
    c.startConnection(a, Offset.zero);
    c.completeConnection(b);
    c.startConnection(b, Offset.zero);
    c.completeConnection(out);
    c.selectNodes([a, b]);
    final clip = c.copySelection()!;
    expect(clip.nodes.length, 2);
    expect(clip.flows.length, 1);
    c.clearSelection();
    expect(c.copySelection(), isNull);
  });

  test('paste in the same process nudges the copy off the original, and each paste lands further along', () {
    final c = _controller();
    final a = c.addNode(BpmnElementType.userTask, 10, 10);
    c.selectNodes([a]);
    final clip = c.copySelection()!;
    c.pasteClip(clip);
    final first = c.state.diagram.node(c.state.selectedNodeIds.single)!;
    expect((first.x, first.y), (50, 50));
    c.pasteClip(clip);
    final second = c.state.diagram.node(c.state.selectedNodeIds.single)!;
    expect((second.x, second.y), (90, 90), reason: 'a second paste must not stack on the first');
  });

  test('paste at a point puts the clip top-left there; the copy has new ids and its internal flow', () {
    final c = _controller();
    final a = c.addNode(BpmnElementType.userTask, 100, 100);
    final b = c.addNode(BpmnElementType.userTask, 400, 100);
    c.startConnection(a, Offset.zero);
    c.completeConnection(b);
    c.selectNodes([a, b]);
    final clip = c.copySelection()!;
    c.pasteClip(clip, at: const Offset(1000, 500));
    final pasted = c.state.selectedNodeIds;
    expect(pasted.length, 2);
    expect(pasted.intersection({a, b}), isEmpty);
    final xs = pasted.map((id) => c.state.diagram.node(id)!.x).toList()..sort();
    expect(xs, [1000, 1300]);
    expect(c.state.diagram.flowList.length, 2);
    c.undo();
    expect(c.state.diagram.nodeCount, 2);
  });

  test('a clip pastes into another process (a fresh controller) with its labels and properties', () {
    final from = _controller();
    final a = from.addNode(BpmnElementType.serviceTask, 10, 10);
    from.selectNodes([a]);
    final clip = from.copySelection()!;
    final to = _controller();
    to.pasteClip(clip, at: const Offset(200, 200));
    final n = to.state.diagram.node(to.state.selectedNodeIds.single)!;
    expect(n.type, BpmnElementType.serviceTask);
    expect((n.x, n.y), (200, 200));
  });

  test('an empty clip does nothing', () {
    final c = _controller();
    c.pasteClip(const BpmnClip([], []));
    expect(c.state.diagram.nodeCount, 0);
  });
}
