/// Operations the server allows in "Test node" (it re-checks; this only decides whether to offer the button).
/// Keep in step with `ConnectorTester.refusal` on the server.
bool canTestNode(Map<String, dynamic> props) {
  final connector = props['connector'];
  if (connector is! String) return false;
  final op = props['operation'];
  switch (connector) {
    case 'http':
      return '${props['method'] ?? 'GET'}'.toUpperCase() == 'GET';
    case 'github':
      return op == 'getIssue' || op == 'listIssues';
    case 'notion':
      return op == 'queryDatabase';
    case 'gcal':
      return op == 'list';
    case 'gsheets':
      return op == 'read';
    case 'trello':
      return op == 'listCards';
    case 'todoist':
      return op == 'listTasks';
    case 'linear':
      return op == 'listIssues';
    case 'airtable':
      return op == 'listRecords';
    case 'mcp':
      return op == 'listTools';
    case 'soap':
      return props['mode'] == 'describe';
    default:
      return false;
  }
}

/// The request for the test call: the node's static settings as `config`, the sample inputs as `inputs`, and the confirmation.
/// Keys that only describe how a run treats the node (retries, timeouts, mappings) are left out.
Map<String, dynamic> testRequest(Map<String, dynamic> props, Map<String, dynamic> inputs, {required bool confirmed}) {
  const runOnly = {'connector', 'inputs', 'inputMapping', 'outputMapping', 'outputMode', 'omitNull', 'retries', 'retryDelayMs', 'timeoutMs', 'onError'};
  return {
    'connector': props['connector'],
    'config': {
      for (final e in props.entries)
        if (!runOnly.contains(e.key)) e.key: e.value,
      if (props['timeoutMs'] != null) 'timeoutMs': props['timeoutMs'],
    },
    'inputs': inputs,
    'confirm': confirmed,
  };
}
