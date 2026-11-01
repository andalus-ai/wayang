import 'package:flutter/material.dart';
import '../core/theme.dart';

/// The element types Wayang executes. [wireName] is exactly the server's `ElementType` name, so a document
/// round-trips without translation; the palette's labels, categories and config schemas come from the server's
/// node catalog (`GET /api/v1/catalog/nodes`), the icons and colours below are presentation only.
enum BpmnElementType {
  startEvent,
  endEvent,
  timerEvent,
  messageCatchEvent,
  userTask,
  serviceTask,
  businessRuleTask,
  agentTask,
  eipTask,
  exclusiveGateway,
  parallelGateway,
  inclusiveGateway;

  String get wireName => switch (this) {
        BpmnElementType.startEvent => 'START_EVENT',
        BpmnElementType.endEvent => 'END_EVENT',
        BpmnElementType.timerEvent => 'TIMER_EVENT',
        BpmnElementType.messageCatchEvent => 'MESSAGE_CATCH_EVENT',
        BpmnElementType.userTask => 'USER_TASK',
        BpmnElementType.serviceTask => 'SERVICE_TASK',
        BpmnElementType.businessRuleTask => 'BUSINESS_RULE_TASK',
        BpmnElementType.agentTask => 'AGENT_TASK',
        BpmnElementType.eipTask => 'EIP_TASK',
        BpmnElementType.exclusiveGateway => 'EXCLUSIVE_GATEWAY',
        BpmnElementType.parallelGateway => 'PARALLEL_GATEWAY',
        BpmnElementType.inclusiveGateway => 'INCLUSIVE_GATEWAY',
      };

  /// Null for a type this Studio does not know (a newer server); callers must report it, never guess.
  static BpmnElementType? tryFromWire(String v) {
    for (final t in BpmnElementType.values) {
      if (t.wireName == v) return t;
    }
    return null;
  }

  String get label => switch (this) {
        BpmnElementType.startEvent => 'Start event',
        BpmnElementType.endEvent => 'End event',
        BpmnElementType.timerEvent => 'Timer',
        BpmnElementType.messageCatchEvent => 'Wait for message',
        BpmnElementType.userTask => 'Human task',
        BpmnElementType.serviceTask => 'Service call',
        BpmnElementType.businessRuleTask => 'Business rule (DMN)',
        BpmnElementType.agentTask => 'AI agent',
        BpmnElementType.eipTask => 'Integration pipeline (EIP)',
        BpmnElementType.exclusiveGateway => 'Exclusive gateway',
        BpmnElementType.parallelGateway => 'Parallel gateway',
        BpmnElementType.inclusiveGateway => 'Inclusive gateway',
      };

  bool get isEvent =>
      this == startEvent || this == endEvent || this == timerEvent || this == messageCatchEvent;
  bool get isGateway => this == exclusiveGateway || this == parallelGateway || this == inclusiveGateway;
  bool get isTask => !isEvent && !isGateway;

  /// Elements that park the run until something outside happens (a person, a clock, a message).
  bool get waits => this == userTask || this == timerEvent || this == messageCatchEvent;

  IconData get icon => switch (this) {
        BpmnElementType.startEvent => Icons.play_arrow_rounded,
        BpmnElementType.endEvent => Icons.stop_rounded,
        BpmnElementType.timerEvent => Icons.timer_outlined,
        BpmnElementType.messageCatchEvent => Icons.mail_outline_rounded,
        BpmnElementType.userTask => Icons.person_outline_rounded,
        BpmnElementType.serviceTask => Icons.settings_outlined,
        BpmnElementType.businessRuleTask => Icons.table_chart_outlined,
        BpmnElementType.agentTask => Icons.smart_toy_outlined,
        BpmnElementType.eipTask => Icons.hub_outlined,
        BpmnElementType.exclusiveGateway => Icons.close_rounded,
        BpmnElementType.parallelGateway => Icons.add_rounded,
        BpmnElementType.inclusiveGateway => Icons.circle_outlined,
      };
}

/// A single BPMN element placed on the canvas.
class BpmnNode {
  const BpmnNode({
    required this.id,
    required this.type,
    required this.x,
    required this.y,
    this.label = '',
    this.laneId,
    this.properties = const {},
  });

  final String id;
  final BpmnElementType type;
  final double x;
  final double y;
  final String label;
  final String? laneId;

  /// The element's configuration, stored verbatim as the server's `config` (validated against the node
  /// catalog's schema for [type]).
  final Map<String, dynamic> properties;

  BpmnNode copyWith({
    double? x,
    double? y,
    String? label,
    String? laneId,
    bool clearLane = false,
    Map<String, dynamic>? properties,
  }) {
    return BpmnNode(
      id: id,
      type: type,
      x: x ?? this.x,
      y: y ?? this.y,
      label: label ?? this.label,
      laneId: clearLane ? null : (laneId ?? this.laneId),
      properties: properties ?? this.properties,
    );
  }
}

/// A connector between two [BpmnNode]s — a sequence flow, optionally the
/// default branch out of a gateway with a guard condition.
class BpmnFlow {
  const BpmnFlow({
    required this.id,
    required this.from,
    required this.to,
    this.label = '',
    this.condition,
    this.isDefault = false,
  });

  final String id;
  final String from;
  final String to;
  final String label;
  final String? condition;
  final bool isDefault;

  BpmnFlow copyWith({String? label, String? condition, bool? isDefault}) {
    return BpmnFlow(
      id: id,
      from: from,
      to: to,
      label: label ?? this.label,
      condition: condition ?? this.condition,
      isDefault: isDefault ?? this.isDefault,
    );
  }
}

/// A horizontal swimlane — represents a participant/role.
class BpmnLane {
  const BpmnLane({required this.id, required this.name, required this.order, this.height = 220});
  final String id;
  final String name;
  final int order;
  final double height;

  BpmnLane copyWith({String? name, double? height}) =>
      BpmnLane(id: id, name: name ?? this.name, order: order, height: height ?? this.height);
}

/// Immutable BPMN process diagram — same shape philosophy as `StudioGraph`.
class BpmnDiagram {
  const BpmnDiagram({
    required this.id,
    this.name = 'Untitled process',
    this.lanes = const [],
    this.nodes = const {},
    this.flows = const {},
    this.revision,
    this.projectId,
    this.mcp,
  });

  final String id;
  final String name;

  /// Draft revision this diagram was loaded at; sent as `If-Match` when saving (null = not stored yet).
  final int? revision;

  /// Project the definition belongs to (`studio.projectId`), if any.
  final String? projectId;

  /// `studio.mcp`: opt-in exposure of the published process as an MCP tool (expose, description, waitSeconds, inputSchema).
  final Map<String, dynamic>? mcp;
  final List<BpmnLane> lanes;
  final Map<String, BpmnNode> nodes;
  final Map<String, BpmnFlow> flows;

  static const empty = BpmnDiagram(id: '');

  List<BpmnNode> get nodeList => nodes.values.toList()..sort((a, b) => a.id.compareTo(b.id));
  int get nodeCount => nodes.length;
  List<BpmnFlow> get flowList => flows.values.toList()..sort((a, b) => a.id.compareTo(b.id));
  List<BpmnLane> get sortedLanes => [...lanes]..sort((a, b) => a.order.compareTo(b.order));

  BpmnNode? node(String id) => nodes[id];
  List<BpmnFlow> flowsFrom(String nodeId) => flowList.where((f) => f.from == nodeId).toList();
  List<BpmnFlow> flowsTo(String nodeId) => flowList.where((f) => f.to == nodeId).toList();

  BpmnDiagram copyWith({
    String? name,
    List<BpmnLane>? lanes,
    Map<String, BpmnNode>? nodes,
    Map<String, BpmnFlow>? flows,
    int? revision,
    String? projectId,
    bool clearProject = false,
    Map<String, dynamic>? mcp,
    bool clearMcp = false,
  }) {
    return BpmnDiagram(
      id: id,
      name: name ?? this.name,
      lanes: lanes ?? this.lanes,
      nodes: nodes ?? this.nodes,
      flows: flows ?? this.flows,
      revision: revision ?? this.revision,
      projectId: clearProject ? null : (projectId ?? this.projectId),
      mcp: clearMcp ? null : (mcp ?? this.mcp),
    );
  }

  BpmnDiagram addNode(BpmnNode n) {
    final next = Map<String, BpmnNode>.from(nodes)..[n.id] = n;
    return copyWith(nodes: next);
  }

  BpmnDiagram removeNode(String id) {
    final nextNodes = Map<String, BpmnNode>.from(nodes)..remove(id);
    final nextFlows = Map<String, BpmnFlow>.from(flows)..removeWhere((_, f) => f.from == id || f.to == id);
    return copyWith(nodes: nextNodes, flows: nextFlows);
  }

  BpmnDiagram updateNode(BpmnNode n) {
    if (!nodes.containsKey(n.id)) return this;
    final next = Map<String, BpmnNode>.from(nodes)..[n.id] = n;
    return copyWith(nodes: next);
  }

  BpmnDiagram moveNode(String id, double x, double y, {String? laneId, bool clearLane = false}) {
    final n = nodes[id];
    if (n == null) return this;
    return updateNode(n.copyWith(x: x, y: y, laneId: laneId, clearLane: clearLane));
  }

  BpmnDiagram connect(BpmnFlow f) {
    if (!nodes.containsKey(f.from) || !nodes.containsKey(f.to)) return this;
    final next = Map<String, BpmnFlow>.from(flows)..[f.id] = f;
    return copyWith(flows: next);
  }

  BpmnDiagram disconnect(String flowId) {
    final next = Map<String, BpmnFlow>.from(flows)..remove(flowId);
    return copyWith(flows: next);
  }

  BpmnDiagram updateFlow(BpmnFlow f) {
    if (!flows.containsKey(f.id)) return this;
    final next = Map<String, BpmnFlow>.from(flows)..[f.id] = f;
    return copyWith(flows: next);
  }

  BpmnDiagram addLane(BpmnLane lane) => copyWith(lanes: [...lanes, lane]);

  BpmnDiagram removeLane(String laneId) {
    final nextLanes = lanes.where((l) => l.id != laneId).toList();
    final nextNodes = {
      for (final entry in nodes.entries)
        entry.key: entry.value.laneId == laneId ? entry.value.copyWith(clearLane: true) : entry.value,
    };
    return copyWith(lanes: nextLanes, nodes: nextNodes);
  }

  /// Nodes reachable by following sequence flows forward from every start
  /// event — used by validation to flag dead branches.
  Set<String> reachableFromStart() {
    final starts = nodeList.where((n) => n.type == BpmnElementType.startEvent).map((n) => n.id);
    final visited = <String>{};
    void visit(String id) {
      if (visited.contains(id)) return;
      visited.add(id);
      for (final f in flowsFrom(id)) {
        visit(f.to);
      }
    }

    for (final s in starts) {
      visit(s);
    }
    return visited;
  }
}

/// A single structural diagnostic for the BPMN "Validate" action.
class BpmnDiagnostic {
  const BpmnDiagnostic({required this.severity, required this.message, this.nodeId});
  final DiagnosticLevel severity;
  final String message;
  final String? nodeId;
}

enum DiagnosticLevel {
  warning,
  error;

  Color get color => this == DiagnosticLevel.error ? AppColors.danger : AppColors.warning;
  IconData get icon => this == DiagnosticLevel.error ? Icons.error_outline_rounded : Icons.warning_amber_rounded;
}
