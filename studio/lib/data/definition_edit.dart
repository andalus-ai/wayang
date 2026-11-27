/// Small pure helpers of the agent/pipeline definition list: id rules (the server's: 1-100 of letters, digits, `_`, `.`, `-`) and list filtering.
library;

import '../models/wayang.dart';

final _id = RegExp(r'^[A-Za-z0-9_.-]{1,100}$');

/// Why [id] cannot be used for a new definition, or null when it can. [existing] are the ids already there (compared as typed).
String? definitionIdProblem(String id, Iterable<String> existing) {
  final t = id.trim();
  if (t.isEmpty) return 'Enter an id.';
  if (t.length > 100) return 'At most 100 characters.';
  if (!_id.hasMatch(t)) return "Use letters, digits, '_', '.' and '-' only (no spaces).";
  if (existing.contains(t)) return 'There is already one with this id; open it from the list instead.';
  return null;
}

/// Definitions whose name or id contains every word of [query] (case-insensitive); an empty query keeps all.
List<DefinitionSummary> filterDefinitions(List<DefinitionSummary> all, String query) {
  final words = query.toLowerCase().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).toList();
  if (words.isEmpty) return all;
  return [
    for (final d in all)
      if (words.every('${d.name} ${d.id}'.toLowerCase().contains)) d,
  ];
}

/// True when [text] differs from the [baseline] it was loaded or last saved as.
bool definitionDirty(String? id, String text, String baseline) => id != null && text != baseline;
