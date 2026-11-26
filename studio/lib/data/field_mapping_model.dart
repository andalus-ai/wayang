/// Model behind the visual field-mapping builder. The JSON spec stays the source of truth (see wayang-integration `Mapper`); this file turns rows
/// into a spec and back. Transformations are the deterministic FEEL built-ins of wayang-expression (no regex, no I/O), applied inside-out:
/// a row "name <- trim, upper case" is the expression `upper case(trim(name))`. Pure Dart, no Flutter.
library;

/// What a row's source column holds.
enum SourceKind { field, constant, template, expression }

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

  /// A source path, a literal, template text with `${...}`, or a raw expression, by [kind].
  String value;
  final List<TransformStep> transforms;
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
    default:
      return expressionFor(row);
  }
}

/// Problems that stop a spec from being built; empty when the rows are fine. Rows with neither target nor source are ignored (a fresh row).
List<String> rowProblems(List<MappingRow> rows) {
  final out = <String>[];
  final seen = <String>{};
  for (var i = 0; i < rows.length; i++) {
    final r = rows[i];
    final n = i + 1;
    final blank = r.target.trim().isEmpty && r.value.trim().isEmpty;
    if (blank) continue;
    final target = r.target.trim();
    if (target.isEmpty) {
      out.add('Row $n has no target field.');
      continue;
    }
    if (!target.split('.').every((p) => p.isNotEmpty)) out.add('Row $n: "$target" is not a valid field path.');
    if (!seen.add(target)) out.add('Row $n: "$target" is mapped twice.');
    if (r.value.trim().isEmpty) {
      out.add('Row $n ("$target") has no source.');
    } else {
      try {
        expressionFor(r);
      } on FormatException catch (e) {
        out.add('Row $n: ${e.message}');
      }
    }
  }
  final targets = seen.toList();
  for (final a in targets) {
    for (final b in targets) {
      if (a != b && b.startsWith('$a.')) out.add('"$a" is a value and also a parent of "$b"; keep one of them.');
    }
  }
  return out;
}

/// The mapping spec for [rows] (blank rows skipped), or null when [rowProblems] is not empty.
Map<String, dynamic>? specFromRows(List<MappingRow> rows) {
  if (rowProblems(rows).isNotEmpty) return null;
  final spec = <String, dynamic>{};
  for (final r in rows) {
    if (r.target.trim().isEmpty && r.value.trim().isEmpty) continue;
    spec[r.target.trim()] = specValue(r);
  }
  return spec;
}

/// Rows for an existing spec, or null when it uses something the builder cannot show (nested objects, lists, `$each`, `$if`, ...), in which
/// case the JSON editor is the place to change it. A transformed source comes back as a raw expression: nothing is lost, it is just not split
/// into steps again.
List<MappingRow>? rowsFromSpec(Object? spec) {
  if (spec is! Map) return null;
  final rows = <MappingRow>[];
  for (final e in spec.entries) {
    final key = '${e.key}';
    final v = e.value;
    if (v is String) {
      rows.add(MappingRow(target: key, kind: _identifier.hasMatch(v.split('.').first) && _isPath(v) ? SourceKind.field : SourceKind.expression, value: v));
    } else if (v is Map && v.length == 1 && v.containsKey(r'$const') && (v[r'$const'] is! Map) && (v[r'$const'] is! List)) {
      rows.add(MappingRow(target: key, kind: SourceKind.constant, value: v[r'$const'] == null ? 'null' : '${v[r'$const']}'));
    } else if (v is Map && v.length == 1 && v[r'$template'] is String) {
      rows.add(MappingRow(target: key, kind: SourceKind.template, value: v[r'$template'] as String));
    } else {
      return null;
    }
  }
  return rows;
}

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
