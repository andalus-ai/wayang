import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/project_edit.dart';

void main() {
  test('project names', () {
    expect(projectNameProblem('Billing', const []), isNull);
    expect(projectNameProblem('   ', const []), isNotNull);
    expect(projectNameProblem('x' * 81, const []), isNotNull);
    expect(projectNameProblem('x' * 80, const []), isNull);
    expect(projectNameProblem(' billing ', const ['Billing']), isNotNull);
    expect(projectNameProblem('Billing', const ['Other']), isNull);
  });
}
