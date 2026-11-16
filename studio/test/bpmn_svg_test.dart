import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/bpmn/diagram_svg.dart';
import 'package:wayang_studio/models/bpmn.dart';

void main() {
  notesInSvgTests();
  BpmnDiagram sample() {
    var d = BpmnDiagram.empty
        .addNode(const BpmnNode(id: 's', type: BpmnElementType.startEvent, x: 60, y: 60, label: 'Start'))
        .addNode(const BpmnNode(id: 't', type: BpmnElementType.userTask, x: 260, y: 40, label: 'Review <order> & "approve"'))
        .addNode(const BpmnNode(id: 'g', type: BpmnElementType.exclusiveGateway, x: 520, y: 60));
    d = d.connect(const BpmnFlow(id: 'f1', from: 's', to: 't'));
    d = d.connect(const BpmnFlow(id: 'f2', from: 't', to: 'g', label: 'done', isDefault: true));
    return d;
  }

  test('the picture holds a shape per node, a line per flow, and is a complete svg document', () {
    final svg = diagramToSvg(sample());
    expect(svg.startsWith('<svg '), isTrue);
    expect(svg.endsWith('</svg>'), isTrue);
    expect(RegExp('<circle').allMatches(svg).length, 1);
    expect(RegExp('<polygon').allMatches(svg).length, 1);
    expect(RegExp('<line ').allMatches(svg).length, 2);
    expect(svg, contains('stroke-dasharray'), reason: 'the default flow is dashed');
    expect(svg, contains('>done<'));
  });

  test('labels are escaped, so a name cannot inject markup', () {
    final svg = diagramToSvg(sample());
    expect(svg, contains('Review &lt;order&gt; &amp; &quot;approve&quot;'));
    expect(svg, isNot(contains('<order>')));
    final evil = BpmnDiagram.empty.addNode(const BpmnNode(id: 'x', type: BpmnElementType.userTask, x: 0, y: 0, label: '</text><script>alert(1)</script>'));
    final out = diagramToSvg(evil);
    expect(out, isNot(contains('<script')));
    expect(out, contains('&lt;/text&gt;&lt;script&gt;'));
  });

  test('control characters are dropped and long labels are shortened', () {
    final d = BpmnDiagram.empty.addNode(BpmnNode(id: 'x', type: BpmnElementType.userTask, x: 0, y: 0, label: 'a\u0001b${'z' * 80}'));
    final svg = diagramToSvg(d);
    expect(svg.contains('\u0001'), isFalse);
    expect(svg, contains('…'));
  });

  test('node settings never appear in the picture', () {
    final d = BpmnDiagram.empty.addNode(const BpmnNode(id: 'x', type: BpmnElementType.serviceTask, x: 0, y: 0, label: 'Call', properties: {'apiKey': r'${secret:API_KEY}', 'url': 'https://internal.example/x'}));
    final svg = diagramToSvg(d);
    expect(svg, isNot(contains('secret')));
    expect(svg, isNot(contains('internal.example')));
  });

  test('an empty diagram is still a valid, sized picture', () {
    final svg = diagramToSvg(BpmnDiagram.empty);
    expect(svg, contains('width="'));
    expect(svg, contains('</svg>'));
  });
}

void notesInSvgTests() {
  test('notes are drawn, wrapped, escaped and counted in the picture size', () {
    final d = BpmnDiagram.empty.putNote(const BpmnNote(id: 'n', x: 900, y: 40, text: '<b>careful</b>\nsecond line'));
    final svg = diagramToSvg(d);
    expect(svg, contains('fill="#fff3b0"'));
    expect(svg, contains('&lt;b&gt;careful&lt;/b&gt;'));
    expect(svg, contains('second line'));
    expect(svg, isNot(contains('<b>')));
    final width = double.parse(RegExp(r'<svg [^>]*width="([0-9.]+)"').firstMatch(svg)!.group(1)!);
    expect(width >= 900 + BpmnNote.width, isTrue);
  });

  test('a very long note is cut to six lines with an ellipsis', () {
    final svg = diagramToSvg(BpmnDiagram.empty.putNote(BpmnNote(id: 'n', x: 0, y: 0, text: 'word ' * 200)));
    expect(RegExp('<text ').allMatches(svg).length, 6);
    expect(svg, contains('…'));
  });
}
