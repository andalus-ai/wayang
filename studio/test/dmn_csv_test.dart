import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/dmn_csv.dart';
import 'package:wayang_studio/models/dmn.dart';

DmnDecisionTable _table() => const DmnDecisionTable(
      id: 't',
      name: 'Risk',
      inputs: [
        DmnColumn(id: 'in1', label: 'Age', expression: 'age', type: DmnType.number),
        DmnColumn(id: 'in2', label: 'Country', expression: 'country', type: DmnType.string),
      ],
      outputs: [DmnColumn(id: 'out1', label: 'Tier', type: DmnType.string)],
      rules: [
        DmnRule(id: 'a', inputEntries: ['< 18', '-'], outputEntries: ['"minor"'], annotation: 'kids, "young"'),
        DmnRule(id: 'b', inputEntries: ['>= 18', '"ID","SG"'], outputEntries: ['"adult"']),
      ],
    );

int _n = 0;
String _id() => 'new${_n++}';

void main() {
  test('rules export with a header, quoting commas, quotes and line breaks', () {
    final csv = rulesToCsv(_table());
    final rows = parseCsv(csv)!;
    expect(rows.first, ['Age', 'Country', 'Tier', 'Note']);
    expect(rows[1], ['< 18', '-', '"minor"', 'kids, "young"']);
    expect(rows[2], ['>= 18', '"ID","SG"', '"adult"', '']);
  });

  test('what is exported can be imported back as the same rules', () {
    final t = _table();
    final r = rulesFromCsv(t, rulesToCsv(t), _id);
    expect(r.error, isNull);
    expect(r.rules.length, 2);
    for (var i = 0; i < 2; i++) {
      expect(r.rules[i].inputEntries, t.rules[i].inputEntries);
      expect(r.rules[i].outputEntries, t.rules[i].outputEntries);
      expect(r.rules[i].annotation, t.rules[i].annotation);
    }
    expect(r.rules.map((x) => x.id).toSet().length, 2);
  });

  test('the parser handles CRLF, a quoted line break, doubled quotes and a missing final newline', () {
    expect(parseCsv('a,b\r\n"x\ny","he said ""hi"""\r\nlast,row'), [
      ['a', 'b'],
      ['x\ny', 'he said "hi"'],
      ['last', 'row'],
    ]);
    expect(parseCsv('a,b\n\n\nc,d'), [
      ['a', 'b'],
      ['c', 'd'],
    ]);
  });

  test('an unclosed quote or an oversized text is refused, not guessed at', () {
    expect(parseCsv('a,"b'), isNull);
    expect(parseCsv('x' * (csvMaxChars + 1)), isNull);
    expect(rulesFromCsv(_table(), 'Age\n"unclosed', _id).error, isNotNull);
  });

  test('headers match by label in any order and case; unknown headers are reported; missing columns get defaults', () {
    final r = rulesFromCsv(_table(), 'tier , AGE , Extra\n"gold",> 30,whatever', _id);
    expect(r.error, isNull);
    expect(r.rules.single.inputEntries, ['> 30', '-']);
    expect(r.rules.single.outputEntries, ['"gold"']);
    expect(r.ignoredColumns, ['Extra']);
  });

  test('nothing importable: no header match, or no data rows', () {
    expect(rulesFromCsv(_table(), 'Foo,Bar\n1,2', _id).error, isNotNull);
    expect(rulesFromCsv(_table(), 'Age,Tier', _id).error, isNotNull);
  });

  test('at most the row cap is read', () {
    final csv = 'Age,Tier\n${List.generate(csvMaxRows + 50, (i) => '$i,"t"').join('\n')}';
    expect(rulesFromCsv(_table(), csv, _id).rules.length, csvMaxRows);
  });
}
