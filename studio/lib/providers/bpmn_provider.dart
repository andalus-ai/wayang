import 'dart:async';

import 'package:flutter/material.dart' hide DiagnosticLevel;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/utils.dart';
import '../data/api_client.dart';
import '../data/bpmn_mapper.dart';
import '../data/wayang_repository.dart';
import '../features/bpmn/auto_layout.dart';
import '../features/bpmn/selection.dart';
import '../models/bpmn.dart';
import '../models/wayang.dart';
import 'app_providers.dart';

@immutable
class BpmnEditorState {
  const BpmnEditorState({
    this.loading = true,
    this.diagram = BpmnDiagram.empty,
    this.selectedNodeId,
    this.selectedNodeIds = const {},
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
  /// The one selected node, or null when none or several are selected (the inspector shows a node only when exactly one is).
  final String? selectedNodeId;

  /// Every selected node, including the single one above.
  final Set<String> selectedNodeIds;
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
    Set<String>? selectedNodeIds,
    bool clearSelectedNode = false,
    bool clearPrimary = false,
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
      selectedNodeId: (clearSelectedNode || clearPrimary) ? null : (selectedNodeId ?? this.selectedNodeId),
      selectedNodeIds: clearSelectedNode ? const {} : (selectedNodeIds ?? this.selectedNodeIds),
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

  void selectNode(String id) => state = state.copyWith(selectedNodeId: id, selectedNodeIds: {id}, clearSelectedFlow: true);

  /// Replaces the selection with [ids] (only ids that exist). One id behaves like [selectNode]; none clears the node selection.
  void selectNodes(Iterable<String> ids) {
    final valid = {for (final id in ids) if (state.diagram.node(id) != null) id};
    if (valid.isEmpty) {
      state = state.copyWith(clearSelectedNode: true);
    } else {
      state = state.copyWith(
        selectedNodeId: valid.length == 1 ? valid.first : null,
        clearPrimary: valid.length > 1,
        selectedNodeIds: valid,
        clearSelectedFlow: true,
      );
    }
  }

  /// Adds [id] to the selection, or removes it when already selected (shift/ctrl-click).
  void toggleNode(String id) {
    final next = {...state.selectedNodeIds};
    if (!next.remove(id)) next.add(id);
    selectNodes(next);
  }

  void selectAllNodes() => selectNodes(state.diagram.nodeList.map((n) => n.id));
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
    if (state.selectedNodeIds.length > 1) {
      _checkpoint();
      var d = state.diagram;
      for (final id in state.selectedNodeIds) {
        d = d.removeNode(id);
      }
      state = state.copyWith(diagram: d);
      clearSelection();
      unawaited(validate());
    } else if (state.selectedNodeId != null) {
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

  /// Copies the selected nodes (and the flows that run between two of them) 40px down and right, and selects the copies. One undo step.
  void duplicateSelected() {
    final ids = state.selectedNodeIds;
    if (ids.isEmpty) return;
    _checkpoint();
    var d = state.diagram;
    final map = <String, String>{};
    for (final id in ids) {
      final n = state.diagram.node(id);
      if (n == null) continue;
      final copyId = IdGen.next('bn');
      map[id] = copyId;
      d = d.addNode(BpmnNode(id: copyId, type: n.type, x: n.x + 40, y: n.y + 40, label: n.label, laneId: n.laneId, properties: n.properties));
    }
    for (final f in state.diagram.flowList) {
      final a = map[f.from], b = map[f.to];
      if (a != null && b != null) {
        d = d.connect(BpmnFlow(id: IdGen.next('bf'), from: a, to: b, label: f.label, condition: f.condition, isDefault: f.isDefault));
      }
    }
    state = state.copyWith(diagram: d);
    selectNodes(map.values);
    unawaited(validate());
  }

  /// Tidies the diagram into left-to-right columns: the selection when two or more nodes are selected, else every node. One undo step.
  void autoLayout() {
    final sel = state.selectedNodeIds;
    final positions = layeredLayout(state.diagram, only: sel.length > 1 ? sel : null);
    if (positions.isEmpty) return;
    _checkpoint();
    var d = state.diagram;
    for (final e in positions.entries) {
      d = d.moveNode(e.key, e.value.dx, e.value.dy);
    }
    state = state.copyWith(diagram: d);
  }

  /// The selected nodes and the flows between two of them, or null when nothing is selected.
  BpmnClip? copySelection() {
    final ids = state.selectedNodeIds;
    final nodes = [for (final n in state.diagram.nodeList) if (ids.contains(n.id)) n];
    if (nodes.isEmpty) return null;
    return BpmnClip(nodes, [for (final f in state.diagram.flowList) if (ids.contains(f.from) && ids.contains(f.to)) f]);
  }

  /// Adds the clip's nodes and internal flows under new ids and selects them. With [at] the clip's top-left corner lands there; without it
  /// the clip is nudged 40px down-right of where it came from until it does not sit exactly on an existing node. Each pasted node joins
  /// the lane it lands in. One undo step.
  void pasteClip(BpmnClip clip, {Offset? at}) {
    if (clip.isEmpty) return;
    final minX = clip.nodes.map((n) => n.x).reduce((a, b) => a < b ? a : b);
    final minY = clip.nodes.map((n) => n.y).reduce((a, b) => a < b ? a : b);
    var dx = at == null ? 0.0 : at.dx - minX, dy = at == null ? 0.0 : at.dy - minY;
    if (at == null) {
      bool clash() => clip.nodes.any((c) => state.diagram.nodeList.any((n) => (n.x - (c.x + dx)).abs() < 1 && (n.y - (c.y + dy)).abs() < 1));
      for (var i = 0; i < 50 && (i == 0 || clash()); i++) {
        dx += 40;
        dy += 40;
      }
    }
    _checkpoint();
    var d = state.diagram;
    final map = <String, String>{};
    for (final n in clip.nodes) {
      final id = IdGen.next('bn');
      map[n.id] = id;
      d = d.addNode(BpmnNode(id: id, type: n.type, x: n.x + dx, y: n.y + dy, label: n.label, properties: n.properties));
    }
    for (final f in clip.flows) {
      final a = map[f.from], b = map[f.to];
      if (a != null && b != null) {
        d = d.connect(BpmnFlow(id: IdGen.next('bf'), from: a, to: b, label: f.label, condition: f.condition, isDefault: f.isDefault));
      }
    }
    for (final e in lanesForNodes(d, map.values).entries) {
      final n = d.node(e.key);
      if (n != null) d = d.moveNode(e.key, n.x, n.y, laneId: e.value, clearLane: e.value == null);
    }
    state = state.copyWith(diagram: d);
    selectNodes(map.values);
    unawaited(validate());
  }

  /// Moves every selected node by [dx],[dy]. One undo step per call. Does nothing with an empty selection.
  void nudgeSelected(double dx, double dy) {
    final ids = state.selectedNodeIds;
    if (ids.isEmpty || (dx == 0 && dy == 0)) return;
    _checkpoint();
    var d = state.diagram;
    for (final id in ids) {
      final n = d.node(id);
      if (n != null) d = d.moveNode(id, n.x + dx, n.y + dy);
    }
    state = state.copyWith(diagram: d);
  }

  /// Lines the selected nodes up on their leftmost x (or topmost y). Needs two or more. One undo step.
  void alignSelected({required bool horizontal}) {
    final nodes = [for (final id in state.selectedNodeIds) if (state.diagram.node(id) case final n?) n];
    if (nodes.length < 2) return;
    _checkpoint();
    var d = state.diagram;
    if (horizontal) {
      final x = nodes.map((n) => n.x).reduce((a, b) => a < b ? a : b);
      for (final n in nodes) {
        d = d.moveNode(n.id, x, n.y);
      }
    } else {
      final y = nodes.map((n) => n.y).reduce((a, b) => a < b ? a : b);
      for (final n in nodes) {
        d = d.moveNode(n.id, n.x, y);
      }
    }
    state = state.copyWith(diagram: d);
  }

  void dragNode(String id, double x, double y) {
    state = state.copyWith(diagram: state.diagram.moveNode(id, x, y));
  }

  /// Moves every node in [starts] (id -> position when the drag began) by [dx],[dy]. No undo step until [endDragGroup].
  void dragGroup(Map<String, Offset> starts, double dx, double dy) {
    var d = state.diagram;
    for (final e in starts.entries) {
      d = d.moveNode(e.key, e.value.dx + dx, e.value.dy + dy);
    }
    state = state.copyWith(diagram: d);
  }

  /// Ends a group drag with one undo step. [before] is the diagram as it was when the drag began. [lanes] (node id -> lane id, null for
  /// none) re-assigns lane membership as part of the same step.
  void endDragGroup(BpmnDiagram before, {Map<String, String?> lanes = const {}}) {
    if (identical(before, state.diagram)) return;
    var now = state.diagram;
    for (final e in lanes.entries) {
      final n = now.node(e.key);
      if (n != null) now = now.moveNode(e.key, n.x, n.y, laneId: e.value, clearLane: e.value == null);
    }
    state = state.copyWith(diagram: before);
    _checkpoint();
    state = state.copyWith(diagram: now);
  }

  void endDragNode(String id, {String? laneId, bool clearLane = false}) {
    final node = state.diagram.node(id);
    if (node == null) return;
    _checkpoint();
    state = state.copyWith(diagram: state.diagram.moveNode(id, node.x, node.y, laneId: laneId, clearLane: clearLane));
  }

  void updateNodeLabel(String id, String label) {
    final node = state.diagram.node(id);
    if (node == null || node.label == label) return;
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

  String addNote(double x, double y) {
    _checkpoint();
    final id = IdGen.next('note');
    state = state.copyWith(diagram: state.diagram.putNote(BpmnNote(id: id, x: x, y: y)));
    return id;
  }

  void updateNoteText(String id, String text) {
    final n = state.diagram.notes[id];
    final t = text.length > BpmnNote.maxText ? text.substring(0, BpmnNote.maxText) : text;
    if (n == null || n.text == t) return;
    _checkpoint();
    state = state.copyWith(diagram: state.diagram.putNote(n.copyWith(text: t)));
  }

  /// Call once when a note drag starts: that is the one undo step for the whole drag.
  void beginNoteDrag() => _checkpoint();

  void dragNote(String id, double x, double y) {
    final n = state.diagram.notes[id];
    if (n != null) state = state.copyWith(diagram: state.diagram.putNote(n.copyWith(x: x, y: y)));
  }

  void removeNote(String id) {
    if (!state.diagram.notes.containsKey(id)) return;
    _checkpoint();
    state = state.copyWith(diagram: state.diagram.removeNote(id));
  }

  /// Sets the text drawn on a flow. One undo step; an unchanged label records nothing.
  void setFlowLabel(String flowId, String label) {
    final flow = state.diagram.flows[flowId];
    if (flow == null || flow.label == label) return;
    _checkpoint();
    state = state.copyWith(diagram: state.diagram.updateFlow(flow.copyWith(label: label)));
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

/// Grid size in canvas pixels, used by snap-to-grid and by arrow-key nudging.
const double bpmnGrid = 20;

/// Whether dragging snaps nodes to the grid.
final bpmnSnapProvider = StateProvider<bool>((ref) => false);

/// Nodes (with the flows that run between them) copied from a diagram, ready to paste into this or another process. Kept in memory
/// only: it is not written to the system clipboard and is gone when Studio closes.
class BpmnClip {
  const BpmnClip(this.nodes, this.flows);

  final List<BpmnNode> nodes;
  final List<BpmnFlow> flows;

  bool get isEmpty => nodes.isEmpty;
}

/// What Ctrl+C / Ctrl+X last copied. Survives switching processes, which is what makes copying between processes work.
final bpmnClipboardProvider = StateProvider<BpmnClip?>((ref) => null);

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
