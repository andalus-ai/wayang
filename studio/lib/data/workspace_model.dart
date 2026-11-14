import 'dart:convert';

/// Workspace bundles and settings, as the Studio shows them. Pure Dart: the server is the authority on every rule here
/// (see `docs/WORKSPACE.md`); this only reads its answers and builds its requests.

/// How an import should behave; the server's defaults are a dry run that fails on conflicts.
class ImportOptions {
  const ImportOptions({
    this.dryRun = true,
    this.onConflict = 'fail',
    this.targetProject,
    this.publish = false,
    this.triggers = true,
    this.projectConfig = true,
    this.tenantConfig = false,
    this.allowUnsigned = false,
  });

  final bool dryRun;
  final String onConflict; // fail | skip | overwrite
  final String? targetProject; // null: a new project is created
  final bool publish;
  final bool triggers;
  final bool projectConfig;
  final bool tenantConfig;
  final bool allowUnsigned;

  static const conflictModes = ['fail', 'skip', 'overwrite'];

  ImportOptions copyWith({
    bool? dryRun,
    String? onConflict,
    Object? targetProject = _keep,
    bool? publish,
    bool? triggers,
    bool? projectConfig,
    bool? tenantConfig,
    bool? allowUnsigned,
  }) =>
      ImportOptions(
        dryRun: dryRun ?? this.dryRun,
        onConflict: onConflict ?? this.onConflict,
        targetProject: identical(targetProject, _keep) ? this.targetProject : targetProject as String?,
        publish: publish ?? this.publish,
        triggers: triggers ?? this.triggers,
        projectConfig: projectConfig ?? this.projectConfig,
        tenantConfig: tenantConfig ?? this.tenantConfig,
        allowUnsigned: allowUnsigned ?? this.allowUnsigned,
      );

  static const Object _keep = Object();

  Map<String, dynamic> toJson() => {
        'dryRun': dryRun,
        'onConflict': onConflict,
        if (targetProject != null) 'targetProject': targetProject,
        'publish': publish,
        'triggers': triggers,
        'projectConfig': projectConfig,
        'tenantConfig': tenantConfig,
        'allowUnsigned': allowUnsigned,
      };

  /// Whether two option sets would do the same thing apart from dry-run: "Apply" is only offered for what was checked.
  bool sameAs(ImportOptions o) =>
      onConflict == o.onConflict &&
      targetProject == o.targetProject &&
      publish == o.publish &&
      triggers == o.triggers &&
      projectConfig == o.projectConfig &&
      tenantConfig == o.tenantConfig &&
      allowUnsigned == o.allowUnsigned;
}

class ImportItem {
  const ImportItem({required this.type, required this.id, required this.action, required this.status, this.message = ''});
  final String type;
  final String id;
  final String action;
  final String status; // ok | done | skipped | error
  final String message;

  bool get isError => status == 'error';

  factory ImportItem.fromJson(Map<String, dynamic> j) => ImportItem(
        type: '${j['type'] ?? ''}',
        id: '${j['id'] ?? ''}',
        action: '${j['action'] ?? ''}',
        status: '${j['status'] ?? ''}',
        message: '${j['message'] ?? ''}',
      );
}

class ImportReport {
  const ImportReport({
    required this.dryRun,
    required this.ok,
    required this.items,
    required this.problems,
    required this.warnings,
    required this.missingSecrets,
    required this.secretsChecked,
    required this.missingConfig,
    required this.oauthConnections,
    required this.webhookSecrets,
    this.project,
  });

  final bool dryRun;
  final bool ok;
  final String? project;
  final List<ImportItem> items;
  final List<String> problems;
  final List<String> warnings;
  final List<String> missingSecrets;
  final bool secretsChecked;
  final List<String> missingConfig;
  final List<String> oauthConnections;

  /// Webhook trigger id to its new secret: shown once, never again.
  final Map<String, String> webhookSecrets;

  /// A dry run that found nothing wrong: the only thing "Apply" is offered after.
  bool get canApply => dryRun && ok;

  /// What the target environment still needs before the imported workflows can run.
  bool get needsSetup => missingSecrets.isNotEmpty || missingConfig.isNotEmpty || oauthConnections.isNotEmpty;

  factory ImportReport.fromJson(Map<String, dynamic> j) {
    List<String> strs(Object? v) => [for (final e in (v as List? ?? const [])) '$e'];
    return ImportReport(
      dryRun: j['dryRun'] == true,
      ok: j['ok'] == true,
      project: j['project'] as String?,
      items: [for (final e in (j['items'] as List? ?? const [])) ImportItem.fromJson(Map<String, dynamic>.from(e as Map))],
      problems: strs(j['problems']),
      warnings: strs(j['warnings']),
      missingSecrets: strs(j['missingSecrets']),
      secretsChecked: j['secretsChecked'] == true,
      missingConfig: strs(j['missingConfig']),
      oauthConnections: strs(j['oauthConnections']),
      webhookSecrets: {for (final e in (j['webhookSecrets'] as Map? ?? const {}).entries) '${e.key}': '${e.value}'},
    );
  }
}

/// One plain setting. [project] is null for the tenant-wide scope.
class ConfigItem {
  const ConfigItem({required this.key, required this.value, required this.version, this.project, this.updatedBy});
  final String key;
  final String value;
  final int version;
  final String? project;
  final String? updatedBy;

  factory ConfigItem.fromJson(Map<String, dynamic> j) => ConfigItem(
        key: '${j['key']}',
        value: '${j['value'] ?? ''}',
        version: (j['version'] as num?)?.toInt() ?? 0,
        project: j['project'] as String?,
        updatedBy: j['updatedBy'] as String?,
      );
}

/// Names that the server refuses for a setting because they look like credentials: warn before sending.
final _credentialLike = RegExp(r'(token|secret|password|passwd|apikey|api_key|private|credential)', caseSensitive: false);

/// A message when [key] or [value] would be refused, else null. The server re-checks everything.
String? configProblem(String key, String value) {
  if (!RegExp(r'^[A-Za-z0-9_.-]{1,100}$').hasMatch(key)) return 'Use 1-100 letters, digits, "_", "." or "-".';
  if (_credentialLike.hasMatch(key)) return 'This looks like a credential: store it as a secret and use \${secret:NAME}.';
  if (value.length > 4096) return 'At most 4096 characters.';
  if (value.contains(r'${')) return 'A value may not contain \${...}: references are not expanded inside settings.';
  return null;
}

/// A line for the import preview: what is in a bundle, without parsing its definitions.
String bundleSummary(Map<String, dynamic> bundle) {
  int n(Object? v) => v is List ? v.length : 0;
  final cfg = bundle['config'];
  final settings = cfg is Map ? n2(cfg['project']) + n2(cfg['tenant']) : 0;
  final name = (bundle['project'] is Map ? (bundle['project'] as Map)['name'] : null) ?? 'workspace';
  return '$name: ${n(bundle['definitions'])} definition(s), ${n(bundle['triggers'])} trigger(s), $settings setting(s)';
}

int n2(Object? v) => v is Map ? v.length : 0;

/// Whether [text] is worth sending as a bundle at all (the server checks the format and integrity).
bool looksLikeBundle(Map<String, dynamic> m) => m['format'] == 'wayang.workspace' && m['formatVersion'] != null;

/// `kind:id` selectors from free text (commas, spaces or new lines between them): trimmed, lower-cased, no blanks, no repeats.
List<String> parseSelectors(String text) => <String>{
      for (final s in text.split(RegExp(r'[,\s]+'))) if (s.trim().isNotEmpty) s.trim().toLowerCase(),
    }.toList();

/// Whether a bundle only holds definitions that [selectors] name. A server that does not know the `only` parameter ignores it and sends
/// everything: this tells Studio so, instead of letting a person believe the bundle is narrowed.
bool selectionHonoured(Map<String, dynamic> bundle, List<String> selectors) {
  if (selectors.isEmpty) return true;
  final defs = bundle['definitions'];
  if (defs is! List) return true;
  for (final d in defs) {
    if (d is! Map) continue;
    final key = '${d['kind']}:${d['id']}'.toLowerCase();
    if (!selectors.contains(key)) return false;
  }
  return true;
}

/// One line about what a bundle holds, for showing before it is sent or after it is exported: project, definitions by kind, triggers, what
/// the target must provide, and whether it is signed. Reads only what is there; nothing is checked (the server does that).
String bundleDigest(Map<String, dynamic> b) {
  int count(Object? v) => v is List ? v.length : 0;
  final parts = <String>[];
  final project = b['project'];
  final name = project is Map ? project['name'] : null;
  if (name is String && name.trim().isNotEmpty) parts.add(name.trim());
  final byKind = <String, int>{};
  final defs = b['definitions'];
  if (defs is List) {
    for (final d in defs) {
      if (d is Map && d['kind'] is String) byKind.update((d['kind'] as String).toLowerCase(), (n) => n + 1, ifAbsent: () => 1);
    }
  }
  final total = count(defs);
  parts.add('$total definition${total == 1 ? '' : 's'}${byKind.isEmpty ? '' : ' (${byKind.entries.map((e) => '${e.value} ${e.key}').join(', ')})'}');
  final t = count(b['triggers']);
  if (t > 0) parts.add('$t trigger${t == 1 ? '' : 's'}');
  final req = b['requires'];
  if (req is Map) {
    final secrets = req['secrets'];
    if (secrets is List && secrets.isNotEmpty) parts.add('needs secrets: ${secrets.whereType<String>().join(', ')}');
    final cfg = req['config'];
    if (cfg is List && cfg.isNotEmpty) parts.add('needs settings: ${cfg.whereType<String>().join(', ')}');
  }
  final integrity = b['integrity'];
  parts.add(integrity is Map && integrity['hmac'] is String ? 'signed' : 'unsigned');
  return parts.join(' · ');
}

/// How far a master-key rotation has got, from `GET /api/v1/secrets/keys`. No secret data.
class KeyStatus {
  const KeyStatus({required this.activeKeyId, required this.secretsByKey, required this.needRotation});

  final String activeKeyId;
  final Map<String, int> secretsByKey;
  final int needRotation;

  factory KeyStatus.fromJson(Map<String, dynamic> j) {
    final by = <String, int>{};
    final raw = j['secretsByKey'];
    if (raw is Map) {
      raw.forEach((k, v) {
        if (v is num) by['$k'] = v.toInt();
      });
    }
    final n = j['needRotation'];
    return KeyStatus(activeKeyId: '${j['activeKeyId'] ?? ''}', secretsByKey: by, needRotation: n is num ? n.toInt() : 0);
  }

  int get total => secretsByKey.values.fold(0, (a, b) => a + b);

  /// One line for the secrets screen.
  String get summary {
    if (total == 0) return 'Active key ${activeKeyId.isEmpty ? '?' : activeKeyId}; no secrets stored yet.';
    final base = 'Active key $activeKeyId; $total secret(s)';
    return needRotation == 0 ? '$base, all sealed under it.' : '$base, $needRotation still under an older key (use Rotate keys).';
  }
}

/// What the server will refuse in a secret name, caught before the round trip. `null` = fine. The server decides.
String? secretNameProblem(String name) {
  if (name.isEmpty) return 'Enter a name';
  if (!RegExp(r'^[A-Za-z0-9_.-]{1,100}$').hasMatch(name)) return 'Use letters, digits, _ . - (up to 100)';
  if (name.startsWith('vault.')) return 'vault.* names are read from Vault, not stored here';
  if (name.startsWith('file.')) return 'file.* names are read from mounted files, not stored here';
  for (final c in const ['aws.', 'gcp.', 'azure.']) {
    if (name.startsWith(c)) return '$c* names are read from the cloud secret service, not stored here';
  }
  if (name.startsWith('oauth.') || name.startsWith('oauth-')) return 'oauth names are reserved';
  return null;
}

/// What a release is called and what it is (the second phase of a project: from designed to runnable without this server).
/// The server validates every field again; this only catches typos before the round trip.
class ReleaseForm {
  const ReleaseForm({
    this.target = 'SERVICE',
    this.groupId = 'com.example',
    this.artifactId = '',
    this.version = '1.0.0',
    this.packageName = '',
    this.port = 8080,
    this.profile = 'full',
  });

  final String target; // SERVICE | STANDALONE | GAMELAN
  final String groupId;
  final String artifactId; // empty: the server derives it from the project name
  final String version;
  final String packageName; // empty: derived
  final int port;
  final String profile; // full | automation | integration | agentic

  static const profiles = ['full', 'automation', 'integration', 'agentic'];
  static const targets = ['SERVICE', 'STANDALONE', 'GAMELAN'];

  ReleaseForm copyWith({String? target, String? groupId, String? artifactId, String? version, String? packageName, int? port, String? profile}) => ReleaseForm(
        target: target ?? this.target,
        groupId: groupId ?? this.groupId,
        artifactId: artifactId ?? this.artifactId,
        version: version ?? this.version,
        packageName: packageName ?? this.packageName,
        port: port ?? this.port,
        profile: profile ?? this.profile,
      );

  Map<String, dynamic> toJson() => {
        'target': target,
        'groupId': groupId,
        'version': version,
        'port': port,
        'profile': profile,
        if (artifactId.isNotEmpty) 'artifactId': artifactId,
        if (packageName.isNotEmpty) 'package': packageName,
      };

  /// First problem with the form, or null.
  String? get problem =>
      releaseFieldProblem('groupId', groupId) ??
      (artifactId.isEmpty ? null : releaseFieldProblem('artifactId', artifactId)) ??
      releaseFieldProblem('version', version) ??
      (packageName.isEmpty ? null : releaseFieldProblem('package', packageName)) ??
      (port < 1 || port > 65535 ? 'port must be 1..65535' : null);
}

const _javaKeywords = {
  'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'const', 'continue', 'default', 'do', 'double', 'else', 'enum',
  'extends', 'final', 'finally', 'float', 'for', 'goto', 'if', 'implements', 'import', 'instanceof', 'int', 'interface', 'long', 'native', 'new',
  'package', 'private', 'protected', 'public', 'return', 'short', 'static', 'strictfp', 'super', 'switch', 'synchronized', 'this', 'throw', 'throws',
  'transient', 'try', 'void', 'volatile', 'while', 'true', 'false', 'null', '_',
};

String? releaseFieldProblem(String field, String v) {
  final dotted = RegExp(r'^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$');
  switch (field) {
    case 'groupId':
      return v.length <= 100 && dotted.hasMatch(v) ? null : 'groupId: lower-case dotted name such as com.example';
    case 'package':
      if (v.length > 100 || !dotted.hasMatch(v)) return 'package: lower-case dotted name such as com.example.release';
      for (final part in v.split('.')) {
        if (_javaKeywords.contains(part)) return "package: '$part' is a Java keyword";
      }
      return null;
    case 'artifactId':
      return RegExp(r'^[a-z0-9][a-z0-9._-]{0,99}$').hasMatch(v) ? null : "artifactId: lower-case letters, digits, '.', '_' or '-'";
    case 'version':
      return RegExp(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$').hasMatch(v) ? null : "version: letters, digits, '.', '_' or '-'";
  }
  return null;
}

/// What a release would contain, from `POST /projects/{id}/release/plan`.
class ReleasePlan {
  const ReleasePlan({
    required this.target,
    required this.artifactId,
    required this.version,
    required this.processes,
    required this.components,
    required this.connectors,
    required this.usesAgents,
    required this.requiredSecrets,
    required this.requiredConfig,
    required this.files,
    required this.notes,
    required this.bundleSha256,
  });

  final String target;
  final String artifactId;
  final String version;
  final List<String> processes;
  final List<String> components;
  final List<String> connectors;
  final bool usesAgents;
  final List<String> requiredSecrets;
  final List<String> requiredConfig;
  final List<String> files;
  final List<String> notes;
  final String bundleSha256;

  static List<String> _strings(Object? o) => o is List ? [for (final x in o) '$x'] : const [];

  factory ReleasePlan.fromJson(Map<String, dynamic> j) => ReleasePlan(
        target: '${j['target'] ?? ''}',
        artifactId: '${j['artifactId'] ?? ''}',
        version: '${j['version'] ?? ''}',
        processes: _strings(j['processes']),
        components: _strings(j['components']),
        connectors: _strings(j['connectors']),
        usesAgents: j['usesAgents'] == true,
        requiredSecrets: _strings(j['requiredSecrets']),
        requiredConfig: _strings(j['requiredConfig']),
        files: _strings(j['files']),
        notes: _strings(j['notes']),
        bundleSha256: '${j['bundleSha256'] ?? ''}',
      );

  /// One line for the card.
  String get summary =>
      '${processes.length} process(es), ${components.length} component(s), ${connectors.isEmpty ? 'no connectors' : 'connectors: ${connectors.join(', ')}'}, '
      '${requiredSecrets.length} secret(s), ${requiredConfig.length} setting(s)';

  String get zipName => '$artifactId-$version.zip';
}

/// The command that downloads the release. The token is never put in the text: the operator's own shell supplies it.
String releaseDownloadCommand(String baseUrl, String projectId, ReleaseForm form, String zipName) {
  final base = baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;
  final body = jsonEncode(form.toJson());
  return "curl -sS -X POST '$base/api/v1/projects/$projectId/release' -H \"Authorization: Bearer \$WAYANG_TOKEN\" "
      "-H 'Content-Type: application/json' -d '${body.replaceAll("'", "'\\''")}' -o '$zipName'";
}

/// Parameters of the Telegram assistant template, checked before anything is sent (the server checks again).
String? assistantFieldProblem(String field, String value) {
  final v = value.trim();
  switch (field) {
    case 'chatId':
      return RegExp(r'^-?[0-9]{1,18}$').hasMatch(v) ? null : 'Your Telegram chat id: a number';
    case 'channelId':
      return RegExp(r'^[CDG][A-Z0-9]{2,20}$').hasMatch(v) ? null : 'A Slack channel id such as D0123ABCD';
    case 'model':
      return RegExp(r'^[a-z0-9]{2,20}/[A-Za-z0-9._:-]{1,80}$').hasMatch(v) ? null : 'provider/model, for example openai/gpt-4o-mini';
    case 'lookupUrl':
      return v.isEmpty || RegExp(r"^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~%!$&'()*+,;=:/-]*)?$").hasMatch(v) && v.length <= 300
          ? null
          : 'An https address without credentials, spaces or a query string';
    case 'lookupSecret':
      return v.isEmpty || RegExp(r'^[A-Za-z][A-Za-z0-9_]{0,63}$').hasMatch(v) ? null : 'A secret name: letters, digits, underscore';
    case 'lookupDescription':
      return v.length <= 200 ? null : 'At most 200 characters';
    case 'calendarConnection':
      return RegExp(r'^[A-Za-z0-9_-]{1,40}$').hasMatch(v) ? null : 'Letters, digits, - and _';
    case 'topic':
      return v.isNotEmpty && v.length <= 300 ? null : 'What the brief covers, up to 300 characters';
    case 'cron':
      return RegExp(r'^(@(hourly|daily|midnight|weekly|monthly|yearly|annually)|\S+( \S+){4})$', caseSensitive: false).hasMatch(v)
          ? null
          : 'Five fields, for example 0 8 * * 1-5';
    case 'timezone':
      return RegExp(r'^[A-Za-z][A-Za-z0-9_+/-]{0,60}$').hasMatch(v) ? null : 'A time zone such as Asia/Jakarta or UTC';
    case 'memoryTurns':
      {
        final n = int.tryParse(v);
        return n != null && n >= 0 && n <= 30 ? null : 'A whole number from 0 to 30 (0 = no memory)';
      }
    case 'imageModel':
      return RegExp(r'^[A-Za-z0-9._:/-]{1,100}$').hasMatch(v) ? null : 'A model name: letters, digits and . _ : / -';
    case 'emailRecipients':
      {
        if (v.isEmpty) return null;
        final parts = v.split(',').map((a) => a.trim()).toList();
        if (parts.length > 10) return 'At most 10 addresses';
        return parts.every((a) => RegExp(r'^[^\s@,]{1,64}@[^\s@,]{1,200}\.[^\s@,]{2,}$').hasMatch(a)) ? null : 'Comma-separated email addresses';
      }
    case 'persona':
      return v.length <= 1000 ? null : 'At most 1000 characters';
    case 'name':
      return v.isNotEmpty && v.length <= 80 ? null : '1 to 80 characters';
  }
  return null;
}

/// One parameter of a starter template, as `GET /api/v1/templates` describes it.
class TemplateParam {
  const TemplateParam({required this.name, required this.description, required this.required, this.defaultValue});

  factory TemplateParam.fromJson(Map<String, dynamic> j) => TemplateParam(
        name: '${j['name'] ?? ''}',
        description: '${j['description'] ?? ''}',
        required: j['required'] == true,
        defaultValue: j['defaultValue'] as String?,
      );

  final String name;
  final String description;
  final bool required;
  final String? defaultValue;

  /// A switch (`true` / `false`) rather than free text.
  bool get isFlag => defaultValue == 'true' || defaultValue == 'false';

  /// What is wrong with [value], or null. Rules the Studio knows by name are reused; the server checks again.
  String? problem(String value) {
    if (value.trim().isEmpty) return required ? 'Required' : null;
    return assistantFieldProblem(name, value);
  }
}

/// A starter template: what it is, which secrets it needs, and the parameters to ask for.
class TemplateInfo {
  const TemplateInfo({required this.id, required this.name, required this.description, required this.params, required this.secrets});

  factory TemplateInfo.fromJson(Map<String, dynamic> j) => TemplateInfo(
        id: '${j['id'] ?? ''}',
        name: '${j['name'] ?? ''}',
        description: '${j['description'] ?? ''}',
        params: [
          for (final p in (j['params'] is List ? j['params'] as List : const []))
            if (p is Map) TemplateParam.fromJson(Map<String, dynamic>.from(p)),
        ],
        secrets: [for (final s in (j['secrets'] is List ? j['secrets'] as List : const [])) '$s'],
      );

  final String id;
  final String name;
  final String description;
  final List<TemplateParam> params;
  final List<String> secrets;

  /// Per-parameter problems for [values] (parameter name to text); empty when the form can be sent.
  Map<String, String> problems(Map<String, String> values) => {
        for (final p in params)
          if (!p.isFlag && p.problem(values[p.name] ?? p.defaultValue ?? '') != null) p.name: p.problem(values[p.name] ?? p.defaultValue ?? '')!,
      };

  /// The request parameters: values that differ from the default, plus every required one. Blank optional text is left out so the
  /// server's default applies.
  Map<String, String> request(Map<String, String> values) => {
        for (final p in params)
          if (p.required || ((values[p.name] ?? '').trim().isNotEmpty && values[p.name]!.trim() != (p.defaultValue ?? '')))
            p.name: (values[p.name] ?? '').trim(),
      };
}
