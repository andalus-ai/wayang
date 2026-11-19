import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/component_inputs.dart';
import 'package:wayang_studio/models/wayang.dart';

ComponentCatalogEntry _entry(String id, List<Map<String, dynamic>> inputs, {String kind = 'CONNECTOR'}) => ComponentCatalogEntry.fromJson({
      'id': id,
      'kind': kind,
      'configSchema': {'type': 'object'},
      'uiHints': {'inputs': inputs},
    });

void main() {
  final ingest = _entry('wayang.knowledge.rag-ingest', [
    {'name': 'note', 'type': 'string', 'mandatory': false},
    {'name': 'documents', 'type': 'array', 'mandatory': true, 'description': 'Documents to add'},
  ]);

  test('reads declared inputs, mandatory first', () {
    final inputs = componentInputs(ingest);
    expect(inputs.map((i) => i.name), ['documents', 'note']);
    expect(inputs.first.mandatory, isTrue);
    expect(inputs.first.description, 'Documents to add');
    expect(componentInputs(_entry('x.y', [{'type': 'string'}, {'name': ''}])), isEmpty);
  });

  test('finds the component by binding, else by connector name', () {
    final other = _entry('wayang.knowledge.rag-search', []);
    final all = [other, ingest];
    expect(componentForNode(all, {'wayang.component.id': 'wayang.knowledge.rag-ingest'})?.id, 'wayang.knowledge.rag-ingest');
    expect(componentForNode(all, {'connector': 'rag-ingest'})?.id, 'wayang.knowledge.rag-ingest');
    expect(componentForNode(all, {'connector': 'nothing'}), isNull);
    expect(componentForNode(all, {}), isNull);
  });

  test('supplied, mapped and empty inputs', () {
    final docs = componentInputs(ingest).first;
    expect(inputState(docs, {'inputs': {'documents': 'upload'}}), InputState.connected);
    expect(inputState(docs, {'inputs': {'documents': '  '}}), InputState.empty);
    expect(inputState(docs, {'inputMapping': {'documents': 'x'}}), InputState.mapped);
    expect(inputState(docs, {}), InputState.empty);
  });

  test('unsuppliedMandatory lists only mandatory gaps', () {
    final inputs = componentInputs(ingest);
    expect(unsuppliedMandatory(inputs, {}).map((i) => i.name), ['documents']);
    expect(unsuppliedMandatory(inputs, {'inputs': {'documents': 'd'}}), isEmpty);
  });
}
