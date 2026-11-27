/// Model behind the visual field-mapping builder. The JSON spec stays the source of truth (see wayang-integration `Mapper`); this file turns rows
/// into a spec and back. Transformations are the deterministic FEEL built-ins of wayang-expression (no regex, no I/O), applied inside-out:
/// a row "name <- trim, upper case" is the expression `upper case(trim(name))`. Pure Dart, no Flutter.
library;

/// What a row's source column holds.
///
/// [list] maps every item of a list (`$each`): [MappingRow.value] is the list, [MappingRow.children] describe one output item, written
/// against [MappingRow.itemName]. [conditional] chooses between two values (`$if`): [MappingRow.value] is the condition.
enum SourceKind { field, constant, template, expression, list, conditional }

/// How a transformation argument is written into the expression.
enum ArgKind {
  /// Always a quoted text literal.
  text,

  /// A number; must parse as one.
  number,

  /// A number, true, false or null when it reads as one, otherwise text.
  value,
}

class ArgSpec {
  const ArgSpec(this.label, this.kind, {this.optional = false, this.hint = ''});
  final String label;
  final ArgKind kind;
  final bool optional;
  final String hint;
}

class TransformDef {
  const TransformDef(this.id, this.label, this.group, {this.args = const [], this.help = ''});

  /// The FEEL built-in name (may contain spaces, as in `upper case`).
  final String id;
  final String label;
  final String group;
  final List<ArgSpec> args;
  final String help;
}

const transformCatalog = <TransformDef>[
  TransformDef('upper case', 'UPPER CASE', 'Text', help: 'Ada -> ADA'),
  TransformDef('lower case', 'lower case', 'Text', help: 'Ada -> ada'),
  TransformDef('trim', 'Trim spaces', 'Text', help: 'Removes leading and trailing spaces'),
  TransformDef('substring', 'Part of text', 'Text',
      args: [ArgSpec('Start (1 = first)', ArgKind.number), ArgSpec('Length', ArgKind.number, optional: true)], help: 'Negative start counts from the end'),
  TransformDef('replace', 'Replace text', 'Text', args: [ArgSpec('Find', ArgKind.text), ArgSpec('Replace with', ArgKind.text)], help: 'Plain text, not a pattern'),
  TransformDef('substring before', 'Text before', 'Text', args: [ArgSpec('Marker', ArgKind.text)]),
  TransformDef('substring after', 'Text after', 'Text', args: [ArgSpec('Marker', ArgKind.text)]),
  TransformDef('split', 'Split into list', 'Text', args: [ArgSpec('Separator', ArgKind.text)]),
  TransformDef('string length', 'Length of text', 'Text'),
  TransformDef('string', 'To text', 'Convert'),
  TransformDef('number', 'To number', 'Convert', help: 'Text such as "12.5" becomes 12.5; anything else becomes null'),
  TransformDef('date', 'To date', 'Convert', help: 'ISO text such as 2026-11-27'),
  TransformDef('date and time', 'To date and time', 'Convert', help: 'ISO text with an offset'),
  TransformDef('round', 'Round', 'Number', args: [ArgSpec('Decimals', ArgKind.number, optional: true)], help: 'Half up'),
  TransformDef('abs', 'Absolute value', 'Number'),
  TransformDef('floor', 'Round down', 'Number'),
  TransformDef('ceiling', 'Round up', 'Number'),
  TransformDef('coalesce', 'Default if missing', 'Fallback', args: [ArgSpec('Default', ArgKind.value)], help: 'Used when the source is missing or null'),
  TransformDef('string join', 'Join list into text', 'List', args: [ArgSpec('Separator', ArgKind.text, optional: true)]),
  TransformDef('count', 'Count items', 'List'),
  TransformDef('sum', 'Sum of items', 'List'),
  TransformDef('distinct values', 'Remove duplicates', 'List'),
  TransformDef('reverse', 'Reverse list', 'List'),
];

TransformDef? transformById(String id) {
  for (final t in transformCatalog) {
    if (t.id == id) return t;
  }
  return null;
}

class TransformStep {
  TransformStep(this.fn, [List<String>? args]) : args = args ?? List<String>.filled(transformById(fn)?.args.length ?? 0, '');
  final String fn;
  final List<String> args;
}

class MappingRow {
  MappingRow({this.target = '', this.kind = SourceKind.field, this.value = '', List<TransformStep>? transforms}) : transforms = transforms ?? [];

  /// Output field; a dotted name sets a nested path (`customer.name`).
  String target;
  SourceKind kind;

  /// From a schema: a short description (type, required) shown under the field name, and whether the schema requires the field.
  String hint = '';
  bool required = false;

  /// A source path, a literal, template text with `${...}`, or a raw expression, by [kind].
  String value;
  final List<TransformStep> transforms;

  /// [SourceKind.list]: the name the current item is bound to (the mapper's default is `item`), an optional filter, and one row per output field.
  String itemName = 'item';
  String where = '';
  final List<MappingRow> children = [];

  /// [SourceKind.conditional]: the value when the condition holds and when it does not (a blank value means no value, i.e. null).
  MappingRow get thenRow => _then ??= MappingRow(target: '-');
  set thenRow(MappingRow r) => _then = r;
  MappingRow get elseRow => _else ??= MappingRow(target: '-');
  set elseRow(MappingRow r) => _else = r;
  MappingRow? _then;
  MappingRow? _else;
}

final _identifier = RegExp(r'^[A-Za-z_$][A-Za-z0-9_$]*$');
final _number = RegExp(r'^-?\d+(\.\d+)?$');

/// A text literal for an expression: quoted, with backslash and quote escaped.
String feelString(String s) => '"${s.replaceAll(r'\', r'\\').replaceAll('"', r'\"')}"';

/// The literal for an argument, or null when [raw] does not fit [kind].
String? argLiteral(ArgKind kind, String raw) {
  switch (kind) {
    case ArgKind.text:
      return feelString(raw);
    case ArgKind.number:
      final t = raw.trim();
      return _number.hasMatch(t) ? t : null;
    case ArgKind.value:
      final t = raw.trim();
      if (_number.hasMatch(t) || t == 'true' || t == 'false' || t == 'null') return t;
      return feelString(raw);
  }
}

/// The expression of [row], or null for a row without a source. Throws [FormatException] when a numeric argument is not a number.
String? expressionFor(MappingRow row) {
  if (row.kind == SourceKind.constant || row.kind == SourceKind.template) return null;
  var e = row.value.trim();
  if (e.isEmpty) return null;
  for (final t in row.transforms) {
    final def = transformById(t.fn);
    if (def == null) throw FormatException('Unknown transformation "${t.fn}"');
    final parts = <String>[e];
    for (var i = 0; i < def.args.length; i++) {
      final raw = i < t.args.length ? t.args[i] : '';
      if (raw.isEmpty && def.args[i].optional) break;
      final lit = argLiteral(def.args[i].kind, raw);
      if (lit == null || (raw.isEmpty && def.args[i].kind != ArgKind.text)) {
        throw FormatException('"${def.label}": ${def.args[i].label} must be a number');
      }
      parts.add(lit);
    }
    e = '${def.id}(${parts.join(', ')})';
  }
  return e;
}

/// The value of [row] in the spec: a string is an expression, so literals and templates need their directive objects.
Object? specValue(MappingRow row) {
  switch (row.kind) {
    case SourceKind.constant:
      final t = row.value.trim();
      if (_number.hasMatch(t)) return {r'$const': num.parse(t)};
      if (t == 'true' || t == 'false') return {r'$const': t == 'true'};
      if (t == 'null') return {r'$const': null};
      return {r'$const': row.value};
    case SourceKind.template:
      return {r'$template': row.value};
    case SourceKind.list:
      final m = <String, dynamic>{r'$each': row.value.trim()};
      if (row.itemName.trim().isNotEmpty && row.itemName.trim() != 'item') m[r'$as'] = row.itemName.trim();
      if (row.where.trim().isNotEmpty) m[r'$where'] = row.where.trim();
      final fields = specFromRows(row.children);
      if (fields != null && fields.isNotEmpty) m[r'$map'] = fields;
      return m;
    case SourceKind.conditional:
      final m = <String, dynamic>{r'$if': row.value.trim()};
      if (_present(row.thenRow)) m[r'$then'] = specValue(row.thenRow);
      if (_present(row.elseRow)) m[r'$else'] = specValue(row.elseRow);
      return m;
    default:
      return expressionFor(row);
  }
}

bool _present(MappingRow r) => r.value.trim().isNotEmpty;

/// Problems that stop a spec from being built; empty when the rows are fine. Rows with neither target nor source are ignored (a fresh row).
/// [where] names the enclosing list row for messages inside a list.
List<String> rowProblems(List<MappingRow> rows, [String where = '']) {
  final out = <String>[];
  final seen = <String>{};
  for (var i = 0; i < rows.length; i++) {
    final r = rows[i];
    final n = '${where}Row ${i + 1}';
    final blank = r.target.trim().isEmpty && r.value.trim().isEmpty && r.children.isEmpty;
    if (blank) continue;
    final target = r.target.trim();
    if (target.isEmpty) {
      out.add('$n has no target field.');
      continue;
    }
    if (!target.split('.').every((p) => p.isNotEmpty)) out.add('$n: "$target" is not a valid field path.');
    if (!seen.add(target)) out.add('$n: "$target" is mapped twice.');
    if (r.value.trim().isEmpty) continue; // not mapped yet: left out of the spec, listed by unmappedRequired when the schema needs it
    switch (r.kind) {
      case SourceKind.list:
        if (!_identifier.hasMatch(r.itemName.trim())) out.add('$n ("$target"): the item name must be a plain name such as item.');
        out.addAll(rowProblems(r.children, '$n ("$target") > '));
      case SourceKind.conditional:
        for (final e in [('then', r.thenRow), ('else', r.elseRow)]) {
          if (e.$2.kind == SourceKind.conditional || e.$2.kind == SourceKind.list) {
            out.add('$n ("$target"): the ${e.$1} value is a field, fixed value, template or expression.');
          } else if (_present(e.$2)) {
            try {
              expressionFor(e.$2);
            } on FormatException catch (x) {
              out.add('$n ("$target") ${e.$1}: ${x.message}');
            }
          }
        }
      default:
        try {
          expressionFor(r);
        } on FormatException catch (e) {
          out.add('$n: ${e.message}');
        }
    }
  }
  final targets = seen.toList();
  for (final a in targets) {
    for (final b in targets) {
      if (a != b && b.startsWith('$a.')) out.add('$where"$a" is a value and also a parent of "$b"; keep one of them.');
    }
  }
  return out;
}

/// The mapping spec for [rows] (blank rows skipped), or null when [rowProblems] is not empty.
Map<String, dynamic>? specFromRows(List<MappingRow> rows) {
  if (rowProblems(rows).isNotEmpty) return null;
  final spec = <String, dynamic>{};
  for (final r in rows) {
    if (r.target.trim().isEmpty || r.value.trim().isEmpty) continue;
    spec[r.target.trim()] = specValue(r);
  }
  return spec;
}

/// One output value as a row, or null when it is something the builder cannot show.
MappingRow? _rowFromValue(String target, Object? v, int depth) {
  if (v is String) {
    return MappingRow(target: target, kind: _isPath(v) ? SourceKind.field : SourceKind.expression, value: v);
  }
  if (v is! Map) return null;
  if (v.length == 1 && v.containsKey(r'$const') && v[r'$const'] is! Map && v[r'$const'] is! List) {
    return MappingRow(target: target, kind: SourceKind.constant, value: v[r'$const'] == null ? 'null' : '${v[r'$const']}');
  }
  if (v.length == 1 && v[r'$template'] is String) {
    return MappingRow(target: target, kind: SourceKind.template, value: v[r'$template'] as String);
  }
  if (depth >= 4) return null;
  if (v[r'$each'] is String && v.keys.every((k) => const {r'$each', r'$as', r'$where', r'$map'}.contains(k))) {
    final r = MappingRow(target: target, kind: SourceKind.list, value: v[r'$each'] as String);
    if (v.containsKey(r'$as')) {
      if (v[r'$as'] is! String) return null;
      r.itemName = v[r'$as'] as String;
    }
    if (v.containsKey(r'$where')) {
      if (v[r'$where'] is! String) return null;
      r.where = v[r'$where'] as String;
    }
    if (v.containsKey(r'$map')) {
      final kids = _rowsFrom(v[r'$map'], depth + 1);
      if (kids == null) return null;
      r.children.addAll(kids);
    }
    return r;
  }
  if (v[r'$if'] is String && v.keys.every((k) => const {r'$if', r'$then', r'$else'}.contains(k))) {
    final r = MappingRow(target: target, kind: SourceKind.conditional, value: v[r'$if'] as String);
    for (final e in [(r'$then', true), (r'$else', false)]) {
      if (!v.containsKey(e.$1)) continue;
      final branch = _rowFromValue('-', v[e.$1], 4); // depth 4: a branch is a single value, not another list or condition
      if (branch == null) return null;
      if (e.$2) {
        r.thenRow = branch;
      } else {
        r.elseRow = branch;
      }
    }
    return r;
  }
  return null;
}

List<MappingRow>? _rowsFrom(Object? spec, int depth) {
  if (spec is! Map) return null;
  if (spec.keys.any((k) => '$k'.startsWith(r'$'))) return null; // a directive, not a set of fields
  final rows = <MappingRow>[];
  for (final e in spec.entries) {
    final row = _rowFromValue('${e.key}', e.value, depth);
    if (row == null) return null;
    rows.add(row);
  }
  return rows;
}

/// Rows for an existing spec, or null when it uses something the builder cannot show (nested plain objects, literal lists, a list item that is
/// not an object, a branch that is itself a list or condition, ...), in which case the JSON editor is the place to change it. A transformed
/// source comes back as a raw expression: nothing is lost, it is just not split into steps again.
List<MappingRow>? rowsFromSpec(Object? spec) => _rowsFrom(spec, 0);

bool _isPath(String s) => s.split('.').every(_identifier.hasMatch);

/// Source paths offered for a sample input: every scalar, plus lists and objects as a whole, as `a.b` and `list[1].x` (expression lists count
/// from 1). Keys that are not plain identifiers are skipped (they need quoting). Bounded in depth and count.
List<String> samplePaths(Object? sample, {int maxPaths = 300, int maxDepth = 6}) {
  final out = <String>[];
  void walk(Object? v, String path, int depth) {
    if (out.length >= maxPaths) return;
    if (v is Map) {
      for (final e in v.entries) {
        final k = '${e.key}';
        if (!_identifier.hasMatch(k)) continue;
        final p = path.isEmpty ? k : '$path.$k';
        if (e.value is Map && depth < maxDepth) {
          walk(e.value, p, depth + 1);
        } else if (e.value is List) {
          out.add(p);
          final l = e.value as List;
          if (l.isNotEmpty && depth < maxDepth) walk(l.first, '$p[1]', depth + 1);
        } else {
          out.add(p);
        }
      }
    } else if (path.isNotEmpty) {
      out.add(path);
    }
  }

  walk(sample, '', 0);
  return out;
}

/// Pairs each unmapped sample path with a target named after its last segment (`customer.name` -> `name`, then `customer_name` on a clash).
/// Returns only the new rows; existing targets and sources are left alone.
List<MappingRow> autoMapRows(List<String> paths, List<MappingRow> existing) {
  final usedTargets = {for (final r in existing) r.target.trim()};
  final usedSources = {for (final r in existing) r.value.trim()};
  final out = <MappingRow>[];
  for (final p in paths) {
    if (usedSources.contains(p) || p.contains('[')) continue;
    final segs = p.split('.');
    var t = segs.last;
    if (usedTargets.contains(t) && segs.length > 1) t = segs.join('_');
    var candidate = t;
    var n = 2;
    while (usedTargets.contains(candidate)) {
      candidate = '${t}_$n';
      n++;
    }
    usedTargets.add(candidate);
    out.add(MappingRow(target: candidate, value: p));
  }
  return out;
}

final _segment = RegExp(r'^([A-Za-z_$][A-Za-z0-9_$]*)((?:\[\d+\])*)$');

/// The value at [path] (`a.b`, `lines[1].sku`; lists count from 1) in [sample], or null when it is not there.
Object? sampleAt(Object? sample, String path) {
  Object? cur = sample;
  for (final seg in path.trim().split('.')) {
    final m = _segment.firstMatch(seg);
    if (m == null || cur is! Map) return null;
    cur = cur[m.group(1)];
    for (final idx in RegExp(r'\[(\d+)\]').allMatches(m.group(2) ?? '')) {
      final i = int.parse(idx.group(1)!);
      if (cur is! List || i < 1 || i > cur.length) return null;
      cur = cur[i - 1];
    }
  }
  return cur;
}

/// Source paths inside one item of the list at [listPath], written against [itemName] (`item.sku`); empty when the sample has no such list.
List<String> itemPaths(Object? sample, String listPath, String itemName) {
  final list = sampleAt(sample, listPath);
  final name = itemName.trim().isEmpty ? 'item' : itemName.trim();
  if (list is! List || list.isEmpty) return const [];
  final first = list.first;
  if (first is! Map) return [name];
  return [for (final p in samplePaths(first)) '$name.$p'];
}

/// The value a row produced in a preview [result] (a dotted target is a nested path): `(found, value)`, with found false when the field is
/// absent (for example dropped by "Omit nulls") or [result] is not an object.
(bool, Object?) resultAt(Object? result, String target) {
  Object? cur = result;
  for (final part in target.trim().split('.')) {
    if (cur is! Map || !cur.containsKey(part)) return (false, null);
    cur = cur[part];
  }
  return (true, cur);
}

/// One line for a value in a row: text quoted, small structures as JSON, large ones summarised, everything cut at [max] characters.
String previewValue(Object? v, {int max = 80}) {
  String clip(String s) => s.length <= max ? s : '${s.substring(0, max - 1)}…';
  if (v == null) return 'null';
  if (v is String) return clip(_quote(v));
  if (v is List) {
    final t = _compact(v);
    return t.length <= max ? t : '[${v.length} items]';
  }
  if (v is Map) {
    final t = _compact(v);
    return t.length <= max ? t : '{${v.length} fields}';
  }
  return clip('$v');
}

String _quote(String s) => '"${s.replaceAll(r'\', r'\\').replaceAll('"', r'\"').replaceAll('\n', r'\n')}"';

String _compact(Object? v) {
  if (v == null) return 'null';
  if (v is String) return _quote(v);
  if (v is List) return '[${v.map(_compact).join(', ')}]';
  if (v is Map) return '{${v.entries.map((e) => '${e.key}: ${_compact(e.value)}').join(', ')}}';
  return '$v';
}

/// Targets the schema requires that have no source yet (they are left out of the spec, so the output would miss them).
List<String> unmappedRequired(List<MappingRow> rows) => [
      for (final r in rows)
        if (r.required && r.target.trim().isNotEmpty && r.value.trim().isEmpty) r.target.trim(),
    ];

/// Rows for the properties of a JSON Schema: objects are flattened into dotted targets, arrays of objects become list rows with the item's
/// properties as children, `$ref` to `#/$defs/..` or `#/definitions/..` is followed. When [sample] is given, a field whose name matches exactly
/// one sample path (by last segment, ignoring case) is already connected to it. Returns null when [schema] has no properties to map.
/// Bounded: depth 4, 200 rows.
List<MappingRow>? rowsFromSchema(Object? schema, {Object? sample}) {
  if (schema is! Map) return null;
  var count = 0;
  Map? resolve(Object? s, int hops) {
    if (s is! Map) return null;
    final ref = s[r'$ref'];
    if (ref is String && hops < 8) {
      final m = RegExp(r'^#/(\$defs|definitions)/([^/]+)$').firstMatch(ref);
      final defs = m == null ? null : schema[m.group(1)];
      return defs is Map ? resolve(defs[m!.group(2)], hops + 1) : null;
    }
    return s;
  }

  String describe(Map p, bool req) {
    final t = p['type'];
    final parts = [if (t is String) t else if (t is List) t.join(' | '), if (p['format'] is String) p['format'] as String, if (req) 'required'];
    final d = p['description'];
    return [parts.join(', '), if (d is String && d.trim().isNotEmpty) d.trim()].where((x) => x.isNotEmpty).join(' - ');
  }

  String? unique(List<String> paths, String name) {
    final hits = paths.where((p) => !p.contains('[') && p.split('.').last.toLowerCase() == name.toLowerCase()).toList();
    return hits.length == 1 ? hits.single : null;
  }

  List<MappingRow> walk(Map obj, String prefix, int depth, List<String> paths, Object? scope) {
    final out = <MappingRow>[];
    final props = obj['properties'];
    if (props is! Map) return out;
    final required = obj['required'] is List ? (obj['required'] as List).map((e) => '$e').toSet() : <String>{};
    for (final e in props.entries) {
      if (count >= 200) break;
      final name = '${e.key}';
      final p = resolve(e.value, 0);
      if (p == null) continue;
      final req = required.contains(name);
      final type = p['type'];
      final props2 = p['properties'];
      if ((type == 'object' || props2 is Map) && props2 is Map && depth < 4) {
        out.addAll(walk(p, '$prefix$name.', depth + 1, paths, scope));
        continue;
      }
      final row = MappingRow(target: '$prefix$name', value: unique(paths, name) ?? '')..hint = describe(p, req)..required = req;
      count++;
      final items = type == 'array' ? resolve(p['items'], 0) : null;
      if (items != null && items['properties'] is Map && depth < 4) {
        row.kind = SourceKind.list;
        final listPath = row.value;
        final itemScope = listPath.isEmpty ? null : sampleAt(scope, listPath);
        final first = itemScope is List && itemScope.isNotEmpty ? itemScope.first : null;
        final itemSample = {'item': first};
        row.children.addAll(walk(items, '', depth + 1, first is Map ? [for (final q in samplePaths(first)) 'item.$q'] : const [], itemSample));
      }
      out.add(row);
    }
    return out;
  }

  final root = resolve(schema, 0);
  if (root == null) return null;
  final rows = walk(root, '', 0, samplePaths(sample), sample);
  return rows.isEmpty ? null : rows;
}
