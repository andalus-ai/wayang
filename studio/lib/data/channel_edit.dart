/// Client-side checks for the Channels screen, mirroring the server's rules so mistakes show before the request.
library;

final _triggerId = RegExp(r'^[A-Za-z0-9_-]{1,64}$');
final _connectionId = RegExp(r'^[A-Za-z0-9_-]{1,60}$');
final _secretName = RegExp(r'^[A-Za-z0-9_.-]{1,100}$');

/// Why [id] cannot name a trigger (letters, digits, `_`, `-`; 1-64), or null. [existing] ids are rejected.
String? triggerIdProblem(String id, Iterable<String> existing) {
  final t = id.trim();
  if (t.isEmpty) return 'Give the trigger an id.';
  if (!_triggerId.hasMatch(t)) return "Use letters, digits, '_' and '-' only, up to 64 characters.";
  if (existing.contains(t)) return 'A trigger with this id already exists.';
  return null;
}

/// Why the Gmail connection form cannot be submitted, or null. Checks id, client id, secret name and scopes.
String? connectionProblem({
  required String id,
  required String clientId,
  required String secretName,
  required int scopeCount,
  Iterable<String> existing = const [],
}) {
  final i = id.trim();
  if (i.isEmpty) return 'Give the connection an id.';
  if (!_connectionId.hasMatch(i)) return "Connection id: letters, digits, '_' and '-' only, up to 60 characters.";
  if (existing.contains(i)) return 'A connection with this id already exists.';
  if (clientId.trim().isEmpty) return 'Enter the Google OAuth client id.';
  final s = secretName.trim();
  if (s.isEmpty) return 'Enter the name of the secret holding the client secret.';
  if (!_secretName.hasMatch(s)) return "Secret name: letters, digits, '_', '.' and '-' only.";
  if (scopeCount == 0) return 'Pick at least one scope.';
  return null;
}
