import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/models/dmn.dart';

void main() {
  const table = DmnDecisionTable(
    id: 't',
    name: 'Risk',
    inputs: [DmnColumn(id: 'in1', label: 'Age', expression: 'age', type: DmnType.number)],
    outputs: [DmnColumn(id: 'out1', label: 'Tier', type: DmnType.string)],
    rules: [
      DmnRule(id: 'r1', inputEntries: ['< 18'], outputEntries: ['"minor"'], annotation: 'children'),
      DmnRule(id: 'r2', inputEntries: ['>= 18'], outputEntries: ['"Adult"']),
      DmnRule(id: 'r3', inputEntries: ['>= 65'], outputEntries: ['"senior"'], annotation: 'adult discount'),
    ],
  );

  test('an empty or blank query keeps every rule', () {
    expect(filterRuleIndexes(table, ''), [0, 1, 2]);
    expect(filterRuleIndexes(table, '   '), [0, 1, 2]);
  });

  test('matches conditions, results, notes and ids, case-insensitively, keeping order', () {
    expect(filterRuleIndexes(table, 'adult'), [1, 2]);
    expect(filterRuleIndexes(table, '>= 65'), [2]);
    expect(filterRuleIndexes(table, 'R1'), [0]);
    expect(filterRuleIndexes(table, 'CHILDREN'), [0]);
  });

  test('every word must match, and no match gives an empty list', () {
    expect(filterRuleIndexes(table, 'adult discount'), [2]);
    expect(filterRuleIndexes(table, 'adult minor'), isEmpty);
    expect(filterRuleIndexes(table, 'zzz'), isEmpty);
  });
}
