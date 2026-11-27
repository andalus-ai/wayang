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
}
