/// Deep-copies JSON-compatible maps and lists without changing scalar values.
///
/// Keeping this primitive independent of the pipeline model lets both legacy
/// and typed step-address helpers preserve immutable edit semantics.
Object? deepCopyJson(Object? value) {
  if (value is Map) {
    return {for (final entry in value.entries) entry.key as String: deepCopyJson(entry.value)};
  }
  if (value is List) return [for (final item in value) deepCopyJson(item)];
  return value;
}
