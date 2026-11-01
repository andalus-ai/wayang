/// The hit policies the Wayang engine implements. [wireName] is the server's `HitPolicy` name.
enum DmnHitPolicy {
  unique,
  first,
  any,
  ruleOrder,
  collect;

  String get wireName => switch (this) {
        DmnHitPolicy.unique => 'UNIQUE',
        DmnHitPolicy.first => 'FIRST',
        DmnHitPolicy.any => 'ANY',
        DmnHitPolicy.ruleOrder => 'RULE_ORDER',
        DmnHitPolicy.collect => 'COLLECT',
      };

  /// The spelling DMN 1.3 XML uses.
  String get xmlName => this == DmnHitPolicy.ruleOrder ? 'RULE ORDER' : wireName;

  static DmnHitPolicy? tryFromWire(String v) {
    for (final p in DmnHitPolicy.values) {
      if (p.wireName == v.toUpperCase()) return p;
    }
    return null;
  }

  /// The single-letter glyph DMN tools render in the table's top-left cell.
  String get glyph => switch (this) {
        DmnHitPolicy.unique => 'U',
        DmnHitPolicy.first => 'F',
        DmnHitPolicy.any => 'A',
        DmnHitPolicy.ruleOrder => 'R',
        DmnHitPolicy.collect => 'C',
      };

  String get label => switch (this) {
        DmnHitPolicy.unique => 'Unique',
        DmnHitPolicy.first => 'First',
        DmnHitPolicy.any => 'Any',
        DmnHitPolicy.ruleOrder => 'Rule order',
        DmnHitPolicy.collect => 'Collect',
      };

  String get description => switch (this) {
        DmnHitPolicy.unique => 'At most one rule can match; overlapping rules are an error.',
        DmnHitPolicy.first => 'Rules are evaluated in order; the first match wins.',
        DmnHitPolicy.any => 'Multiple rules may match if they all produce the same output.',
        DmnHitPolicy.ruleOrder => 'All matches are returned, in rule order.',
        DmnHitPolicy.collect => 'All matches are combined; choose an aggregator (sum, min, max, count) or get the list.',
      };
}

/// A DMN "simple type" for an input/output column.
enum DmnType { string, number, boolean, date }

extension DmnTypeX on DmnType {
  String get label => switch (this) {
        DmnType.string => 'string',
        DmnType.number => 'number',
        DmnType.boolean => 'boolean',
        DmnType.date => 'date',
      };
}

/// One input or output column in a decision table.
class DmnColumn {
  const DmnColumn({required this.id, required this.label, this.expression = '', this.type = DmnType.string});
  final String id;
  final String label;

  /// For input columns: the FEEL expression evaluated against the incoming
  /// context (e.g. `applicant.age`, `order.amount`). Unused for outputs.
  final String expression;
  final DmnType type;

  DmnColumn copyWith({String? label, String? expression, DmnType? type}) =>
      DmnColumn(id: id, label: label ?? this.label, expression: expression ?? this.expression, type: type ?? this.type);
}

/// One row of a decision table: one unary test per input column, one expression per output column.
///
/// Entries are Wayang expressions, so **text must be quoted** (`"gold"`, output `"high"`); unquoted words are read
/// as variable names. Input-entry syntax (checked against the engine in `docs/contract/decision-tier.json`):
/// - `-` matches anything
/// - `"gold"` matches a string exactly
/// - `<500`, `<=500`, `>500`, `>=500`, `=500` numeric comparisons
/// - `[100..500]` inclusive numeric range
/// - `"gold","platinum"` matches any listed value
class DmnRule {
  const DmnRule({required this.id, required this.inputEntries, required this.outputEntries, this.annotation = ''});
  final String id;
  final List<String> inputEntries;
  final List<String> outputEntries;
  final String annotation;

  DmnRule copyWith({List<String>? inputEntries, List<String>? outputEntries, String? annotation}) => DmnRule(
        id: id,
        inputEntries: inputEntries ?? this.inputEntries,
        outputEntries: outputEntries ?? this.outputEntries,
        annotation: annotation ?? this.annotation,
      );

  DmnRule withInputEntry(int index, String value) {
    final next = [...inputEntries];
    while (next.length <= index) {
      next.add('-');
    }
    next[index] = value;
    return copyWith(inputEntries: next);
  }

  DmnRule withOutputEntry(int index, String value) {
    final next = [...outputEntries];
    while (next.length <= index) {
      next.add('');
    }
    next[index] = value;
    return copyWith(outputEntries: next);
  }
}

/// An immutable DMN decision table.
class DmnDecisionTable {
  const DmnDecisionTable({
    required this.id,
    this.name = 'Untitled decision',
    this.hitPolicy = DmnHitPolicy.unique,
    this.collectAggregator,
    this.inputs = const [],
    this.outputs = const [],
    this.rules = const [],
    this.revision,
    this.projectId,
  });

  final String id;
  final String name;
  final DmnHitPolicy hitPolicy;

  /// Only meaningful when [hitPolicy] is [DmnHitPolicy.collect] — how the
  /// (possibly many) matching numeric outputs are combined: `SUM`, `MIN`,
  /// `MAX`, `COUNT`, or `null` for the raw unordered list.
  final String? collectAggregator;
  final List<DmnColumn> inputs;
  final List<DmnColumn> outputs;
  final List<DmnRule> rules;

  /// Draft revision this table was loaded at; sent as `If-Match` when saving (null = not stored yet).
  final int? revision;

  /// Project the definition belongs to (`studio.projectId`), if any.
  final String? projectId;

  DmnDecisionTable copyWith({
    String? name,
    DmnHitPolicy? hitPolicy,
    String? collectAggregator,
    bool clearAggregator = false,
    List<DmnColumn>? inputs,
    List<DmnColumn>? outputs,
    List<DmnRule>? rules,
    int? revision,
    String? projectId,
    bool clearProject = false,
  }) {
    return DmnDecisionTable(
      id: id,
      name: name ?? this.name,
      hitPolicy: hitPolicy ?? this.hitPolicy,
      collectAggregator: clearAggregator ? null : (collectAggregator ?? this.collectAggregator),
      inputs: inputs ?? this.inputs,
      outputs: outputs ?? this.outputs,
      rules: rules ?? this.rules,
      revision: revision ?? this.revision,
      projectId: clearProject ? null : (projectId ?? this.projectId),
    );
  }

  DmnDecisionTable addRule(DmnRule rule) => copyWith(rules: [...rules, rule]);
  DmnDecisionTable removeRule(String ruleId) => copyWith(rules: rules.where((r) => r.id != ruleId).toList());
  DmnDecisionTable updateRule(DmnRule rule) =>
      copyWith(rules: rules.map((r) => r.id == rule.id ? rule : r).toList());

  DmnDecisionTable addInputColumn(DmnColumn column) => copyWith(
        inputs: [...inputs, column],
        rules: rules.map((r) => r.copyWith(inputEntries: [...r.inputEntries, '-'])).toList(),
      );

  DmnDecisionTable addOutputColumn(DmnColumn column) => copyWith(
        outputs: [...outputs, column],
        rules: rules.map((r) => r.copyWith(outputEntries: [...r.outputEntries, ''])).toList(),
      );

  DmnDecisionTable removeInputColumn(String columnId) {
    final index = inputs.indexWhere((c) => c.id == columnId);
    if (index < 0) return this;
    return copyWith(
      inputs: inputs.where((c) => c.id != columnId).toList(),
      rules: rules
          .map((r) => r.copyWith(inputEntries: [for (var i = 0; i < r.inputEntries.length; i++) if (i != index) r.inputEntries[i]]))
          .toList(),
    );
  }

  DmnDecisionTable removeOutputColumn(String columnId) {
    final index = outputs.indexWhere((c) => c.id == columnId);
    if (index < 0) return this;
    return copyWith(
      outputs: outputs.where((c) => c.id != columnId).toList(),
      rules: rules
          .map((r) => r.copyWith(outputEntries: [for (var i = 0; i < r.outputEntries.length; i++) if (i != index) r.outputEntries[i]]))
          .toList(),
    );
  }
}

/// Result of evaluating a decision table against a set of input values.
class DmnEvaluationResult {
  const DmnEvaluationResult({required this.matchedRuleIds, required this.outputs, this.note = ''});
  final List<String> matchedRuleIds;

  /// Output column label → resolved value (or list of values, for
  /// COLLECT/RULE_ORDER/OUTPUT_ORDER hit policies with multiple matches).
  final Map<String, dynamic> outputs;
  final String note;
}

/// A pair of rules whose input entries could both match the same input —
/// only meaningful for the UNIQUE hit policy, where overlap is an error.
class DmnOverlapWarning {
  const DmnOverlapWarning({required this.ruleIdA, required this.ruleIdB, required this.columnLabel});
  final String ruleIdA;
  final String ruleIdB;
  final String columnLabel;
}

/// Heuristically detects pairs of rules that could both match the same
/// input — a real DMN engine's overlap check is a full constraint solve
/// over each input's value domain; this is a practical approximation that
/// catches the common cases: identical entries, a wildcard (`-`) sharing a
/// column with any other entry, and overlapping numeric ranges/comparisons.
List<DmnOverlapWarning> detectRuleOverlaps(DmnDecisionTable table) {
  final warnings = <DmnOverlapWarning>[];
  for (var i = 0; i < table.rules.length; i++) {
    for (var j = i + 1; j < table.rules.length; j++) {
      final a = table.rules[i];
      final b = table.rules[j];
      var allColumnsOverlap = true;
      String? overlappingColumn;
      for (var c = 0; c < table.inputs.length; c++) {
        final ea = c < a.inputEntries.length ? a.inputEntries[c].trim() : '-';
        final eb = c < b.inputEntries.length ? b.inputEntries[c].trim() : '-';
        if (!_entriesMayOverlap(ea, eb)) {
          allColumnsOverlap = false;
          break;
        }
        overlappingColumn ??= table.inputs[c].label;
      }
      if (allColumnsOverlap && table.inputs.isNotEmpty) {
        warnings.add(DmnOverlapWarning(ruleIdA: a.id, ruleIdB: b.id, columnLabel: overlappingColumn ?? ''));
      }
    }
  }
  return warnings;
}

bool _entriesMayOverlap(String a, String b) {
  if (a == '-' || b == '-') return true;
  if (a == b) return true;
  final rangeA = _parseRange(a);
  final rangeB = _parseRange(b);
  if (rangeA != null && rangeB != null) {
    return rangeA.$1 <= rangeB.$2 && rangeB.$1 <= rangeA.$2;
  }
  return false;
}

(double, double)? _parseRange(String entry) {
  final range = RegExp(r'^[\[\(]\s*(-?\d+(\.\d+)?)\s*\.\.\s*(-?\d+(\.\d+)?)\s*[\]\)]$').firstMatch(entry);
  if (range != null) {
    return (double.parse(range.group(1)!), double.parse(range.group(3)!));
  }
  final cmp = RegExp(r'^(<=|>=|<|>)\s*(-?\d+(\.\d+)?)$').firstMatch(entry);
  if (cmp != null) {
    final value = double.parse(cmp.group(2)!);
    return switch (cmp.group(1)) {
      '<' => (double.negativeInfinity, value),
      '<=' => (double.negativeInfinity, value),
      '>' => (value, double.infinity),
      '>=' => (value, double.infinity),
      _ => null,
    };
  }
  return null;
}

extension DmnDecisionTableXml on DmnDecisionTable {
  static String _xmlEscape(String s) =>
      s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');

  /// Serializes this table to a DMN 1.3-shaped XML document (a
  /// `decisionTable` inside a `decision` element) — hand-written for the
  /// same reason as the BPMN exporter: full control over the output shape,
  /// no dependency on an unverified third-party XML package.
  String toDmnXml() {
    final buffer = StringBuffer();
    buffer.writeln('<?xml version="1.0" encoding="UTF-8"?>');
    buffer.writeln('<dmn:definitions xmlns:dmn="https://www.omg.org/spec/DMN/20191111/MODEL/" '
        'id="Definitions_$id" name="${_xmlEscape(name)}" namespace="https://wayang.kayys.tech/dmn">');
    buffer.writeln('  <dmn:decision id="Decision_$id" name="${_xmlEscape(name)}">');
    buffer.writeln('    <dmn:decisionTable id="DecisionTable_$id" hitPolicy="${hitPolicy.xmlName}"'
        '${hitPolicy == DmnHitPolicy.collect && collectAggregator != null ? ' aggregation="$collectAggregator"' : ''}>');
    for (final col in inputs) {
      buffer.writeln('      <dmn:input id="Input_${col.id}" label="${_xmlEscape(col.label)}">');
      buffer.writeln('        <dmn:inputExpression typeRef="${col.type.label}"><dmn:text>${_xmlEscape(col.expression)}</dmn:text></dmn:inputExpression>');
      buffer.writeln('      </dmn:input>');
    }
    for (final col in outputs) {
      buffer.writeln('      <dmn:output id="Output_${col.id}" label="${_xmlEscape(col.label)}" typeRef="${col.type.label}"/>');
    }
    for (final rule in rules) {
      buffer.writeln('      <dmn:rule id="Rule_${rule.id}">');
      for (final entry in rule.inputEntries) {
        buffer.writeln('        <dmn:inputEntry><dmn:text>${_xmlEscape(entry)}</dmn:text></dmn:inputEntry>');
      }
      for (final entry in rule.outputEntries) {
        buffer.writeln('        <dmn:outputEntry><dmn:text>${_xmlEscape(entry)}</dmn:text></dmn:outputEntry>');
      }
      if (rule.annotation.isNotEmpty) {
        buffer.writeln('        <dmn:annotation>${_xmlEscape(rule.annotation)}</dmn:annotation>');
      }
      buffer.writeln('      </dmn:rule>');
    }
    buffer.writeln('    </dmn:decisionTable>');
    buffer.writeln('  </dmn:decision>');
    buffer.writeln('</dmn:definitions>');
    return buffer.toString();
  }
}
