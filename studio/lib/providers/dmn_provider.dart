import 'package:flutter/foundation.dart' show visibleForTesting;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/utils.dart';
import '../data/api_client.dart';
import '../data/wayang_repository.dart';
import '../models/dmn.dart';
import '../models/wayang.dart';
import 'app_providers.dart';

/// Summaries of the decision definitions (id + name); a table is loaded in full when opened.
final decisionTablesProvider = FutureProvider.autoDispose<List<DefinitionSummary>>((ref) {
  return ref.watch(definitionsProvider(DefinitionKind.decision).future);
});

final activeDecisionTableIdProvider = StateProvider<String?>((ref) => null);

/// Heuristic pairwise overlap warnings for the currently-open table —
/// recomputed client-side (cheap, pure function) rather than round-tripping
/// to the repository on every keystroke.
final decisionTableOverlapsProvider = Provider.family<List<DmnOverlapWarning>, DmnDecisionTable?>((ref, table) {
  if (table == null || table.hitPolicy != DmnHitPolicy.unique) return const [];
  return detectRuleOverlaps(table);
});

/// The text typed in the rule filter box of a table; kept per table while the editor is open.
final dmnRuleFilterProvider = StateProvider.autoDispose.family<String, String>((ref, tableId) => '');

class DmnEditorState {
  const DmnEditorState({
    this.loading = true,
    this.table,
    this.dirty = false,
    this.saving = false,
    this.canUndo = false,
    this.canRedo = false,
    this.epoch = 0,
    this.report,
    this.publishedVersion,
    this.error,
  });
  final bool loading;
  final DmnDecisionTable? table;
  final bool dirty;
  final bool saving;
  final bool canUndo;
  final bool canRedo;

  /// Bumped by undo/redo so the grid's text fields (which only read their initial value) are rebuilt with the restored text.
  final int epoch;

  /// Last server-side validation of the table (null = not validated since the last edit).
  final ValidationReport? report;

  /// Set after a successful publish, until the next edit.
  final int? publishedVersion;
  final String? error;

  DmnEditorState copyWith({
    bool? loading,
    DmnDecisionTable? table,
    bool? dirty,
    bool? saving,
    bool? canUndo,
    bool? canRedo,
    int? epoch,
    ValidationReport? report,
    bool clearReport = false,
    int? publishedVersion,
    bool clearPublished = false,
    String? error,
    bool clearError = false,
  }) {
    return DmnEditorState(
      loading: loading ?? this.loading,
      table: table ?? this.table,
      dirty: dirty ?? this.dirty,
      saving: saving ?? this.saving,
      canUndo: canUndo ?? this.canUndo,
      canRedo: canRedo ?? this.canRedo,
      epoch: epoch ?? this.epoch,
      report: clearReport ? null : (report ?? this.report),
      publishedVersion: clearPublished ? null : (publishedVersion ?? this.publishedVersion),
      error: clearError ? null : (error ?? this.error),
    );
  }
}

/// Owns the currently-open decision table's edit buffer (so cell edits feel
/// instant) and flushes to the repository on save.
class DmnController extends StateNotifier<DmnEditorState> {
  DmnController(this._repo, String tableId, {DateTime Function()? now})
      : _now = now ?? DateTime.now,
        super(const DmnEditorState()) {
    if (tableId.isNotEmpty) _load(tableId);
  }

  final WayangRepository _repo;
  final DateTime Function() _now;

  static const _maxHistory = 100;
  static const _coalesceWindow = Duration(milliseconds: 1000);
  final List<DmnDecisionTable> _undo = [];
  final List<DmnDecisionTable> _redo = [];
  String? _lastKey;
  DateTime? _lastAt;

  /// Puts [table] in the editor without touching the server (tests only).
  @visibleForTesting
  void seed(DmnDecisionTable table) => state = state.copyWith(loading: false, table: table, dirty: false);

  Future<void> _load(String tableId) async {
    state = state.copyWith(loading: true, clearError: true);
    try {
      final table = await _repo.loadTable(tableId);
      _undo.clear();
      _redo.clear();
      state = state.copyWith(loading: false, table: table, dirty: false, canUndo: false, canRedo: false);
    } catch (e) {
      state = state.copyWith(loading: false, error: '$e');
    }
  }

  /// Every edit goes through here so stale validation/publish state is dropped.
  ///
  /// Each edit is one undo step, except that typing into the same cell (same [coalesce] key) within a second is one step, so undo does not
  /// walk back letter by letter.
  void _edit(DmnDecisionTable next, {String? coalesce}) {
    final before = state.table;
    final at = _now();
    final merge = coalesce != null && coalesce == _lastKey && _lastAt != null && at.difference(_lastAt!) < _coalesceWindow;
    if (before != null && !merge) {
      _undo.add(before);
      if (_undo.length > _maxHistory) _undo.removeAt(0);
    }
    _redo.clear();
    _lastKey = coalesce;
    _lastAt = at;
    state = state.copyWith(table: next, dirty: true, clearReport: true, clearPublished: true, canUndo: _undo.isNotEmpty, canRedo: false);
  }

  void undo() {
    final t = state.table;
    if (t == null || _undo.isEmpty) return;
    _redo.add(t);
    _lastKey = null;
    state = state.copyWith(table: _undo.removeLast(), dirty: true, clearReport: true, clearPublished: true, canUndo: _undo.isNotEmpty, canRedo: true, epoch: state.epoch + 1);
  }

  void redo() {
    final t = state.table;
    if (t == null || _redo.isEmpty) return;
    _undo.add(t);
    _lastKey = null;
    state = state.copyWith(table: _redo.removeLast(), dirty: true, clearReport: true, clearPublished: true, canUndo: true, canRedo: _redo.isNotEmpty, epoch: state.epoch + 1);
  }

  void setHitPolicy(DmnHitPolicy policy) {
    final t = state.table;
    if (t == null) return;
    _edit(t.copyWith(hitPolicy: policy));
  }

  void setCollectAggregator(String? aggregator) {
    final t = state.table;
    if (t == null) return;
    _edit(t.copyWith(collectAggregator: aggregator, clearAggregator: aggregator == null));
  }

  void renameTable(String name) {
    final t = state.table;
    if (t == null) return;
    _edit(t.copyWith(name: name), coalesce: 'name');
  }

  void addInputColumn(String label) {
    final t = state.table;
    if (t == null) return;
    _edit(t.addInputColumn(DmnColumn(id: 'in_${DateTime.now().microsecondsSinceEpoch}', label: label)));
  }

  void addOutputColumn(String label) {
    final t = state.table;
    if (t == null) return;
    _edit(t.addOutputColumn(DmnColumn(id: 'out_${DateTime.now().microsecondsSinceEpoch}', label: label)));
  }

  void removeInputColumn(String columnId) {
    final t = state.table;
    if (t == null) return;
    _edit(t.removeInputColumn(columnId));
  }

  void removeOutputColumn(String columnId) {
    final t = state.table;
    if (t == null) return;
    _edit(t.removeOutputColumn(columnId));
  }

  void addRule() {
    final t = state.table;
    if (t == null) return;
    final rule = DmnRule(
      id: 'rule_${DateTime.now().microsecondsSinceEpoch}',
      inputEntries: List.filled(t.inputs.length, '-'),
      outputEntries: List.filled(t.outputs.length, ''),
    );
    _edit(t.addRule(rule));
  }

  void removeRule(String ruleId) {
    final t = state.table;
    if (t == null) return;
    _edit(t.removeRule(ruleId));
  }

  /// Appends [rules] (already shaped for this table) as one undo step.
  void appendRules(List<DmnRule> rules) {
    final t = state.table;
    if (t == null || rules.isEmpty) return;
    _edit(t.copyWith(rules: [...t.rules, ...rules]));
  }

  /// A fresh rule id, unique within the editor session.
  String newRuleId() => 'rule_${_now().microsecondsSinceEpoch}_${_idCounter++}';
  int _idCounter = 0;

  /// Copies a rule right below itself (new id, same entries).
  void duplicateRule(String ruleId) {
    final t = state.table;
    if (t == null) return;
    final i = t.rules.indexWhere((r) => r.id == ruleId);
    if (i < 0) return;
    final src = t.rules[i];
    final copy = DmnRule(id: 'rule_${_now().microsecondsSinceEpoch}', inputEntries: [...src.inputEntries], outputEntries: [...src.outputEntries], annotation: src.annotation);
    _edit(t.copyWith(rules: [...t.rules.sublist(0, i + 1), copy, ...t.rules.sublist(i + 1)]));
  }

  /// Moves a rule up (-1) or down (+1). The order matters for hit policies such as first and rule order.
  void moveRule(String ruleId, int delta) {
    final t = state.table;
    if (t == null) return;
    final i = t.rules.indexWhere((r) => r.id == ruleId);
    final j = i + delta;
    if (i < 0 || j < 0 || j >= t.rules.length) return;
    final next = [...t.rules];
    final r = next.removeAt(i);
    next.insert(j, r);
    _edit(t.copyWith(rules: next));
  }

  void setInputEntry(String ruleId, int columnIndex, String value) {
    final t = state.table;
    if (t == null) return;
    final rule = t.rules.firstWhere((r) => r.id == ruleId);
    _edit(t.updateRule(rule.withInputEntry(columnIndex, value)), coalesce: 'in:$ruleId:$columnIndex');
  }

  void setOutputEntry(String ruleId, int columnIndex, String value) {
    final t = state.table;
    if (t == null) return;
    final rule = t.rules.firstWhere((r) => r.id == ruleId);
    _edit(t.updateRule(rule.withOutputEntry(columnIndex, value)), coalesce: 'out:$ruleId:$columnIndex');
  }

  void setRuleAnnotation(String ruleId, String annotation) {
    final t = state.table;
    if (t == null) return;
    final rule = t.rules.firstWhere((r) => r.id == ruleId);
    _edit(t.updateRule(rule.copyWith(annotation: annotation)), coalesce: 'note:$ruleId');
  }

  Future<bool> save() async {
    final t = state.table;
    if (t == null) return false;
    state = state.copyWith(saving: true, clearError: true);
    try {
      final saved = await _repo.saveTable(t);
      state = state.copyWith(saving: false, dirty: false, table: saved);
      return true;
    } catch (e) {
      state = state.copyWith(saving: false, error: e is WayangApiException ? e.fullMessage : '$e');
      return false;
    }
  }

  Future<void> validate() async {
    final t = state.table;
    if (t == null) return;
    try {
      state = state.copyWith(report: await _repo.validateTable(t), clearError: true);
    } catch (e) {
      state = state.copyWith(error: e is WayangApiException ? e.fullMessage : '$e');
    }
  }

  /// Saves the edit buffer, then freezes it as the next immutable version.
  Future<void> publish() async {
    if (state.saving || !await save()) return; // a second click while a save/publish is running does nothing
    state = state.copyWith(saving: true); // keeps Save and Publish disabled (spinner on) until the server answers
    try {
      final v = await _repo.publish(DefinitionKind.decision, state.table!.id);
      state = state.copyWith(saving: false, publishedVersion: v.version, clearError: true);
    } catch (e) {
      state = state.copyWith(saving: false, error: e is WayangApiException ? e.fullMessage : '$e');
    }
  }
}

final dmnControllerProvider = StateNotifierProvider.autoDispose.family<DmnController, DmnEditorState, String>((ref, tableId) {
  final repo = ref.watch(repositoryProvider);
  return DmnController(repo, tableId);
});

/// New/duplicate/rename/delete for the table picker — mirrors
/// [BpmnDiagramActions]; a list-level concern separate from [DmnController]
/// which only edits one table.
class DmnTableActions {
  DmnTableActions(this.ref);
  final Ref ref;

  Future<void> createNew(String name) async {
    final repo = ref.read(repositoryProvider);
    final id = IdGen.next('decision');
    final table = DmnDecisionTable(
      id: id,
      name: name,
      projectId: ref.read(activeProjectIdProvider),
      inputs: const [DmnColumn(id: 'in1', label: 'Input', expression: 'input', type: DmnType.string)],
      outputs: const [DmnColumn(id: 'out1', label: 'Result', type: DmnType.string)],
      rules: const [DmnRule(id: 'r1', inputEntries: ['-'], outputEntries: ['""'])],
    );
    await repo.saveTable(table);
    ref.invalidate(decisionTablesProvider);
    ref.read(activeDecisionTableIdProvider.notifier).state = id;
  }

  Future<void> duplicate(DmnDecisionTable source) async {
    final repo = ref.read(repositoryProvider);
    final id = IdGen.next('decision');
    final copy = DmnDecisionTable(
      id: id,
      name: '${source.name} (copy)',
      hitPolicy: source.hitPolicy,
      collectAggregator: source.collectAggregator,
      inputs: source.inputs,
      outputs: source.outputs,
      rules: source.rules,
      projectId: source.projectId,
    );
    await repo.saveTable(copy);
    ref.invalidate(decisionTablesProvider);
    ref.read(activeDecisionTableIdProvider.notifier).state = id;
  }

  Future<void> rename(DmnDecisionTable table, String newName) async {
    await ref.read(repositoryProvider).saveTable(table.copyWith(name: newName));
    ref.invalidate(decisionTablesProvider);
  }

  /// Deletes the *draft*. Published versions are immutable and stay (a process may reference them).
  Future<void> deleteDraft(String id, List<DefinitionSummary> remaining) async {
    await ref.read(repositoryProvider).deleteDraft(DefinitionKind.decision, id);
    ref.invalidate(decisionTablesProvider);
    final others = remaining.where((t) => t.id != id).toList();
    ref.read(activeDecisionTableIdProvider.notifier).state = others.isNotEmpty ? others.first.id : null;
  }
}

final dmnTableActionsProvider = Provider((ref) => DmnTableActions(ref));
