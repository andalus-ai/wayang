import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/mapper/mapping_field.dart';

void main() {
  test('the summary counts fields and says when the mapping needs the JSON editor', () {
    expect(MappingField.summary(null), 'Not set');
    expect(MappingField.summary(<String, dynamic>{}), 'Not set');
    expect(MappingField.summary({'a': 'x'}), '1 field');
    expect(MappingField.summary({'a': 'x', 'b': 'y.z'}), '2 fields');
    expect(MappingField.summary({'a': {'b': 'c'}}), 'Custom mapping (edit as JSON)');
    expect(MappingField.summary('text'), 'Custom mapping (edit as JSON)');
  });

  test('the sample of an output mapping carries the last test output as result', () {
    expect(MappingField.sampleText(false, {'a': 1}), '{}');
    expect(MappingField.sampleText(true, null), '{\n  "result": {}\n}');
    expect(MappingField.sampleText(true, {'status': 200}), '{\n  "result": {\n    "status": 200\n  }\n}');
    expect(MappingField.sampleText(true, [1]), contains('"result": ['));
  });
}
