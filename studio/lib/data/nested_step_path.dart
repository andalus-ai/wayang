/// Immutable address of a step inside the pipeline JSON.
class StepPath {
  const StepPath(this.indices);
  final List<int> indices;

  StepPath child(int index) => StepPath([...indices, index]);

  @override
  bool operator ==(Object other) =>
      other is StepPath && _same(indices, other.indices);

  @override
  int get hashCode => Object.hashAll(indices);

  static bool _same(List<int> a, List<int> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}

/// Reads a nested step by numeric sequence path. The first index addresses
/// document.steps; each subsequent index addresses a nested sequence.
Map<String, dynamic>? stepAtPath(
  Map<String, dynamic> document,
  StepPath path,
) {
  if (path.indices.isEmpty) return null;
  Object? sequence = document['steps'];
  Map<String, dynamic>? current;
  for (final index in path.indices) {
    if (sequence is! List || index < 0 || index >= sequence.length) return null;
    final value = sequence[index];
    if (value is! Map) return null;
    current = Map<String, dynamic>.from(value);
    sequence = current['steps'];
  }
  return current;
}

/// Replaces a step at [path], preserving unrelated document and step fields.
/// Returns null when the path does not identify an existing step.
Map<String, dynamic>? replaceStepAtPath(
  Map<String, dynamic> document,
  StepPath path,
  Map<String, dynamic> replacement,
) {
  if (path.indices.isEmpty) return null;
  final copy = deepCopy(document) as Map<String, dynamic>;
  Object? sequence = copy['steps'];
  for (var depth = 0; depth < path.indices.length; depth++) {
    final index = path.indices[depth];
    if (sequence is! List || index < 0 || index >= sequence.length) return null;
    if (depth == path.indices.length - 1) {
      sequence[index] = deepCopy(replacement);
      return copy;
    }
    final item = sequence[index];
    if (item is! Map || item['steps'] is! List) return null;
    sequence = item['steps'];
  }
  return null;
}

/// Inserts a child into an existing nested sequence addressed by its parent path.
/// Empty parent path inserts into document.steps. Returns null on invalid paths.
Map<String, dynamic>? insertStepAtPath(
  Map<String, dynamic> document,
  StepPath parentPath,
  int index,
  Map<String, dynamic> step,
) {
  final copy = deepCopy(document) as Map<String, dynamic>;
  Object? sequence = copy['steps'];
  for (final parentIndex in parentPath.indices) {
    if (sequence is! List || parentIndex < 0 || parentIndex >= sequence.length) return null;
    final parent = sequence[parentIndex];
    if (parent is! Map || parent['steps'] is! List) return null;
    sequence = parent['steps'];
  }
  if (sequence is! List || index < 0 || index > sequence.length) return null;
  sequence.insert(index, deepCopy(step));
  return copy;
}

/// Removes the step at [path]. The root sequence may not be removed if that
/// would leave the document without a steps list; an empty list is permitted.
Map<String, dynamic>? removeStepAtPath(
  Map<String, dynamic> document,
  StepPath path,
) {
  if (path.indices.isEmpty) return null;
  final copy = deepCopy(document) as Map<String, dynamic>;
  Object? sequence = copy['steps'];
  for (var depth = 0; depth < path.indices.length; depth++) {
    final index = path.indices[depth];
    if (sequence is! List || index < 0 || index >= sequence.length) return null;
    if (depth == path.indices.length - 1) {
      sequence.removeAt(index);
      return copy;
    }
    final parent = sequence[index];
    if (parent is! Map || parent['steps'] is! List) return null;
    sequence = parent['steps'];
  }
  return null;
}

/// Moves a step within its containing sequence without changing other JSON.
Map<String, dynamic>? moveStepAtPath(
  Map<String, dynamic> document,
  StepPath path,
  int delta,
) {
  if (path.indices.isEmpty) return null;
  final copy = deepCopy(document) as Map<String, dynamic>;
  Object? sequence = copy['steps'];
  for (var depth = 0; depth < path.indices.length; depth++) {
    final index = path.indices[depth];
    if (sequence is! List || index < 0 || index >= sequence.length) return null;
    if (depth == path.indices.length - 1) {
      final destination = index + delta;
      if (destination < 0 || destination >= sequence.length) return copy;
      final item = sequence.removeAt(index);
      sequence.insert(destination, item);
      return copy;
    }
    final parent = sequence[index];
    if (parent is! Map || parent['steps'] is! List) return null;
    sequence = parent['steps'];
  }
  return null;
}
