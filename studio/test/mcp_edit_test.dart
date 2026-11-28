import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/mcp_edit.dart';

void main() {
  test('server ids', () {
    expect(mcpServerIdProblem('pets_api-2', const []), isNull);
    expect(mcpServerIdProblem('', const []), isNotNull);
    expect(mcpServerIdProblem('a b', const []), isNotNull);
    expect(mcpServerIdProblem('a.b', const []), isNotNull);
    expect(mcpServerIdProblem('x' * 61, const []), isNotNull);
    expect(mcpServerIdProblem('pets', const ['pets']), isNotNull);
  });

  test('path patterns', () {
    expect(pathPatternProblem(const ['^/pets.*', '/users/\\d+']), isNull);
    expect(pathPatternProblem(const []), isNull);
    expect(pathPatternProblem(const ['/ok', '([unclosed']), contains('unclosed'));
  });
}
