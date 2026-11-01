// Plain read models of the Wayang API (hand-written like the rest of the app: no codegen).

DateTime? parseTime(Object? v) {
  if (v is String) return DateTime.tryParse(v);
  if (v is num) return DateTime.fromMillisecondsSinceEpoch((v * 1000).round(), isUtc: true);
  return null;
}

Map<String, dynamic> asMap(Object? v) => v is Map ? Map<String, dynamic>.from(v) : <String, dynamic>{};

List<dynamic> asList(Object? v) => v is List ? v : const [];

/// The definition kinds the server stores; [path] is the URL segment.
enum DefinitionKind {
  process('process', 'Processes'),
  decision('decision', 'Decisions'),
  agent('agent', 'Agents'),
  pipeline('pipeline', 'Pipelines');

  const DefinitionKind(this.path, this.label);
  final String path;
  final String label;
}

class DefinitionSummary {
  const DefinitionSummary({
    required this.kind,
    required this.id,
    required this.name,
    required this.hasDraft,
    required this.latestVersion,
    this.updatedAt,
    this.updatedBy,
    this.projectId,
  });

  final DefinitionKind kind;
  final String id;
  final String name;
  final bool hasDraft;

  /// 0 when never published.
  final int latestVersion;
  final DateTime? updatedAt;
  final String? updatedBy;
  final String? projectId;

  bool get isPublished => latestVersion > 0;

  factory DefinitionSummary.fromJson(DefinitionKind kind, Map<String, dynamic> j) => DefinitionSummary(
        kind: kind,
        id: j['id'] as String,
        name: (j['name'] as String?) ?? (j['id'] as String),
        hasDraft: j['hasDraft'] == true,
        latestVersion: (j['latestVersion'] as num?)?.toInt() ?? 0,
        updatedAt: parseTime(j['updatedAt']),
        updatedBy: j['updatedBy'] as String?,
        projectId: j['projectId'] as String?,
      );
}

/// A stored draft (`version == 0`) or an immutable published version.
class StoredDefinition {
  const StoredDefinition({
    required this.kind,
    required this.id,
    required this.version,
    required this.revision,
    required this.document,
    this.updatedAt,
    this.updatedBy,
  });

  final DefinitionKind kind;
  final String id;
  final int version;
  final int revision;
  final Map<String, dynamic> document;
  final DateTime? updatedAt;
  final String? updatedBy;

  bool get isDraft => version == 0;

  factory StoredDefinition.fromJson(Map<String, dynamic> j) => StoredDefinition(
        kind: DefinitionKind.values.firstWhere((k) => k.path == '${j['kind']}'.toLowerCase(), orElse: () => DefinitionKind.process),
        id: j['id'] as String,
        version: (j['version'] as num?)?.toInt() ?? 0,
        revision: (j['revision'] as num?)?.toInt() ?? 0,
        document: asMap(j['document']),
        updatedAt: parseTime(j['updatedAt']),
        updatedBy: j['updatedBy'] as String?,
      );
}

class ValidationItem {
  const ValidationItem({required this.severity, required this.message, this.elementId});

  /// `ERROR` or `WARNING`.
  final String severity;
  final String message;
  final String? elementId;

  bool get isError => severity.toUpperCase() == 'ERROR';

  factory ValidationItem.fromJson(Map<String, dynamic> j) => ValidationItem(
        severity: (j['severity'] as String?) ?? 'ERROR',
        message: (j['message'] as String?) ?? '',
        elementId: j['elementId'] as String?,
      );
}

class ValidationReport {
  const ValidationReport({required this.valid, required this.items});
  final bool valid;
  final List<ValidationItem> items;

  factory ValidationReport.fromJson(Map<String, dynamic> j) => ValidationReport(
        valid: j['valid'] == true,
        items: [for (final i in asList(j['items'])) ValidationItem.fromJson(asMap(i))],
      );
}

/// One entry of `GET /api/v1/catalog/nodes`: what the palette shows and the JSON-schema of the node's config.
class NodeCatalogEntry {
  const NodeCatalogEntry({
    required this.type,
    required this.label,
    required this.category,
    required this.description,
    required this.waits,
    required this.maxIncoming,
    required this.maxOutgoing,
    required this.configSchema,
  });

  final String type;
  final String label;
  final String category;
  final String description;
  final bool waits;
  final int maxIncoming;
  final int maxOutgoing;
  final Map<String, dynamic> configSchema;

  List<String> get requiredKeys => [for (final r in asList(configSchema['required'])) '$r'];
  Map<String, dynamic> get properties => asMap(configSchema['properties']);

  factory NodeCatalogEntry.fromJson(Map<String, dynamic> j) => NodeCatalogEntry(
        type: j['type'] as String,
        label: (j['label'] as String?) ?? (j['type'] as String),
        category: (j['category'] as String?) ?? 'Other',
        description: (j['description'] as String?) ?? '',
        waits: j['waits'] == true,
        maxIncoming: (j['maxIncoming'] as num?)?.toInt() ?? 0,
        maxOutgoing: (j['maxOutgoing'] as num?)?.toInt() ?? 0,
        configSchema: asMap(j['configSchema']),
      );
}

class Project {
  const Project({required this.id, required this.name, this.description, this.createdAt, this.updatedAt});
  final String id;
  final String name;
  final String? description;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  factory Project.fromJson(Map<String, dynamic> j) => Project(
        id: j['id'] as String,
        name: (j['name'] as String?) ?? (j['id'] as String),
        description: j['description'] as String?,
        createdAt: parseTime(j['createdAt']),
        updatedAt: parseTime(j['updatedAt']),
      );
}

/// A pending wait: a human task, a timer or a message correlation.
class TaskView {
  const TaskView({
    required this.token,
    required this.runId,
    required this.nodeId,
    required this.kind,
    this.title,
    this.assignee,
    this.info = const {},
    this.dueAt,
    this.messageName,
    this.correlationKey,
  });

  final String token;
  final String runId;
  final String nodeId;

  /// `human`, `timer` or `message`.
  final String kind;
  final String? title;
  final String? assignee;
  final Map<String, dynamic> info;
  final DateTime? dueAt;
  final String? messageName;
  final String? correlationKey;

  factory TaskView.fromJson(Map<String, dynamic> j) => TaskView(
        token: j['token'] as String,
        runId: j['runId'] as String,
        nodeId: j['nodeId'] as String,
        kind: (j['kind'] as String?) ?? 'human',
        title: j['title'] as String?,
        assignee: j['assignee'] as String?,
        info: asMap(j['info']),
        dueAt: parseTime(j['dueAt']),
        messageName: j['messageName'] as String?,
        correlationKey: j['correlationKey'] as String?,
      );
}

enum RunStatus {
  running,
  waiting,
  completed,
  failed,
  cancelled;

  bool get isTerminal => this == completed || this == failed || this == cancelled;

  static RunStatus parse(Object? v) => RunStatus.values.firstWhere(
        (s) => s.name == '$v'.toLowerCase(),
        orElse: () => RunStatus.running,
      );
}

class RunView {
  const RunView({
    required this.id,
    required this.processId,
    required this.version,
    required this.status,
    this.variables = const {},
    this.executedNodes = const [],
    this.waiting = const [],
    this.error,
    this.startedBy,
    this.startedAt,
    this.finishedAt,
  });

  final String id;
  final String processId;
  final int version;
  final RunStatus status;
  final Map<String, dynamic> variables;

  /// Nodes that did real work, in order: lets the canvas highlight the path taken.
  final List<String> executedNodes;
  final List<TaskView> waiting;
  final String? error;
  final String? startedBy;
  final DateTime? startedAt;
  final DateTime? finishedAt;

  factory RunView.fromJson(Map<String, dynamic> j) => RunView(
        id: j['id'] as String,
        processId: (j['processId'] as String?) ?? '',
        version: (j['version'] as num?)?.toInt() ?? 0,
        status: RunStatus.parse(j['status']),
        variables: asMap(j['variables']),
        executedNodes: [for (final n in asList(j['executedNodes'])) '$n'],
        waiting: [for (final t in asList(j['waiting'])) TaskView.fromJson(asMap(t))],
        error: j['error'] as String?,
        startedBy: j['startedBy'] as String?,
        startedAt: parseTime(j['startedAt']),
        finishedAt: parseTime(j['finishedAt']),
      );
}

/// Secret metadata only: the server never returns a value.
class SecretMeta {
  const SecretMeta({required this.name, required this.version, required this.keyId, this.updatedAt, this.updatedBy});
  final String name;
  final int version;
  final String keyId;
  final DateTime? updatedAt;
  final String? updatedBy;

  factory SecretMeta.fromJson(Map<String, dynamic> j) => SecretMeta(
        name: j['name'] as String,
        version: (j['version'] as num?)?.toInt() ?? 1,
        keyId: (j['keyId'] as String?) ?? '',
        updatedAt: parseTime(j['updatedAt']),
        updatedBy: j['updatedBy'] as String?,
      );
}

/// A trigger as listed by the API. The webhook secret is only ever returned by the create call.
class TriggerInfo {
  const TriggerInfo({
    required this.id,
    required this.kind,
    required this.processId,
    required this.enabled,
    required this.config,
    this.lastPolledAt,
    this.lastError,
    this.consecutiveFailures = 0,
  });
  final String id;
  final String kind;
  final String processId;
  final bool enabled;
  final Map<String, dynamic> config;
  final DateTime? lastPolledAt;
  final String? lastError;
  final int consecutiveFailures;

  bool get isPoll => kind == 'POLL';

  /// `telegram`, `gmail`, `slack`, `whatsapp`, or `webhook`.
  String get channel => kind == 'SCHEDULE' ? 'schedule' : (config['source'] ?? config['auth'] ?? 'webhook').toString();

  bool get isSchedule => kind == 'SCHEDULE';

  factory TriggerInfo.fromJson(Map<String, dynamic> j) => TriggerInfo(
        id: j['id'] as String,
        kind: (j['kind'] as String?) ?? 'WEBHOOK',
        processId: (j['processId'] as String?) ?? '',
        enabled: j['enabled'] == true,
        config: j['config'] is Map ? Map<String, dynamic>.from(j['config'] as Map) : <String, dynamic>{},
        lastPolledAt: parseTime(j['lastPolledAt']),
        lastError: j['lastError'] as String?,
        consecutiveFailures: (j['consecutiveFailures'] as num?)?.toInt() ?? 0,
      );
}

/// Result of creating a trigger; [webhookSecret] is shown once.
class CreatedTrigger {
  const CreatedTrigger({required this.trigger, this.webhookSecret, this.webhookPath});
  final TriggerInfo trigger;
  final String? webhookSecret;
  final String? webhookPath;
}

/// A connected account (OAuth). Holds no tokens.
class OAuthConnectionInfo {
  const OAuthConnectionInfo({
    required this.id,
    required this.provider,
    required this.clientId,
    required this.scopes,
    required this.status,
    required this.secretRef,
    this.connectedAt,
  });
  final String id;
  final String provider;
  final String clientId;
  final List<String> scopes;
  final String status; // PENDING | CONNECTED | REVOKED
  final String secretRef; // what to put in a node/trigger config, e.g. ${secret:oauth.work}
  final DateTime? connectedAt;

  factory OAuthConnectionInfo.fromJson(Map<String, dynamic> j) => OAuthConnectionInfo(
        id: j['id'] as String,
        provider: (j['provider'] as String?) ?? '',
        clientId: (j['clientId'] as String?) ?? '',
        scopes: [for (final x in (j['scopes'] as List? ?? const [])) x.toString()],
        status: (j['status'] as String?) ?? 'PENDING',
        secretRef: (j['secretRef'] as String?) ?? '',
        connectedAt: parseTime(j['connectedAt']),
      );
}

/// One tool of a generated MCP server (the MCP `tools/list` entry).
class McpToolInfo {
  const McpToolInfo({required this.name, required this.description, required this.readOnly, required this.destructive, required this.args});
  final String name;
  final String description;
  final bool readOnly;
  final bool destructive;
  final List<String> args;

  factory McpToolInfo.fromJson(Map<String, dynamic> j) {
    final ann = j['annotations'] is Map ? Map<String, dynamic>.from(j['annotations'] as Map) : <String, dynamic>{};
    final schema = j['inputSchema'] is Map ? Map<String, dynamic>.from(j['inputSchema'] as Map) : <String, dynamic>{};
    final props = schema['properties'] is Map ? Map<String, dynamic>.from(schema['properties'] as Map) : <String, dynamic>{};
    return McpToolInfo(
      name: (j['name'] as String?) ?? '',
      description: (j['description'] as String?) ?? '',
      readOnly: ann['readOnlyHint'] == true,
      destructive: ann['destructiveHint'] == true,
      args: props.keys.toList(),
    );
  }
}

/// A generated server, or the result of a preview (then [id] is empty).
class McpServerInfo {
  const McpServerInfo({
    required this.id,
    required this.title,
    required this.baseUrl,
    required this.endpoint,
    required this.tools,
    required this.warnings,
    required this.security,
    this.sourceUrl,
  });
  final String id;
  final String title;
  final String baseUrl;
  final String endpoint;
  final String? sourceUrl;
  final List<McpToolInfo> tools;
  final List<String> warnings;
  final List<String> security;

  factory McpServerInfo.fromJson(Map<String, dynamic> j) => McpServerInfo(
        id: (j['id'] as String?) ?? '',
        title: (j['title'] as String?) ?? '',
        baseUrl: (j['baseUrl'] as String?) ?? '',
        endpoint: (j['endpoint'] as String?) ?? '',
        sourceUrl: j['sourceUrl'] as String?,
        tools: [for (final t in (j['tools'] as List? ?? const [])) McpToolInfo.fromJson(Map<String, dynamic>.from(t as Map))],
        warnings: [for (final w in (j['warnings'] as List? ?? const [])) w.toString()],
        security: [
          for (final s in (j['security'] as List? ?? const []))
            if (s is Map) '${s['name']} (${s['type']}${s['scheme'] != null ? ' ${s['scheme']}' : ''})'
        ],
      );
}
