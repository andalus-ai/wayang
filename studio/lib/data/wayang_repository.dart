import '../models/bpmn.dart';
import '../models/dmn.dart';
import '../models/wayang.dart';
import 'api_client.dart';
import 'bpmn_mapper.dart';
import 'dmn_mapper.dart';
import 'workspace_model.dart';

/// Everything the Studio and the management screens ask of the Wayang API, one method per endpoint
/// (see `docs/API.md`). There is no mock implementation: run the server's in-memory reference mode
/// (`wayang.store=memory`, `wayang.engine=reference`) for a backend that needs no infrastructure.
class WayangRepository {
  WayangRepository(this._api);

  final ApiClient _api;

  // ------------------------------------------------------------------ catalog

  Future<List<NodeCatalogEntry>> nodeCatalog() async {
    final j = await _api.getJson('/api/v1/catalog/nodes');
    return [for (final e in asList(j)) NodeCatalogEntry.fromJson(asMap(e))];
  }

  /// Profile-filtered reusable runtime capabilities and their configuration contracts.
  Future<List<ComponentCatalogEntry>> componentCatalog() async {
    final j = await _api.getJson('/api/v1/catalog/components');
    return [for (final e in asList(j)) ComponentCatalogEntry.fromJson(asMap(e))];
  }

  /// Validates a component config; invalid input is returned by the API as HTTP 422.
  Future<Map<String, dynamic>> validateComponentConfig(String id, Map<String, dynamic> config) async =>
      asMap(await _api.sendJson('POST', '/api/v1/catalog/components/$id/validate', body: config));

  // ----------------------------------------------------------------- projects

  Future<List<Project>> listProjects() async =>
      [for (final p in asList(await _api.getJson('/api/v1/projects'))) Project.fromJson(asMap(p))];

  Future<Project> createProject(String name, {String? description}) async => Project.fromJson(asMap(
      await _api.sendJson('POST', '/api/v1/projects', body: {'name': name, if (description != null) 'description': description})));

  Future<Project> updateProject(String id, String name, {String? description}) async => Project.fromJson(asMap(await _api
      .sendJson('PUT', '/api/v1/projects/$id', body: {'name': name, if (description != null) 'description': description})));

  Future<void> deleteProject(String id) async {
    await _api.sendJson('DELETE', '/api/v1/projects/$id');
  }

  // -------------------------------------------------------------- definitions

  Future<List<DefinitionSummary>> listDefinitions(DefinitionKind kind, {String? projectId}) async {
    final j = await _api.getJson('/api/v1/definitions/${kind.path}', query: {'projectId': projectId});
    return [for (final e in asList(j)) DefinitionSummary.fromJson(kind, asMap(e))];
  }

  /// The draft when there is one, otherwise the latest published version (saving it then creates the draft).
  Future<StoredDefinition> loadForEditing(DefinitionKind kind, String id) async {
    try {
      return StoredDefinition.fromJson(asMap(await _api.getJson('/api/v1/definitions/${kind.path}/$id/draft')));
    } on WayangApiException catch (e) {
      if (!e.isNotFound) rethrow;
      return StoredDefinition.fromJson(asMap(await _api.getJson('/api/v1/definitions/${kind.path}/$id/versions/latest')));
    }
  }

  Future<StoredDefinition> getVersion(DefinitionKind kind, String id, int version) async =>
      StoredDefinition.fromJson(asMap(await _api.getJson('/api/v1/definitions/${kind.path}/$id/versions/$version')));

  /// Saves the draft. [revision] is what the caller loaded (`null` only when creating); a stale one answers 409.
  Future<StoredDefinition> saveDraft(DefinitionKind kind, String id, Map<String, dynamic> doc, {int? revision}) async =>
      StoredDefinition.fromJson(asMap(await _api.sendJson('PUT', '/api/v1/definitions/${kind.path}/$id/draft',
          body: doc, headers: revision == null ? null : {'If-Match': '$revision'})));

  Future<void> deleteDraft(DefinitionKind kind, String id) async {
    await _api.sendJson('DELETE', '/api/v1/definitions/${kind.path}/$id/draft');
  }

  Future<ValidationReport> validate(DefinitionKind kind, Map<String, dynamic> doc) async =>
      ValidationReport.fromJson(asMap(await _api.sendJson('POST', '/api/v1/definitions/${kind.path}/validate', body: doc)));

  Future<StoredDefinition> publish(DefinitionKind kind, String id) async =>
      StoredDefinition.fromJson(asMap(await _api.sendJson('POST', '/api/v1/definitions/${kind.path}/$id/publish')));

  Future<String> exportBpmn(String processId, {int? version}) =>
      _api.getText('/api/v1/definitions/process/$processId/bpmn', query: {'version': version?.toString()});

  /// BPMN 2.0 XML -> native process document (not stored; the designer opens it and the person saves).
  Future<Map<String, dynamic>> importBpmn(String xml) async =>
      asMap(await _api.postText('/api/v1/definitions/process/import-bpmn', xml));

  // --------------------------------------------------- designer-specific forms

  Future<BpmnDiagram> loadDiagram(String id) async {
    final d = await loadForEditing(DefinitionKind.process, id);
    return bpmnFromDocument(d.document, revision: d.isDraft ? d.revision : null);
  }

  /// Saves and returns the diagram as the server stored it (new revision).
  Future<BpmnDiagram> saveDiagram(BpmnDiagram d) async {
    final saved = await saveDraft(DefinitionKind.process, d.id, bpmnToDocument(d), revision: d.revision);
    return d.copyWith(revision: saved.revision);
  }

  Future<ValidationReport> validateDiagram(BpmnDiagram d) => validate(DefinitionKind.process, bpmnToDocument(d));

  Future<DmnDecisionTable> loadTable(String id) async {
    final d = await loadForEditing(DefinitionKind.decision, id);
    return dmnFromDocument(d.document, revision: d.isDraft ? d.revision : null);
  }

  Future<DmnDecisionTable> saveTable(DmnDecisionTable t) async {
    final saved = await saveDraft(DefinitionKind.decision, t.id, dmnToDocument(t), revision: t.revision);
    return t.copyWith(revision: saved.revision);
  }

  Future<ValidationReport> validateTable(DmnDecisionTable t) => validate(DefinitionKind.decision, dmnToDocument(t));

  /// Runs the table on the server's engine, the same one that executes published processes.
  Future<Map<String, dynamic>> testTable(DmnDecisionTable t, Map<String, dynamic> inputs) async =>
      asMap(await _api.sendJson('POST', '/api/v1/tools/decision-test', body: {'document': dmnToDocument(t), 'inputs': inputs}));

  Future<Object?> evalExpression(String expression, Map<String, dynamic> variables) async {
    final j = asMap(await _api.sendJson('POST', '/api/v1/tools/expression', body: {'expression': expression, 'variables': variables}));
    return j['value'];
  }

  // --------------------------------------------------------------------- runs

  Future<RunView> startRun(String processId, {int? version, Map<String, dynamic> input = const {}}) async =>
      RunView.fromJson(asMap(await _api.sendJson('POST', '/api/v1/runs',
          body: {'processId': processId, if (version != null) 'version': version, 'input': input})));

  Future<List<RunView>> listRuns({String? processId, int limit = 50}) async {
    final j = await _api.getJson('/api/v1/runs', query: {'processId': processId, 'limit': '$limit'});
    return [for (final e in asList(j)) RunView.fromJson(asMap(e))];
  }

  Future<RunView> getRun(String id) async => RunView.fromJson(asMap(await _api.getJson('/api/v1/runs/$id')));

  /// A single-use ticket for the WebSocket handshake (browsers cannot send an Authorization header there).
  Future<String> wsTicket() async => '${asMap(await _api.sendJson('POST', '/api/v1/ws/ticket'))['ticket']}';

  Future<void> cancelRun(String id) async {
    await _api.sendJson('POST', '/api/v1/runs/$id/cancel');
  }

  Future<List<TaskView>> listTasks({int limit = 100}) async {
    final j = await _api.getJson('/api/v1/tasks', query: {'limit': '$limit'});
    return [for (final e in asList(j)) TaskView.fromJson(asMap(e))];
  }

  Future<void> completeTask(String token, Map<String, dynamic> outputs) async {
    await _api.sendJson('POST', '/api/v1/tasks/$token/complete', body: outputs);
  }

  /// Returns `DELIVERED` or `BUFFERED`.
  Future<String> sendMessage(String name, String correlationKey, Map<String, dynamic> payload) async {
    final j = asMap(await _api.sendJson('POST', '/api/v1/messages',
        body: {'name': name, 'correlationKey': correlationKey, 'payload': payload}));
    return '${j['result']}';
  }

  // ------------------------------------------------------------------ secrets

  Future<List<SecretMeta>> listSecrets() async =>
      [for (final e in asList(await _api.getJson('/api/v1/secrets'))) SecretMeta.fromJson(asMap(e))];

  Future<SecretMeta> putSecret(String name, String value) async =>
      SecretMeta.fromJson(asMap(await _api.sendJson('PUT', '/api/v1/secrets/$name', body: {'value': value})));

  Future<void> deleteSecret(String name) async {
    await _api.sendJson('DELETE', '/api/v1/secrets/$name');
  }

  Future<int> rotateSecrets() async {
    final j = asMap(await _api.sendJson('POST', '/api/v1/secrets/rotate'));
    return (j['resealed'] as num?)?.toInt() ?? 0;
  }

  // ---------------------------------------------------------- config, workspace

  /// Plain (non-secret) settings of the tenant, or of [project].
  Future<List<ConfigItem>> listConfig({String? project}) async =>
      [for (final e in asList(await _api.getJson('/api/v1/config', query: {'project': project}))) ConfigItem.fromJson(asMap(e))];

  Future<ConfigItem> putConfig(String key, String value, {String? project}) async => ConfigItem.fromJson(
      asMap(await _api.sendJson('PUT', '/api/v1/config/$key', body: {'value': value}, query: {'project': project})));

  Future<void> deleteConfig(String key, {String? project}) async {
    await _api.sendJson('DELETE', '/api/v1/config/$key', query: {'project': project});
  }

  /// The project as a bundle (never any secret value).
  Future<Map<String, dynamic>> exportProject(String id, {bool includeTenantConfig = false}) async =>
      asMap(await _api.getJson('/api/v1/projects/$id/export', query: {'includeTenantConfig': includeTenantConfig ? 'true' : null}));

  Future<ImportReport> importBundle(Map<String, dynamic> bundle, ImportOptions options) async => ImportReport.fromJson(
      asMap(await _api.sendJson('POST', '/api/v1/workspace/import', body: {'bundle': bundle, 'options': options.toJson()})));

  /// What a release of the project would contain (built from published versions only). Nothing is generated or stored.
  Future<ReleasePlan> planRelease(String projectId, ReleaseForm form) async =>
      ReleasePlan.fromJson(asMap(await _api.sendJson('POST', '/api/v1/projects/$projectId/release/plan', body: form.toJson())));

  /// Sends feedback (rating, comment, bug, idea or error report); any signed-in user may. Returns `{id}`.
  Future<Map<String, dynamic>> sendFeedback(Map<String, dynamic> request) async =>
      asMap(await _api.sendJson('POST', '/api/v1/feedback', body: request));

  /// The starter templates this server offers (empty when its profile has none).
  Future<List<TemplateInfo>> listTemplates() async => [
        for (final t in asList(await _api.getJson('/api/v1/templates'))) if (t is Map) TemplateInfo.fromJson(asMap(t)),
      ];

  /// Creates a starter project (`assistant-telegram`). A dry run unless [dryRun] is false; triggers are created disabled.
  Future<ImportReport> instantiateTemplate(String id, Map<String, String> params, {required bool dryRun}) async => ImportReport.fromJson(
      asMap(await _api.sendJson('POST', '/api/v1/templates/$id/instantiate', body: {'params': params, 'dryRun': dryRun})));

  /// Which master key the secrets are sealed under: `activeKeyId`, `secretsByKey`, `needRotation`.
  Future<KeyStatus> secretKeyStatus() async => KeyStatus.fromJson(asMap(await _api.getJson('/api/v1/secrets/keys')));

  /// Re-read the keyring after a rotation without a restart (admin; file or Vault key sources only).
  Future<KeyStatus> reloadSecretKeys() async => KeyStatus.fromJson(asMap(await _api.sendJson('POST', '/api/v1/secrets/keys/reload')));

  // ----------------------------------------------------------------- triggers

  Future<List<TriggerInfo>> listTriggers() async =>
      [for (final e in asList(await _api.getJson('/api/v1/triggers'))) TriggerInfo.fromJson(asMap(e))];

  Future<CreatedTrigger> createTrigger({
    required String id,
    required String kind,
    required String processId,
    required Map<String, dynamic> config,
  }) async {
    final j = asMap(await _api.sendJson('POST', '/api/v1/triggers',
        body: {'id': id, 'kind': kind, 'processId': processId, 'config': config}));
    return CreatedTrigger(
      trigger: TriggerInfo.fromJson(j),
      webhookSecret: j['webhookSecret'] as String?,
      webhookPath: j['webhookPath'] as String?,
    );
  }

  Future<void> setTriggerEnabled(String id, bool enabled) async {
    await _api.sendJson('PUT', '/api/v1/triggers/$id/enabled', body: {'enabled': enabled});
  }

  Future<void> deleteTrigger(String id) async {
    await _api.sendJson('DELETE', '/api/v1/triggers/$id');
  }

  // -------------------------------------------------------- connected accounts

  Future<List<OAuthConnectionInfo>> listOAuthConnections() async =>
      [for (final e in asList(await _api.getJson('/api/v1/oauth/connections'))) OAuthConnectionInfo.fromJson(asMap(e))];

  Future<OAuthConnectionInfo> createOAuthConnection({
    required String id,
    required String clientId,
    required String clientSecretName,
    required List<String> scopes,
    String provider = 'google',
  }) async =>
      OAuthConnectionInfo.fromJson(asMap(await _api.sendJson('POST', '/api/v1/oauth/connections', body: {
        'id': id,
        'provider': provider,
        'clientId': clientId,
        'clientSecretName': clientSecretName,
        'scopes': scopes,
      })));

  /// @return the consent URL to open in a browser
  Future<String> authorizeOAuthConnection(String id) async =>
      asMap(await _api.sendJson('POST', '/api/v1/oauth/connections/$id/authorize'))['url'] as String;

  Future<void> deleteOAuthConnection(String id) async {
    await _api.sendJson('DELETE', '/api/v1/oauth/connections/$id');
  }

  // --------------------------------------------------------------- MCP servers

  Future<List<McpServerInfo>> listMcpServers() async =>
      [for (final e in asList(await _api.getJson('/api/v1/mcp/servers'))) McpServerInfo.fromJson(asMap(e))];

  Future<McpServerInfo> getMcpServer(String id) async => McpServerInfo.fromJson(asMap(await _api.getJson('/api/v1/mcp/servers/$id')));

  /// Generates without storing. [request] is the create body (spec | specUrl, filters, headers).
  Future<McpServerInfo> previewMcpServer(Map<String, dynamic> request) async =>
      McpServerInfo.fromJson(asMap(await _api.sendJson('POST', '/api/v1/mcp/servers/preview', body: request)));

  Future<McpServerInfo> createMcpServer(String id, Map<String, dynamic> request) async =>
      McpServerInfo.fromJson(asMap(await _api.sendJson('POST', '/api/v1/mcp/servers', body: {...request, 'id': id})));

  Future<McpServerInfo> refreshMcpServer(String id) async =>
      McpServerInfo.fromJson(asMap(await _api.sendJson('POST', '/api/v1/mcp/servers/$id/refresh')));

  Future<void> deleteMcpServer(String id) async {
    await _api.sendJson('DELETE', '/api/v1/mcp/servers/$id');
  }

  /// Operations of a SOAP service read from its WSDL (a designer-only call that fetches the WSDL and calls nothing).
  Future<Map<String, dynamic>> describeConnector(Map<String, dynamic> request) async =>
      asMap(await _api.sendJson('POST', '/api/v1/connectors/describe', body: request));

  /// A real, read-only call of a connector node with sample inputs (the server refuses anything that could send or change something,
  /// and requires `confirm: true`).
  Future<Map<String, dynamic>> testConnector(Map<String, dynamic> request) async =>
      asMap(await _api.sendJson('POST', '/api/v1/connectors/test', body: request));

  /// Side-effect-free dry run of a mapper spec or an EIP pipeline.
  Future<Map<String, dynamic>> previewMapper(Map<String, dynamic> request) async =>
      asMap(await _api.sendJson('POST', '/api/v1/mapper/preview', body: request));
}
