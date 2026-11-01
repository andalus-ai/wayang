import 'dart:async';

import 'package:flutter/material.dart' hide DiagnosticLevel;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/utils.dart';
import '../data/api_client.dart';
import '../data/bpmn_mapper.dart';
import '../data/wayang_repository.dart';
import '../models/bpmn.dart';
import '../models/wayang.dart';
import 'app_providers.dart';

@immutable
class BpmnEditorState {
  const BpmnEditorState({
    this.loading = true,
    this.diagram = BpmnDiagram.empty,
    this.selectedNodeId,
    this.selectedFlowId,
    this.canUndo = false,
    this.canRedo = false,
    this.dirty = false,
    this.saving = false,
    this.diagnostics = const [],
    this.validating = false,
    this.connectingFromNodeId,
    this.connectingCursor,
    this.errorMessage,
    this.publishedVersion,
    this.highlight = const {},
  });

  final bool loading;
  final BpmnDiagram diagram;
  final String? selectedNodeId;
  final String? selectedFlowId;
  final bool canUndo;
  final bool canRedo;
  final bool dirty;
  final bool saving;
  final List<BpmnDiagnostic> diagnostics;
  final bool validating;
  final String? connectingFromNodeId;
  final Offset? connectingCursor;
  final String? errorMessage;

  /// Set after a successful publish, until the next edit.
  final int? publishedVersion;

  /// Node ids to mark on the canvas (e.g. the path a run took); empty = none.
  final Set<String> highlight;

  bool get isConnecting => connectingFromNodeId != null;
  BpmnNode? get selectedNode => selectedNodeId == null ? null : diagram.node(selectedNodeId!);
  BpmnFlow? get selectedFlow => selectedFlowId == null ? null : diagram.flows[selectedFlowId];
  int get errorCount => diagnostics.where((d) => d.severity == DiagnosticLevel.error).length;
  int get warningCount => diagnostics.where((d) => d.severity == DiagnosticLevel.warning).length;

  BpmnEditorState copyWith({
    bool? loading,
    BpmnDiagram? diagram,
    String? selectedNodeId,
    bool clearSelectedNode = false,
    String? selectedFlowId,
    bool clearSelectedFlow = false,
    bool? canUndo,
    bool? canRedo,
    bool? dirty,
    bool? saving,
    List<BpmnDiagnostic>? diagnostics,
    bool? validating,
    String? connectingFromNodeId,
    bool clearConnecting = false,
    Offset? connectingCursor,
    String? errorMessage,
    bool clearError = false,
    int? publishedVersion,
    bool clearPublished = false,
    Set<String>? highlight,
  }) {
    return BpmnEditorState(
      loading: loading ?? this.loading,
      diagram: diagram ?? this.diagram,
      selectedNodeId: clearSelectedNode ? null : (selectedNodeId ?? this.selectedNodeId),
      selectedFlowId: clearSelectedFlow ? null : (selectedFlowId ?? this.selectedFlowId),
      canUndo: canUndo ?? this.canUndo,
      canRedo: canRedo ?? this.canRedo,
      dirty: dirty ?? this.dirty,
      saving: saving ?? this.saving,
      diagnostics: diagnostics ?? this.diagnostics,
      validating: validating ?? this.validating,
      connectingFromNodeId: clearConnecting ? null : (connectingFromNodeId ?? this.connectingFromNodeId),
      connectingCursor: clearConnecting ? null : (connectingCursor ?? this.connectingCursor),
      errorMessage: clearError ? null : (errorMessage ?? this.errorMessage),
      publishedVersion: clearPublished ? null : (publishedVersion ?? this.publishedVersion),
      highlight: highlight ?? this.highlight,
    );
  }
}

class BpmnController extends StateNotifier<BpmnEditorState> {
  BpmnController(this._repo, this._diagramId) : super(BpmnEditorState(loading: _diagramId.isNotEmpty)) {
    if (_diagramId.isNotEmpty) _load();
  }

  final WayangRepository _repo;
  final String _diagramId;

  final List<BpmnDiagram> _undoStack = [];
  final List<BpmnDiagram> _redoStack = [];

  Future<void> _load() async {
    state = state.copyWith(loading: true, clearError: true);
    try {
      final diagram = await _repo.loadDiagram(_diagramId);
      state = state.copyWith(loading: false, diagram: diagram);
      unawaited(validate());
    } catch (e) {
      state = state.copyWith(loading: false, errorMessage: 'Cannot open process: ${_message(e)}');
    }
  }

  static String _message(Object e) => e is WayangApiException ? e.fullMessage : '$e';

  void _checkpoint() {
    _undoStack.add(state.diagram);
    if (_undoStack.length > 100) _undoStack.removeAt(0);
    _redoStack.clear();
    state = state.copyWith(canUndo: true, canRedo: false, dirty: true, clearPublished: true);
  }

  void selectNode(String id) => state = state.copyWith(selectedNodeId: id, clearSelectedFlow: true);
  void selectFlow(String id) => state = state.copyWith(selectedFlowId: id, clearSelectedNode: true);
  void clearSelection() => state = state.copyWith(clearSelectedNode: true, clearSelectedFlow: true);

  String addNode(BpmnElementType type, double x, double y, {String? laneId}) {
    _checkpoint();
    final id = IdGen.next('bn');
    final node = BpmnNode(id: id, type: type, x: x, y: y, label: type.label, laneId: laneId);
    state = state.copyWith(diagram: state.diagram.addNode(node));
    selectNode(id);
    unawaited(validate());
    return id;
  }

  void removeSelected() {
    if (state.selectedNodeId != null) {
      _checkpoint();
      state = state.copyWith(diagram: state.diagram.removeNode(state.selectedNodeId!));
      clearSelection();
      unawaited(validate());
    } else if (state.selectedFlowId != null) {
      _checkpoint();
      state = state.copyWith(diagram: state.diagram.disconnect(state.selectedFlowId!));
      clearSelection();
      unawaited(validate());
    }
  }

  void dragNode(String id, double x, double y) {
    state = state.copyWith(diagram: state.diagram.moveNode(id, x, y));
  }

  void endDragNode(String id, {String? laneId, bool clearLane = false}) {
    final node = state.diagram.node(id);
    if (node == null) return;
    _checkpoint();
    state = state.copyWith(diagram: state.diagram.moveNode(id, node.x, node.y, laneId: laneId, clearLane: clearLane));
  }

  void updateNodeLabel(String id, String label) {
    final node = state.diagram.node(id);
    if (node == null) return;
    _checkpoint();
    state = state.copyWith(diagram: state.diagram.updateNode(node.copyWith(label: label)));
  }

  void updateNodeProperties(String id, Map<String, dynamic> properties) {
    final node = state.diagram.node(id);
    if (node == null) return;
    _checkpoint();
    state = state.copyWith(diagram: state.diagram.updateNode(node.copyWith(properties: properties)));
    unawaited(validate());
  }

  void startConnection(String fromNodeId, Offset cursor) =>
      state = state.copyWith(connectingFromNodeId: fromNodeId, connectingCursor: cursor);

  void updateConnectionCursor(Offset cursor) {
    if (!state.isConnecting) return;
    state = state.copyWith(connectingCursor: cursor);
  }

  void cancelConnection() => state = state.copyWith(clearConnecting: true);

  void completeConnection(String toNodeId) {
    final fromId = state.connectingFromNodeId;
    state = state.copyWith(clearConnecting: true);
    if (fromId == null || fromId == toNodeId) return;
    _checkpoint();
    final id = IdGen.next('bf');
    state = state.copyWith(diagram: state.diagram.connect(BpmnFlow(id: id, from: fromId, to: toNodeId)));
    selectFlow(id);
    unawaited(validate());
  }

  void setFlowCondition(String flowId, String condition) {
    final flow = state.diagram.flows[flowId];
    if (flow == null) return;
    _checkpoint();
    state = state.copyWith(diagram: state.diagram.updateFlow(flow.copyWith(condition: condition)));
  }

  void setFlowDefault(String flowId, bool isDefault) {
    final flow = state.diagram.flows[flowId];
    if (flow == null) return;
    _checkpoint();
    state = state.copyWith(diagram: state.diagram.updateFlow(flow.copyWith(isDefault: isDefault)));
  }

  void addLane() {
    _checkpoint();
    final order = state.diagram.lanes.length;
    state = state.copyWith(diagram: state.diagram.addLane(BpmnLane(id: IdGen.next('lane'), name: 'Lane ${order + 1}', order: order)));
  }

  /// Sets or clears (null) the process's MCP exposure settings.
  void setMcp(Map<String, dynamic>? mcp) {
    _checkpoint();
    state = state.copyWith(diagram: mcp == null ? state.diagram.copyWith(clearMcp: true) : state.diagram.copyWith(mcp: mcp));
  }

  void renameLane(String laneId, String name) {
    _checkpoint();
    final lanes = state.diagram.lanes.map((l) => l.id == laneId ? l.copyWith(name: name) : l).toList();
    state = state.copyWith(diagram: state.diagram.copyWith(lanes: lanes));
  }

  void removeLane(String laneId) {
    _checkpoint();
    state = state.copyWith(diagram: state.diagram.removeLane(laneId));
  }

  void undo() {
    if (_undoStack.isEmpty) return;
    _redoStack.add(state.diagram);
    final previous = _undoStack.removeLast();
    state = state.copyWith(diagram: previous, canUndo: _undoStack.isNotEmpty, canRedo: true, clearSelectedNode: true, clearSelectedFlow: true);
    unawaited(validate());
  }

  void redo() {
    if (_redoStack.isEmpty) return;
    _undoStack.add(state.diagram);
    final next = _redoStack.removeLast();
    state = state.copyWith(diagram: next, canUndo: true, canRedo: _redoStack.isNotEmpty, clearSelectedNode: true, clearSelectedFlow: true);
    unawaited(validate());
  }

  /// Validates on the server (structure, flows, reference checks and each node's config schema).
  Future<void> validate() async {
    state = state.copyWith(validating: true);
    try {
      final report = await _repo.validateDiagram(state.diagram);
      state = state.copyWith(
        diagnostics: [
          for (final i in report.items)
            BpmnDiagnostic(
              severity: i.isError ? DiagnosticLevel.error : DiagnosticLevel.warning,
              message: i.message,
              nodeId: i.elementId,
            ),
        ],
        validating: false,
      );
    } catch (e) {
      state = state.copyWith(validating: false, errorMessage: 'Validation failed: ${_message(e)}');
    }
  }

  Future<bool> save() async {
    state = state.copyWith(saving: true, clearError: true);
    try {
      final saved = await _repo.saveDiagram(state.diagram);
      state = state.copyWith(saving: false, dirty: false, diagram: saved);
      return true;
    } catch (e) {
      state = state.copyWith(saving: false, errorMessage: 'Save failed: ${_message(e)}');
      return false;
    }
  }

  /// Saves the edit buffer, then freezes it as the next immutable version (only valid processes publish).
  Future<void> publish() async {
    if (!await save()) return;
    try {
      final v = await _repo.publish(DefinitionKind.process, state.diagram.id);
      state = state.copyWith(publishedVersion: v.version, clearError: true);
    } catch (e) {
      state = state.copyWith(errorMessage: 'Publish failed: ${_message(e)}');
    }
  }

  /// Marks nodes on the canvas (the path of a run); an empty set clears the marks.
  void setHighlight(Set<String> nodeIds) => state = state.copyWith(highlight: nodeIds);

  /// BPMN 2.0 XML of the *saved* process, produced by the server's exporter.
  Future<String> exportXml() => _repo.exportBpmn(state.diagram.id);

}

/// Nodes to mark on the canvas (set by the Runs screen to show the path a run took).
final runHighlightProvider = StateProvider<Set<String>>((ref) => const {});

/// The process open in the designer (null until one is picked or created).
final activeBpmnDiagramIdProvider = StateProvider<String?>((ref) => null);

/// The editor for the process picked in [activeBpmnDiagramIdProvider] (an empty id = nothing open).
final bpmnControllerProvider = StateNotifierProvider<BpmnController, BpmnEditorState>((ref) {
  return BpmnController(ref.watch(repositoryProvider), ref.watch(activeBpmnDiagramIdProvider) ?? '');
});

/// Summaries of the process definitions (id + name); a process is loaded in full when opened.
final bpmnDiagramListProvider = FutureProvider.autoDispose<List<DefinitionSummary>>((ref) {
  return ref.watch(definitionsProvider(DefinitionKind.process).future);
});

/// New/duplicate/rename/delete for the process picker — a list-level concern, separate from [BpmnController]
/// which only edits one diagram.
class BpmnDiagramActions {
  BpmnDiagramActions(this.ref);
  final Ref ref;

  Future<void> createNew(String name) async {
    final repo = ref.read(repositoryProvider);
    final id = IdGen.next('process');
    final diagram = BpmnDiagram(
      id: id,
      name: name,
      projectId: ref.read(activeProjectIdProvider),
      lanes: const [BpmnLane(id: 'lane-1', name: 'Participant', order: 0)],
    );
    await repo.saveDiagram(diagram);
    ref.invalidate(bpmnDiagramListProvider);
    ref.read(activeBpmnDiagramIdProvider.notifier).state = id;
  }

  Future<void> duplicate(BpmnDiagram source) async {
    final repo = ref.read(repositoryProvider);
    final id = IdGen.next('process');
    final clone = BpmnDiagram(
      id: id,
      name: '${source.name} (copy)',
      lanes: source.lanes,
      nodes: source.nodes,
      flows: source.flows,
      projectId: source.projectId,
    );
    await repo.saveDiagram(clone);
    ref.invalidate(bpmnDiagramListProvider);
    ref.read(activeBpmnDiagramIdProvider.notifier).state = id;
  }

  Future<void> rename(BpmnDiagram diagram, String newName) async {
    await ref.read(repositoryProvider).saveDiagram(diagram.copyWith(name: newName));
    ref.invalidate(bpmnDiagramListProvider);
  }

  /// Deletes the *draft*; published versions are immutable and stay (runs may reference them).
  Future<void> deleteDraft(String id, List<DefinitionSummary> remaining) async {
    await ref.read(repositoryProvider).deleteDraft(DefinitionKind.process, id);
    ref.invalidate(bpmnDiagramListProvider);
    final others = remaining.where((d) => d.id != id).toList();
    ref.read(activeBpmnDiagramIdProvider.notifier).state = others.isNotEmpty ? others.first.id : null;
  }

  /// Turns BPMN 2.0 XML into a new draft (the server's importer adds a layout when the XML has none).
  Future<String> importXml(String xml) async {
    final repo = ref.read(repositoryProvider);
    final doc = await repo.importBpmn(xml);
    final id = IdGen.next('process');
    final imported = bpmnFromDocument({...doc, 'id': id});
    await repo.saveDiagram(imported);
    ref.invalidate(bpmnDiagramListProvider);
    ref.read(activeBpmnDiagramIdProvider.notifier).state = id;
    return id;
  }
}

final bpmnDiagramActionsProvider = Provider((ref) => BpmnDiagramActions(ref));
