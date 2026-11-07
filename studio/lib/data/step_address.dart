/// A typed JSON path segment used to address nested EIP execution lanes.
///
/// Keys address object properties (for example `branches`, `cases`, or `steps`);
/// indices address list entries. Keeping both forms explicit prevents a map key
/// such as `paid` from being confused with a positional list index.
class StepAddressSegment {
  const StepAddressSegment._(this.index, this.key);

  const StepAddressSegment.index(int value) : this._(value, null);
  const StepAddressSegment.key(String value) : this._(null, value);

  final int? index;
  final String? key;

  bool get isIndex => index != null;

  @override
  bool operator ==(Object other) => other is StepAddressSegment &&
      index == other.index && key == other.key;

  @override
  int get hashCode => Object.hash(index, key);

  @override
  String toString() => isIndex ? '[$index]' : '.$key';
}

/// Immutable address of a step in a pipeline JSON document.
///
/// Example: `steps[0].branches[1].steps[2]` is represented by
/// `StepAddress.root(0).field('branches').at(1).field('steps').at(2)`.
class StepAddress {
  StepAddress(Iterable<StepAddressSegment> segments)
      : segments = List.unmodifiable(segments);

  factory StepAddress.root(int index) =>
      StepAddress([StepAddressSegment.key('steps'), StepAddressSegment.index(index)]);

  final List<StepAddressSegment> segments;

  StepAddress field(String name) =>
      StepAddress([...segments, StepAddressSegment.key(name)]);

  StepAddress at(int index) =>
      StepAddress([...segments, StepAddressSegment.index(index)]);

  StepAddress child(int index) => field('steps').at(index);

  @override
  bool operator ==(Object other) {
    if (other is! StepAddress || segments.length != other.segments.length) return false;
    for (var i = 0; i < segments.length; i++) {
      if (segments[i] != other.segments[i]) return false;
    }
    return true;
  }

  @override
  int get hashCode => Object.hashAll(segments);

  @override
  String toString() => segments.map((segment) => segment.toString()).join();
}

Object? _valueAtAddress(Object? root, StepAddress address) {
  Object? current = root;
  for (final segment in address.segments) {
    if (segment.isIndex) {
      if (current is! List) return null;
      final index = segment.index!;
      if (index < 0 || index >= current.length) return null;
      current = current[index];
    } else {
      if (current is! Map || !current.containsKey(segment.key)) return null;
      current = current[segment.key];
    }
  }
  return current;
}

bool _assignAtAddress(Object root, StepAddress address, Object? value) {
  if (address.segments.isEmpty) return false;
  final parentAddress = StepAddress(address.segments.take(address.segments.length - 1));
  final parent = _valueAtAddress(root, parentAddress);
  final last = address.segments.last;
  if (last.isIndex) {
    if (parent is! List) return false;
    final index = last.index!;
    if (index < 0 || index >= parent.length) return false;
    parent[index] = value;
    return true;
  }
  if (parent is! Map || !parent.containsKey(last.key)) return false;
  parent[last.key] = value;
  return true;
}

Map<String, dynamic>? stepAtAddress(
  Map<String, dynamic> document, StepAddress address) {
  final value = _valueAtAddress(document, address);
  return value is Map ? Map<String, dynamic>.from(value) : null;
}

/// Replaces an addressed step. Returns null for a stale or malformed address.
Map<String, dynamic>? replaceStepAtAddress(
  Map<String, dynamic> document, StepAddress address, Map<String, dynamic> replacement) {
  final copy = deepCopy(document) as Map<String, dynamic>;
  if (_valueAtAddress(copy, address) is! Map) return null;
  if (!_assignAtAddress(copy, address, deepCopy(replacement))) return null;
  return copy;
}

/// Inserts into an addressed list. `sequenceAddress` must resolve to a list.
Map<String, dynamic>? insertStepAtAddress(
  Map<String, dynamic> document, StepAddress sequenceAddress, int index,
  Map<String, dynamic> step) {
  final copy = deepCopy(document) as Map<String, dynamic>;
  final sequence = _valueAtAddress(copy, sequenceAddress);
  if (sequence is! List || index < 0 || index > sequence.length) return null;
  sequence.insert(index, deepCopy(step));
  return copy;
}

/// Removes an addressed list item. Returns null for invalid addresses.
Map<String, dynamic>? removeStepAtAddress(
  Map<String, dynamic> document, StepAddress address) {
  if (address.segments.isEmpty || !address.segments.last.isIndex) return null;
  final parent = StepAddress(address.segments.take(address.segments.length - 1));
  final copy = deepCopy(document) as Map<String, dynamic>;
  final sequence = _valueAtAddress(copy, parent);
  final index = address.segments.last.index!;
  if (sequence is! List || index < 0 || index >= sequence.length) return null;
  sequence.removeAt(index);
  return copy;
}

/// Moves an addressed item within its containing list; no-op at list boundaries.
Map<String, dynamic>? moveStepAtAddress(
  Map<String, dynamic> document, StepAddress address, int delta) {
  if (address.segments.isEmpty || !address.segments.last.isIndex) return null;
  final parent = StepAddress(address.segments.take(address.segments.length - 1));
  final copy = deepCopy(document) as Map<String, dynamic>;
  final sequence = _valueAtAddress(copy, parent);
  final index = address.segments.last.index!;
  if (sequence is! List || index < 0 || index >= sequence.length) return null;
  final destination = index + delta;
  if (destination < 0 || destination >= sequence.length) return copy;
  final value = sequence.removeAt(index);
  sequence.insert(destination, value);
  return copy;
}
