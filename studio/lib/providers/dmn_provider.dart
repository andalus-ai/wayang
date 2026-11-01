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

class DmnEditorState {
  const DmnEditorState({
    this.loading = true,
    this.table,
    this.dirty = false,
    this.saving = false,
    this.report,
    this.publishedVersion,
    this.error,
  });
  final bool loading;
  final DmnDecisionTable? table;
  final bool dirty;
  final bool saving;

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
      report: clearReport ? null : (report ?? this.report),
      publishedVersion: clearPublished ? null : (publishedVersion ?? this.publishedVersion),
      error: clearError ? null : (error ?? this.error),
    );
  }
}

/// Owns the currently-open decision table's edit buffer (so cell edits feel
/// instant) and flushes to the repository on save.
class DmnController extends StateNotifier<DmnEditorState> {
  DmnController(this._repo, String tableId) : super(const DmnEditorState()) {
    _load(tableId);
  }

  final WayangRepository _repo;

  Future<void> _load(String tableId) async {
    state = state.copyWith(loading: true, clearError: true);
    try {
      final table = await _repo.loadTable(tableId);
      state = state.copyWith(loading: false, table: table, dirty: false);
    } catch (e) {
      state = state.copyWith(loading: false, error: '$e');
    }
  }

  /// Every edit goes through here so stale validation/publish state is dropped.
  void _edit(DmnDecisionTable next) {
    state = state.copyWith(table: next, dirty: true, clearReport: true, clearPublished: true);
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
    _edit(t.copyWith(name: name));
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

  void setInputEntry(String ruleId, int columnIndex, String value) {
    final t = state.table;
    if (t == null) return;
    final rule = t.rules.firstWhere((r) => r.id == ruleId);
    _edit(t.updateRule(rule.withInputEntry(columnIndex, value)));
  }

  void setOutputEntry(String ruleId, int columnIndex, String value) {
    final t = state.table;
    if (t == null) return;
    final rule = t.rules.firstWhere((r) => r.id == ruleId);
    _edit(t.updateRule(rule.withOutputEntry(columnIndex, value)));
  }

  void setRuleAnnotation(String ruleId, String annotation) {
    final t = state.table;
    if (t == null) return;
    final rule = t.rules.firstWhere((r) => r.id == ruleId);
    _edit(t.updateRule(rule.copyWith(annotation: annotation)));
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
    if (!await save()) return;
    try {
      final v = await _repo.publish(DefinitionKind.decision, state.table!.id);
      state = state.copyWith(publishedVersion: v.version, clearError: true);
    } catch (e) {
      state = state.copyWith(error: e is WayangApiException ? e.fullMessage : '$e');
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
