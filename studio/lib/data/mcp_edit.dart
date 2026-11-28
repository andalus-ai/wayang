/// Client-side checks for the MCP import dialog, mirroring the server's rules.
library;

final _id = RegExp(r'^[A-Za-z0-9_-]{1,60}$');

/// Why [id] cannot name a new MCP server (letters, digits, `_`, `-`; 1-60; not taken), or null.
String? mcpServerIdProblem(String id, Iterable<String> existing) {
  final t = id.trim();
  if (t.isEmpty) return 'Give the server an id.';
  if (!_id.hasMatch(t)) return "Use letters, digits, '_' and '-' only, up to 60 characters.";
  if (existing.contains(t)) return 'A server with this id exists already; pick another id.';
  return null;
}

/// The first of [patterns] that is not a valid regular expression, described for the user, or null when all compile.
String? pathPatternProblem(Iterable<String> patterns) {
  for (final p in patterns) {
    try {
      RegExp(p);
    } on FormatException {
      return "'$p' is not a valid regular expression.";
    }
  }
  return null;
}
