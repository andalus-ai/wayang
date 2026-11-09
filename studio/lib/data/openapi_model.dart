/// One operation of an OpenAPI/Swagger document as the server lists it (`POST /api/v1/connectors/describe` with
/// `connector: openapi`; the connector's `listOperations` shape).
class OpenApiOperation {
  const OpenApiOperation({required this.name, required this.description, required this.method, required this.path, required this.args, required this.required});

  factory OpenApiOperation.fromJson(Map<String, dynamic> j) {
    final schema = j['inputSchema'] is Map ? Map<String, dynamic>.from(j['inputSchema'] as Map) : <String, dynamic>{};
    final props = schema['properties'] is Map ? Map<String, dynamic>.from(schema['properties'] as Map) : <String, dynamic>{};
    return OpenApiOperation(
      name: '${j['name']}',
      description: '${j['description'] ?? ''}',
      method: '${j['method'] ?? ''}'.toUpperCase(),
      path: '${j['path'] ?? ''}',
      args: props.keys.toList(),
      required: [for (final r in (schema['required'] as List? ?? const [])) '$r'],
    );
  }

  final String name;
  final String description;
  final String method;
  final String path;
  final List<String> args;
  final List<String> required;

  /// Anything but GET/HEAD changes something on the other side; the picker marks it so it is not chosen by accident.
  bool get changesData => method != 'GET' && method != 'HEAD';
}

/// Keys of the node that decide which operations are listed. Headers, query, baseUrl and credentials are deliberately not here.
const _listKeys = ['methods', 'includeTags', 'excludeTags', 'includePaths', 'excludePaths'];

/// The request for the describe call: the node's `spec` or `specUrl` plus the filters. Null when neither is set. When both are
/// set, the URL wins, so the call is never refused for being ambiguous.
Map<String, dynamic>? openApiDescribeRequest(Map<String, dynamic> props) {
  final url = props['specUrl'];
  final spec = props['spec'];
  final config = <String, dynamic>{};
  if (url is String && url.trim().isNotEmpty) {
    config['specUrl'] = url.trim();
  } else if (spec is String && spec.trim().isNotEmpty) {
    config['spec'] = spec;
  } else {
    return null;
  }
  for (final k in _listKeys) {
    final v = props[k];
    if (v is List && v.isNotEmpty) config[k] = v;
  }
  return {'connector': 'openapi', 'config': config};
}

List<OpenApiOperation> parseOpenApiOperations(Map<String, dynamic> response) => [
      for (final o in (response['operations'] as List? ?? const [])) OpenApiOperation.fromJson(Map<String, dynamic>.from(o as Map)),
    ];

/// The node properties with [op] chosen. A mutating operation also widens `methods` (the connector refuses it otherwise),
/// and only for that method, so picking one DELETE never opens up the rest.
Map<String, dynamic> withOpenApiOperation(Map<String, dynamic> props, OpenApiOperation op) {
  final out = {...props, 'operation': op.name};
  if (op.changesData && op.method.isNotEmpty) {
    final methods = <String>[for (final m in (props['methods'] as List? ?? const ['GET', 'HEAD'])) '$m'.toUpperCase()];
    if (!methods.contains(op.method)) methods.add(op.method);
    out['methods'] = methods;
  }
  return out;
}
