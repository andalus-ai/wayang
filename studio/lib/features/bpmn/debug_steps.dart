/// Stepping through the nodes a run executed, in order. Pure so it is testable without a widget.
class DebugSteps {
  const DebugSteps(this.executed, [this.index = -1]);

  /// Nodes that did real work, in the order they ran.
  final List<String> executed;

  /// The step shown, or -1 for "the whole path".
  final int index;

  bool get all => index < 0 || executed.isEmpty;
  int get length => executed.length;

  /// The node to look at now, or null when showing the whole path.
  String? get current => all ? null : executed[index.clamp(0, executed.length - 1)];

  /// Nodes to mark on the canvas: the whole path, or everything up to and including the current step.
  Set<String> get marked => all ? executed.toSet() : executed.sublist(0, index.clamp(0, executed.length - 1) + 1).toSet();

  DebugSteps next() => executed.isEmpty ? this : DebugSteps(executed, all ? 0 : (index + 1).clamp(0, executed.length - 1));
  DebugSteps previous() => executed.isEmpty ? this : DebugSteps(executed, all ? executed.length - 1 : (index - 1).clamp(0, executed.length - 1));
  DebugSteps showAll() => DebugSteps(executed);

  /// The same position over a longer path (a live run adds nodes); a finished position is kept, "all" stays "all".
  DebugSteps withExecuted(List<String> now) => DebugSteps(now, all ? -1 : index.clamp(0, now.isEmpty ? 0 : now.length - 1));
}
