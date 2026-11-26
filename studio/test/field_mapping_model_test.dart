import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/field_mapping_model.dart';

void main() {
  test('transformations nest inside-out', () {
    final row = MappingRow(target: 'n', value: 'customer.name', transforms: [TransformStep('trim'), TransformStep('upper case')]);
    expect(expressionFor(row), 'upper case(trim(customer.name))');
  });

  test('arguments are quoted or checked by kind', () {
    final r = MappingRow(target: 'a', value: 'x', transforms: [
      TransformStep('replace', ['a"b', r'c\d']),
      TransformStep('substring', ['2', '']), // optional length left out
    ]);
    expect(expressionFor(r), r'substring(replace(x, "a\"b", "c\\d"), 2)');
    final bad = MappingRow(target: 'a', value: 'x', transforms: [TransformStep('round', ['two'])]);
    expect(() => expressionFor(bad), throwsFormatException);
    final missing = MappingRow(target: 'a', value: 'x', transforms: [TransformStep('substring', [''])]);
    expect(() => expressionFor(missing), throwsFormatException);
  });

  test('a default is typed when it reads as a number or boolean', () {
    String d(String v) => expressionFor(MappingRow(target: 'a', value: 'x', transforms: [TransformStep('coalesce', [v])]))!;
    expect(d('0'), 'coalesce(x, 0)');
    expect(d('true'), 'coalesce(x, true)');
    expect(d('n/a'), 'coalesce(x, "n/a")');
  });

  test('every transformation exists in the catalog once and has labelled arguments', () {
    final ids = transformCatalog.map((t) => t.id).toList();
    expect(ids.toSet().length, ids.length);
    for (final t in transformCatalog) {
      for (final a in t.args) {
        expect(a.label, isNotEmpty);
      }
    }
  });

  test('constants and templates become directive objects, fields and expressions plain strings', () {
    final spec = specFromRows([
      MappingRow(target: 'tier', value: 'result.data.tier'),
      MappingRow(target: 'lane', kind: SourceKind.constant, value: 'fast'),
      MappingRow(target: 'qty', kind: SourceKind.constant, value: '3'),
      MappingRow(target: 'flag', kind: SourceKind.constant, value: 'true'),
      MappingRow(target: 'hello', kind: SourceKind.template, value: r'Hi ${customer.name}'),
      MappingRow(target: 'customer.ok', kind: SourceKind.expression, value: 'result.status = 200'),
      MappingRow(), // a fresh blank row is ignored
    ])!;
    expect(spec, {
      'tier': 'result.data.tier',
      'lane': {r'$const': 'fast'},
      'qty': {r'$const': 3},
      'flag': {r'$const': true},
      'hello': {r'$template': r'Hi ${customer.name}'},
      'customer.ok': 'result.status = 200',
    });
  });

  test('problems are reported instead of building a wrong spec', () {
    expect(rowProblems([MappingRow(target: 'a', value: 'x'), MappingRow(target: 'a', value: 'y')]).single, contains('mapped twice'));
    expect(rowProblems([MappingRow(value: 'x')]).single, contains('no target'));
    expect(rowProblems([MappingRow(target: 'a')]).single, contains('no source'));
    expect(rowProblems([MappingRow(target: 'a', value: 'x'), MappingRow(target: 'a.b', value: 'y')]).single, contains('parent'));
    expect(rowProblems([MappingRow(target: 'a..b', value: 'x')]).single, contains('not a valid field path'));
    expect(specFromRows([MappingRow(target: 'a')]), isNull);
    expect(rowProblems([MappingRow()]), isEmpty);
  });

  test('a spec reads back into rows, and anything richer is left to the JSON editor', () {
    final rows = rowsFromSpec({
      'tier': 'result.data.tier',
      'ok': 'result.status = 200',
      'lane': {r'$const': 'fast'},
      'hi': {r'$template': r'Hi ${a}'},
    })!;
    expect(rows.map((r) => r.kind), [SourceKind.field, SourceKind.expression, SourceKind.constant, SourceKind.template]);
    expect(rows[1].value, 'result.status = 200');
    expect(specFromRows(rows), {
      'tier': 'result.data.tier',
      'ok': 'result.status = 200',
      'lane': {r'$const': 'fast'},
      'hi': {r'$template': r'Hi ${a}'},
    });
    expect(rowsFromSpec({'lines': {r'$each': 'body.lines', r'$map': {'a': 'x'}}}), isNull);
    expect(rowsFromSpec({'nested': {'a': 'b'}}), isNull);
    expect(rowsFromSpec([1, 2]), isNull);
    expect(rowsFromSpec({'n': 5}), isNull, reason: 'a bare number is a literal the builder does not model');
  });

  test('sample paths offer scalars, lists and the first list element, skipping awkward keys', () {
    final paths = samplePaths({
      'customer': {'name': 'Ada', 'tags': ['a']},
      'lines': [
        {'sku': 'a', 'qty': 1}
      ],
      'odd key': 1,
      'ok': true,
    });
    expect(paths, ['customer.name', 'customer.tags', 'customer.tags[1]', 'lines', 'lines[1].sku', 'lines[1].qty', 'ok']);
    expect(samplePaths(null), isEmpty);
  });

  test('auto-map names targets after the last segment and avoids clashes and existing rows', () {
    final existing = [MappingRow(target: 'name', value: 'customer.name')];
    final rows = autoMapRows(['customer.name', 'partner.name', 'id', 'lines[1].sku'], existing);
    expect(rows.map((r) => '${r.target}<-${r.value}'), ['partner_name<-partner.name', 'id<-id']);
  });
}
