import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/bpmn_mapper.dart';
import 'package:wayang_studio/data/wayang_repository.dart';
import 'package:wayang_studio/models/bpmn.dart';
import 'package:wayang_studio/providers/bpmn_provider.dart';

BpmnController _controller() => BpmnController(WayangRepository(ApiClient(const ApiConfig(baseUrl: 'http://127.0.0.1:1'))), '');

void main() {
  test('a note is added, edited, dragged and removed, each as undoable steps', () {
    final c = _controller();
    final id = c.addNote(100, 50);
    c.updateNoteText(id, 'check the limits');
    c.updateNoteText(id, 'check the limits'); // unchanged: no extra step
    c.beginNoteDrag();
    c.dragNote(id, 150, 80);
    c.dragNote(id, 200, 90);
    expect((c.state.diagram.notes[id]!.x, c.state.diagram.notes[id]!.y), (200, 90));
    c.undo(); // the whole drag
    expect((c.state.diagram.notes[id]!.x, c.state.diagram.notes[id]!.y), (100, 50));
    c.undo(); // the text
    expect(c.state.diagram.notes[id]!.text, '');
    c.removeNote(id);
    expect(c.state.diagram.notes, isEmpty);
    c.undo();
    expect(c.state.diagram.notes.containsKey(id), isTrue);
  });

  test('note text is capped', () {
    final c = _controller();
    final id = c.addNote(0, 0);
    c.updateNoteText(id, 'x' * 5000);
    expect(c.state.diagram.notes[id]!.text.length, BpmnNote.maxText);
  });

  test('notes round-trip through the document, only in studio.meta, and are absent when there are none', () {
    final d = BpmnDiagram.empty.putNote(const BpmnNote(id: 'n1', x: 10, y: 20, text: 'hello'));
    final doc = bpmnToDocument(d);
    expect(((doc['studio'] as Map)['meta'] as Map)['notes'], [
      {'id': 'n1', 'x': 10.0, 'y': 20.0, 'text': 'hello'},
    ]);
    expect(doc.containsKey('notes'), isFalse);
    final back = bpmnFromDocument(doc);
    expect(back.notes['n1']!.text, 'hello');
    expect(((bpmnToDocument(BpmnDiagram.empty)['studio'] as Map)['meta'] as Map).containsKey('notes'), isFalse);
  });

  test('a hand-edited document with odd notes still opens, dropping what it cannot read', () {
    final doc = bpmnToDocument(BpmnDiagram.empty);
    ((doc['studio'] as Map)['meta'] as Map)['notes'] = [
      {'id': 'ok', 'x': 1, 'y': 2, 'text': 'fine'},
      {'id': 5, 'x': 1, 'y': 2},
      {'id': 'nox', 'y': 2},
      'junk',
      {'id': 'long', 'x': 0, 'y': 0, 'text': 'y' * 5000},
    ];
    final back = bpmnFromDocument(doc);
    expect(back.notes.keys.toSet(), {'ok', 'long'});
    expect(back.notes['long']!.text.length, BpmnNote.maxText);
  });
}
