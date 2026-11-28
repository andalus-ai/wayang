/// Client-side check for project names (kept short, like the other named things in the workspace).
library;

const kMaxProjectName = 80;

/// Why [name] cannot be used, or null. [others] are the names of the other projects (case-insensitive duplicates are refused
/// so the list stays unambiguous).
String? projectNameProblem(String name, Iterable<String> others) {
  final t = name.trim();
  if (t.isEmpty) return 'Enter a name.';
  if (t.length > kMaxProjectName) return 'At most $kMaxProjectName characters.';
  final lower = t.toLowerCase();
  if (others.any((o) => o.trim().toLowerCase() == lower)) return 'Another project already has this name.';
  return null;
}
