/// One operation of a SOAP service as the server reports it (`POST /api/v1/connectors/describe`).
class SoapOperation {
  const SoapOperation({required this.name, required this.soapAction, required this.style, required this.input});

  factory SoapOperation.fromJson(Map<String, dynamic> j) => SoapOperation(
        name: '${j['name']}',
        soapAction: '${j['soapAction'] ?? ''}',
        style: '${j['style'] ?? 'document'}',
        input: [for (final i in (j['input'] as List? ?? const [])) '$i'],
      );

  final String name;
  final String soapAction;
  final String style;
  final List<String> input;
}

/// The request for the describe call, built only from the node's `wsdl`, `endpoint` and `port`: credentials, headers and
/// everything else stay out of it, and the server ignores them anyway. Null when there is no WSDL yet.
Map<String, dynamic>? describeRequest(Map<String, dynamic> props) {
  final wsdl = props['wsdl'];
  if (wsdl is! String || wsdl.trim().isEmpty) return null;
  final config = <String, dynamic>{'wsdl': wsdl.trim()};
  for (final k in ['endpoint', 'port']) {
    final v = props[k];
    if (v is String && v.trim().isNotEmpty) config[k] = v.trim();
  }
  return {'connector': 'soap', 'config': config};
}

List<SoapOperation> parseOperations(Map<String, dynamic> response) => [
      for (final o in (response['operations'] as List? ?? const [])) SoapOperation.fromJson(Map<String, dynamic>.from(o as Map)),
    ];

/// The node properties with [name] chosen as the operation and added (once) to the required allow-list.
Map<String, dynamic> withOperation(Map<String, dynamic> props, String name) {
  final allowed = <String>[
    for (final a in (props['allowedOperations'] as List? ?? const [])) '$a',
  ];
  if (!allowed.contains(name)) allowed.add(name);
  return {...props, 'operation': name, 'allowedOperations': allowed};
}
