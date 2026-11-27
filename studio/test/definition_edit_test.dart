import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/definition_edit.dart';
import 'package:wayang_studio/models/wayang.dart';

DefinitionSummary d(String id, String name) =>
    DefinitionSummary(kind: DefinitionKind.agent, id: id, name: name, hasDraft: true, latestVersion: 0);

void main() {
  test('an id follows the server rule and must be new', () {
    expect(definitionIdProblem('support-triage_1.2', []), isNull);
    expect(definitionIdProblem('  ok  ', []), isNull, reason: 'trimmed like the server call');
    expect(definitionIdProblem('', []), isNotNull);
    expect(definitionIdProblem('has space', []), contains('no spaces'));
    expect(definitionIdProblem('slash/no', []), isNotNull);
    expect(definitionIdProblem('a' * 101, []), contains('100'));
    expect(definitionIdProblem('a' * 100, []), isNull);
    expect(definitionIdProblem('bot', ['bot', 'x']), contains('already'));
  });

  test('the list filters on every word of name or id', () {
    final all = [d('support-triage', 'Support triage'), d('sales-bot', 'Sales assistant'), d('x', 'Triage helper')];
    expect(filterDefinitions(all, ' ').length, 3);
    expect(filterDefinitions(all, 'TRIAGE').map((e) => e.id), ['support-triage', 'x']);
    expect(filterDefinitions(all, 'support triage').map((e) => e.id), ['support-triage']);
    expect(filterDefinitions(all, 'sales-bot').map((e) => e.id), ['sales-bot']);
    expect(filterDefinitions(all, 'zzz'), isEmpty);
  });

  test('dirty means something is open and its text moved away from what was loaded or saved', () {
    expect(definitionDirty(null, 'a', ''), isFalse);
    expect(definitionDirty('x', 'a', 'a'), isFalse);
    expect(definitionDirty('x', 'ab', 'a'), isTrue);
    expect(definitionDirty('new', '{}', ''), isTrue, reason: 'a new document is unsaved until it is saved');
  });
}
