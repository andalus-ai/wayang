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
    expect(rowProblems([MappingRow(target: 'a', value: 'x'), MappingRow(target: 'a.b', value: 'y')]).single, contains('parent'));
    expect(rowProblems([MappingRow(target: 'a..b', value: 'x')]).single, contains('not a valid field path'));
    expect(rowProblems([MappingRow(target: 'a')]), isEmpty, reason: 'not mapped yet is not an error');
    expect(specFromRows([MappingRow(target: 'a'), MappingRow(target: 'b', value: 'x')]), {'b': 'x'}, reason: 'unmapped rows are left out');
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
    expect(rowsFromSpec({'nested': {'a': 'b'}}), isNull);
    expect(rowsFromSpec({'lines': {r'$each': 'body.lines', r'$map': {r'$template': 'x'}}}), isNull, reason: 'an item that is not a set of fields');
    expect(rowsFromSpec({'a': {r'$if': 'x', r'$then': {r'$each': 'y'}}}), isNull, reason: 'a branch cannot be a list');
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

  test('a list row maps each item with its own fields and round-trips', () {
    final spec = {
      'lines': {
        r'$each': 'body.lines',
        r'$as': 'l',
        r'$where': 'l.qty > 0',
        r'$map': {'sku': 'l.sku', 'label': {r'$template': r'#${l.sku}'}, 'source': {r'$const': 'web'}},
      },
      'plain': {r'$each': 'xs'},
    };
    final rows = rowsFromSpec(spec)!;
    expect(rows[0].kind, SourceKind.list);
    expect(rows[0].itemName, 'l');
    expect(rows[0].where, 'l.qty > 0');
    expect(rows[0].children.map((c) => c.kind), [SourceKind.field, SourceKind.template, SourceKind.constant]);
    expect(rows[1].children, isEmpty);
    expect(specFromRows(rows), spec);
  });

  test('the default item name is left out of the spec and lists can nest', () {
    final inner = MappingRow(target: 'tags', kind: SourceKind.list, value: 'item.tags')..children.add(MappingRow(target: 't', value: 'item'));
    inner.itemName = 'item';
    final outer = MappingRow(target: 'rows', kind: SourceKind.list, value: 'body.rows')..children.add(inner);
    final spec = specFromRows([outer])!;
    expect(spec['rows'], {
      r'$each': 'body.rows',
      r'$map': {
        'tags': {r'$each': 'item.tags', r'$map': {'t': 'item'}}
      }
    });
    expect(rowsFromSpec(spec)!.single.children.single.kind, SourceKind.list);
  });

  test('a condition chooses between two values, either of which may be left out', () {
    final r = MappingRow(target: 'lane', kind: SourceKind.conditional, value: 'body.vip');
    r.thenRow = MappingRow(target: '-', kind: SourceKind.constant, value: 'fast');
    r.elseRow = MappingRow(target: '-', value: 'body.lane', transforms: [TransformStep('upper case')]);
    expect(specFromRows([r]), {
      'lane': {r'$if': 'body.vip', r'$then': {r'$const': 'fast'}, r'$else': 'upper case(body.lane)'}
    });
    r.elseRow = MappingRow(target: '-');
    expect(specFromRows([r]), {
      'lane': {r'$if': 'body.vip', r'$then': {r'$const': 'fast'}}
    });
    final back = rowsFromSpec({'lane': {r'$if': 'x', r'$else': 'y'}})!.single;
    expect(back.kind, SourceKind.conditional);
    expect(back.elseRow.value, 'y');
    expect(back.thenRow.value, isEmpty);
  });

  test('problems inside lists and conditions name the row they are in', () {
    final l = MappingRow(target: 'lines', kind: SourceKind.list, value: 'body.lines')..itemName = '1x';
    l.children.addAll([MappingRow(target: 'a', value: 'x'), MappingRow(target: 'a', value: 'y')]);
    final p = rowProblems([l]);
    expect(p.any((m) => m.contains('item name')), isTrue);
    expect(p.any((m) => m.contains('Row 1 ("lines") > Row 2') && m.contains('mapped twice')), isTrue);
    final c = MappingRow(target: 'c', kind: SourceKind.conditional, value: 'x');
    c.thenRow = MappingRow(target: '-', value: 'y', transforms: [TransformStep('round', ['two'])]);
    expect(rowProblems([c]).single, contains('then'));
  });

  test('sample values resolve by path and item paths follow the list', () {
    final sample = {
      'body': {
        'lines': [
          {'sku': 'a', 'qty': 1, 'dims': {'w': 2}}
        ],
        'tags': ['x']
      }
    };
    expect(sampleAt(sample, 'body.lines[1].sku'), 'a');
    expect(sampleAt(sample, 'body.lines[2].sku'), isNull);
    expect(sampleAt(sample, 'body.nope'), isNull);
    expect(itemPaths(sample, 'body.lines', 'l'), ['l.sku', 'l.qty', 'l.dims.w']);
    expect(itemPaths(sample, 'body.tags', ''), ['item']);
    expect(itemPaths(sample, 'body.missing', 'l'), isEmpty);
  });

  test('a row finds its value in the preview result, by dotted path', () {
    final result = {'tier': 'gold', 'customer': {'ok': true, 'n': null}};
    expect(resultAt(result, 'tier'), (true, 'gold'));
    expect(resultAt(result, 'customer.ok'), (true, true));
    expect(resultAt(result, 'customer.n'), (true, null));
    expect(resultAt(result, 'customer.x'), (false, null));
    expect(resultAt(result, 'tier.x'), (false, null));
    expect(resultAt('text', 'a'), (false, null));
  });

  test('values are previewed on one line, long ones summarised', () {
    expect(previewValue(null), 'null');
    expect(previewValue('Ada "L"'), r'"Ada \"L\""');
    expect(previewValue(3), '3');
    expect(previewValue([1, 'a']), '[1, "a"]');
    expect(previewValue({'a': 1}), '{a: 1}');
    expect(previewValue(List.generate(50, (i) => i)), '[50 items]');
    expect(previewValue({for (var i = 0; i < 30; i++) 'k$i': i}), '{30 fields}');
    expect(previewValue('x' * 200, max: 10), '"xxxxxxxx…');
  });

  test('rows come from a JSON Schema: flattened objects, list rows, required fields, refs', () {
    final schema = {
      'type': 'object',
      'required': ['id'],
      r'$defs': {
        'line': {
          'type': 'object',
          'properties': {'sku': {'type': 'string'}, 'qty': {'type': 'integer'}}
        }
      },
      'properties': {
        'id': {'type': 'string', 'description': 'Order id'},
        'customer': {
          'type': 'object',
          'properties': {'name': {'type': 'string'}, 'email': {'type': 'string', 'format': 'email'}}
        },
        'lines': {'type': 'array', 'items': {r'$ref': '#/\$defs/line'}},
        'tags': {'type': 'array', 'items': {'type': 'string'}},
      }
    };
    final rows = rowsFromSchema(schema)!;
    expect(rows.map((r) => r.target), ['id', 'customer.name', 'customer.email', 'lines', 'tags']);
    expect(rows[0].required, isTrue);
    expect(rows[0].hint, 'string, required - Order id');
    expect(rows[2].hint, 'string, email');
    expect(rows[3].kind, SourceKind.list);
    expect(rows[3].children.map((c) => c.target), ['sku', 'qty']);
    expect(rows[4].kind, SourceKind.field, reason: 'an array of scalars is one value');
    expect(unmappedRequired(rows), ['id']);
    expect(specFromRows(rows), isEmpty, reason: 'nothing is connected yet');
  });

  test('a sample connects fields whose name matches exactly one path, also inside lists', () {
    final schema = {
      'properties': {
        'id': {'type': 'string'},
        'name': {'type': 'string'},
        'lines': {
          'type': 'array',
          'items': {
            'properties': {'sku': {'type': 'string'}}
          }
        },
      }
    };
    final sample = {
      'order': {'id': 7, 'name': 'a'},
      'customer': {'name': 'Ada'},
      'lines': [
        {'sku': 's1'}
      ]
    };
    final rows = rowsFromSchema(schema, sample: sample)!;
    expect(rows[0].value, 'order.id');
    expect(rows[1].value, isEmpty, reason: 'name matches two paths');
    expect(rows[2].value, 'lines');
    expect(rows[2].children.single.value, 'item.sku');
    expect(specFromRows(rows), {
      'id': 'order.id',
      'lines': {r'$each': 'lines', r'$map': {'sku': 'item.sku'}},
    });
  });

  test('schemas without properties, or with broken refs, give nothing', () {
    expect(rowsFromSchema({'type': 'string'}), isNull);
    expect(rowsFromSchema('x'), isNull);
    expect(rowsFromSchema({'properties': {'a': {r'$ref': '#/\$defs/missing'}}}), isNull);
  });

  test('the first output item of a list row gives its fields something to show', () {
    final result = {
      'lines': [
        {'sku': 'a', 'qty': 1},
        {'sku': 'b', 'qty': 2}
      ],
      'order': {
        'items': [
          {'n': 1}
        ]
      },
      'empty': [],
      'scalars': [1, 2],
    };
    expect(firstItemResult(result, 'lines'), {'sku': 'a', 'qty': 1});
    expect(firstItemResult(result, 'order.items'), {'n': 1});
    expect(firstItemResult(result, 'empty'), isNull);
    expect(firstItemResult(result, 'scalars'), isNull);
    expect(firstItemResult(result, 'missing'), isNull);
    expect(resultAt(firstItemResult(result, 'lines'), 'sku'), (true, 'a'));
  });

  test('a copy is independent and gets an unused target', () {
    final list = MappingRow(target: 'lines', kind: SourceKind.list, value: 'body.lines')
      ..required = true
      ..hint = 'array'
      ..itemName = 'l'
      ..where = 'l.q > 0'
      ..children.add(MappingRow(target: 'sku', value: 'l.sku', transforms: [TransformStep('replace', ['a', 'b'])]));
    final c = list.copy();
    expect(specFromRows([list]), specFromRows([c..target = 'lines']));
    c.children.single.transforms.single.args[0] = 'z';
    c.children.single.value = 'other';
    expect(list.children.single.transforms.single.args[0], 'a', reason: 'arguments are not shared');
    expect(list.children.single.value, 'l.sku');
    final cond = MappingRow(target: 'c', kind: SourceKind.conditional, value: 'x')..thenRow = MappingRow(target: '-', value: 'y');
    expect(cond.copy().thenRow.value, 'y');
    expect(identical(cond.copy().thenRow, cond.thenRow), isFalse);
    expect(copyTarget('a', [MappingRow(target: 'a')]), 'a_copy');
    expect(copyTarget('a', [MappingRow(target: 'a'), MappingRow(target: 'a_copy'), MappingRow(target: 'a_copy2')]), 'a_copy3');
    expect(copyTarget('', []), 'field_copy');
  });
}
