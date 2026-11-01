import '../models/dmn.dart';
import '../models/wayang.dart';
import 'bpmn_mapper.dart' show DocumentFormatException;

/// Lower-case identifier safe to use as a decision/output name in expressions (`score.risk_class`).
String dmnIdentifier(String label, {String fallback = 'value'}) {
  var s = label.trim().toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), '_').replaceAll(RegExp(r'^_+|_+$'), '');
  if (s.isEmpty) s = fallback;
  if (RegExp(r'^[0-9]').hasMatch(s)) s = '_$s';
  return s;
}

/// Output column names, made unique ("risk", "risk_2", ...) in column order.
List<String> dmnOutputNames(List<DmnColumn> outputs) {
  final seen = <String>{};
  final names = <String>[];
  for (var i = 0; i < outputs.length; i++) {
    final base = dmnIdentifier(outputs[i].label, fallback: 'output_${i + 1}');
    var name = base;
    var n = 2;
    while (!seen.add(name)) {
      name = '${base}_$n';
      n++;
    }
    names.add(name);
  }
  return names;
}

/// Decision table -> the server's decision model document (one `table` decision).
///
/// The decision is named after the first output ("risk"), so a process reads `score.risk` (or `score.result`).
/// Column labels, types and rule ids are UI data and go to `studio.meta`.
Map<String, dynamic> dmnToDocument(DmnDecisionTable t) {
  final outNames = dmnOutputNames(t.outputs);
  final decisionName = outNames.isNotEmpty ? outNames.first : dmnIdentifier(t.name, fallback: 'decision');
  return {
    'id': t.id,
    'decisions': [
      {
        'name': decisionName,
        'type': 'table',
        'hitPolicy': t.hitPolicy.wireName,
        'aggregator': t.hitPolicy == DmnHitPolicy.collect ? (t.collectAggregator ?? 'NONE') : 'NONE',
        'inputs': [
          for (var i = 0; i < t.inputs.length; i++)
            t.inputs[i].expression.trim().isEmpty ? dmnIdentifier(t.inputs[i].label, fallback: 'input_${i + 1}') : t.inputs[i].expression.trim(),
        ],
        'outputs': outNames,
        'rules': [
          for (final r in t.rules)
            {
              'when': List<String>.from(r.inputEntries),
              'then': List<String>.from(r.outputEntries),
              if (r.annotation.isNotEmpty) 'note': r.annotation,
            },
        ],
      },
    ],
    'studio': {
      if (t.projectId != null) 'projectId': t.projectId,
      'meta': {
        'title': t.name,
        'columns': {
          'inputs': [for (final c in t.inputs) {'id': c.id, 'label': c.label, 'type': c.type.label}],
          'outputs': [for (final c in t.outputs) {'id': c.id, 'label': c.label, 'type': c.type.label}],
        },
        'ruleIds': [for (final r in t.rules) r.id],
      },
    },
  };
}

DmnType _type(Object? v) => DmnType.values.firstWhere((t) => t.label == '$v', orElse: () => DmnType.string);

/// The server's decision model document -> table. Only single-table models are editable here; a model with
/// several decisions (or a literal decision) is refused with a clear message instead of being half-shown.
DmnDecisionTable dmnFromDocument(Map<String, dynamic> doc, {int? revision}) {
  final decisions = asList(doc['decisions']);
  final id = (doc['id'] as String?) ?? '';
  if (decisions.length != 1) {
    throw DocumentFormatException('Decision model "$id" has ${decisions.length} decisions; '
        'the table editor edits models with exactly one decision table.');
  }
  final d = asMap(decisions.first);
  if ((d['type'] ?? 'table') != 'table') {
    throw DocumentFormatException('Decision "${d['name']}" is a ${d['type']} decision; the table editor only edits tables.');
  }
  final policy = DmnHitPolicy.tryFromWire('${d['hitPolicy'] ?? 'UNIQUE'}');
  if (policy == null) {
    throw DocumentFormatException('Unknown hit policy "${d['hitPolicy']}".');
  }
  final studio = asMap(doc['studio']);
  final meta = asMap(studio['meta']);
  final cols = asMap(meta['columns']);
  final inExprs = [for (final e in asList(d['inputs'])) '$e'];
  final outNames = [for (final e in asList(d['outputs'])) '$e'];

  List<DmnColumn> columns(String key, List<String> fallbackLabels, {required bool isInput}) {
    final stored = asList(cols[key]);
    return [
      for (var i = 0; i < fallbackLabels.length; i++)
        () {
          final m = i < stored.length ? asMap(stored[i]) : <String, dynamic>{};
          return DmnColumn(
            id: (m['id'] as String?) ?? '${isInput ? 'in' : 'out'}${i + 1}',
            label: (m['label'] as String?) ?? fallbackLabels[i],
            expression: isInput ? fallbackLabels[i] : '',
            type: _type(m['type']),
          );
        }(),
    ];
  }

  final ruleIds = [for (final r in asList(meta['ruleIds'])) '$r'];
  final rules = <DmnRule>[];
  var ri = 0;
  for (final r in asList(d['rules'])) {
    final m = asMap(r);
    rules.add(DmnRule(
      id: ri < ruleIds.length ? ruleIds[ri] : 'r${ri + 1}',
      inputEntries: [for (final e in asList(m['when'])) '$e'],
      outputEntries: [for (final e in asList(m['then'])) '$e'],
      annotation: (m['note'] as String?) ?? '',
    ));
    ri++;
  }

  final agg = '${d['aggregator'] ?? 'NONE'}'.toUpperCase();
  return DmnDecisionTable(
    id: id,
    name: (meta['title'] as String?) ?? (d['name'] as String?) ?? id,
    hitPolicy: policy,
    collectAggregator: policy == DmnHitPolicy.collect && agg != 'NONE' ? agg : null,
    inputs: columns('inputs', inExprs, isInput: true),
    outputs: columns('outputs', outNames, isInput: false),
    rules: rules,
    revision: revision,
    projectId: studio['projectId'] as String?,
  );
}
