import 'dart:convert';

import 'step_address.dart';

/// One kind of pipeline step the builder can add. The catalog is `assets/pipeline_steps.json`; a Java test builds every
/// template with the real pipeline loader, so a template here is always a step the server accepts.
class StepKind {
  const StepKind({
    required this.type,
    required this.label,
    required this.group,
    required this.icon,
    required this.description,
    required this.template,
  });

  factory StepKind.fromJson(Map<String, dynamic> j) => StepKind(
        type: j['type'] as String,
        label: j['label'] as String,
        group: j['group'] as String,
        icon: j['icon'] as String,
        description: j['description'] as String,
        template: Map<String, dynamic>.from(j['template'] as Map),
      );

  final String type;
  final String label;
  final String group;
  final String icon;
  final String description;
  final Map<String, dynamic> template;

  /// A fresh copy, so editing one step never changes the catalog or another step.
  Map<String, dynamic> instantiate() => deepCopy(template) as Map<String, dynamic>;
}

List<StepKind> parseCatalog(String json) =>
    [for (final e in jsonDecode(json) as List) StepKind.fromJson(Map<String, dynamic>.from(e as Map))];

Object? deepCopy(Object? v) {
  if (v is Map) return {for (final e in v.entries) e.key as String: deepCopy(e.value)};
  if (v is List) return [for (final e in v) deepCopy(e)];
  return v;
}

/// The steps of a pipeline document `{"steps": [...]}`, or null when [text] is not one (bad JSON, no list, a non-object step).
/// The builder then shows a hint instead of guessing, and the JSON view stays the source of truth.
List<Map<String, dynamic>>? stepsOf(String text) {
  try {
    final doc = jsonDecode(text);
    if (doc is! Map) return null;
    final steps = doc['steps'];
    if (steps == null) return <Map<String, dynamic>>[];
    if (steps is! List) return null;
    final out = <Map<String, dynamic>>[];
    for (final s in steps) {
      if (s is! Map) return null;
      out.add(Map<String, dynamic>.from(s));
    }
    return out;
  } on FormatException {
    return null;
  }
}

/// Pipeline JSON for [steps], keeping any other top-level keys of [previous] (the builder only edits `steps`).
String encodeSteps(List<Map<String, dynamic>> steps, {String? previous}) {
  Map<String, dynamic> doc = <String, dynamic>{};
  if (previous != null) {
    try {
      final p = jsonDecode(previous);
      if (p is Map) doc = Map<String, dynamic>.from(p);
    } on FormatException {
      // start from an empty document
    }
  }
  doc['steps'] = steps;
  return const JsonEncoder.withIndent('  ').convert(doc);
}

/// A named nested execution lane already represented inside an EIP step's JSON.
class NestedStepGroup {
  const NestedStepGroup({
    required this.label,
    required this.steps,
    this.condition,
    this.address,
  });

  final String label;
  final List<Map<String, dynamic>> steps;
  final String? condition;
  final StepAddress? address;
}

List<Map<String, dynamic>> _stepObjects(Object? value) {
  if (value is! List) return const [];
  if (value.any((item) => item is! Map)) return const [];
  return [for (final item in value) Map<String, dynamic>.from(item as Map)];
}

/// Projects known nested EIP structures into addressable execution lanes.
///
/// Supports the actual catalog shapes: choice.branches/otherwise,
/// switch.cases/default, multicast.branches, loadBalance.targets,
/// routingSlip.stages, and steps/fallback wrappers (guard, retry, loop,
/// circuitBreaker). It does not mutate or normalize the source JSON.
List<NestedStepGroup> nestedStepGroups(
  Map<String, dynamic> step, {StepAddress? parentAddress}) {
  final groups = <NestedStepGroup>[];

  void addList(String label, Object? value, {String? condition, StepAddress? address}) {
    final children = _stepObjects(value);
    if (value is List) {
      groups.add(NestedStepGroup(
        label: label,
        steps: children,
        condition: condition,
        address: value.every((item) => item is Map) ? address : null,
      ));
    }
  }

  void addMap(String label, Object? value, String field) {
    if (value is! Map) return;
    for (final entry in value.entries) {
      addList('$label · ${entry.key}', entry.value,
        address: parentAddress?.field(field).field(entry.key));
    }
  }

  final branches = step['branches'];
  if (branches is List) {
    var index = 0;
    for (final branch in branches) {
      index++;
      if (branch is Map) {
        addList(
          'Branch $index',
          branch['steps'],
          condition: branch['when']?.toString(),
          address: parentAddress?.field('branches').at(index - 1).field('steps'),
        );
      }
    }
  } else if (branches is Map) {
    addMap('Branch', branches, 'branches');
  }

  final cases = step['cases'];
  if (cases is Map) addMap('Case', cases, 'cases');

  final targets = step['targets'];
  if (targets is Map) addMap('Target', targets, 'targets');

  final stages = step['stages'];
  if (stages is Map) addMap('Stage', stages, 'stages');

  // These keys represent independent nested sequences in the catalog.
  addList('Otherwise', step['otherwise'], address: parentAddress?.field('otherwise'));
  addList('Default', step['default'], address: parentAddress?.field('default'));
  addList('Main steps', step['steps'], address: parentAddress?.field('steps'));
  addList('Fallback', step['fallback'], address: parentAddress?.field('fallback'));

  return groups;
}

/// Human-readable route metadata for router/recipient-list style steps.
List<String> routeSummaries(Map<String, dynamic> step) {
  final routes = step['routes'];
  if (routes is! List) return const [];
  final summaries = <String>[];
  for (final route in routes) {
    if (route is! Map) continue;
    final target = route['to']?.toString() ?? 'unnamed target';
    final condition = route['when']?.toString();
    summaries.add(condition == null || condition.isEmpty
        ? target
        : '$target  when $condition');
  }
  final otherwise = step['otherwise'];
  if (otherwise is String && otherwise.isNotEmpty) {
    summaries.add('otherwise → $otherwise');
  }
  return summaries;
}

/// A copy of [list] with the item at [index] moved by [delta] places; unchanged when that would leave the list.
List<T> moved<T>(List<T> list, int index, int delta) {
  final to = index + delta;
  if (index < 0 || index >= list.length || to < 0 || to >= list.length) return List<T>.of(list);
  final out = List<T>.of(list);
  final item = out.removeAt(index);
  out.insert(to, item);
  return out;
}

/// A step's one-line summary: its most telling settings, and how many nested steps it holds.
String describeStep(Map<String, dynamic> step) {
  const keys = ['format', 'connector', 'when', 'on', 'key', 'by', 'name', 'items', 'slip', 'action', 'size', 'attempts', 'perSecond', 'ms'];
  final parts = <String>[];
  for (final k in keys) {
    final v = step[k];
    if (v is String || v is num || v is bool) {
      parts.add('$k: $v');
      if (parts.length == 2) break;
    }
  }
  var nested = 0;
  for (final k in ['steps', 'handler', 'fallback', 'otherwise', 'default']) {
    final v = step[k];
    if (v is List) nested += v.length;
  }
  for (final k in ['branches', 'cases', 'stages', 'targets']) {
    final v = step[k];
    if (v is Map) {
      for (final b in v.values) {
        if (b is List) nested += b.length;
      }
    } else if (v is List) {
      for (final b in v) {
        if (b is Map && b['steps'] is List) nested += (b['steps'] as List).length;
      }
    }
  }
  if (nested > 0) parts.add('$nested nested step${nested == 1 ? '' : 's'}');
  var text = parts.join(' · ');
  if (text.length > 90) text = '${text.substring(0, 87)}...';
  return text;
}

/// Parses one step edited as JSON. Throws [FormatException] with a message fit for the user.
Map<String, dynamic> parseStep(String text) {
  final dynamic v;
  try {
    v = jsonDecode(text);
  } on FormatException catch (e) {
    throw FormatException('Not valid JSON: ${e.message}');
  }
  if (v is! Map) throw const FormatException('A step is a JSON object');
  if (v['type'] is! String || (v['type'] as String).isEmpty) throw const FormatException('A step needs a "type"');
  return Map<String, dynamic>.from(v);
}
