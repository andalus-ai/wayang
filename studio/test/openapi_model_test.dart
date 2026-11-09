import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/openapi_model.dart';

void main() {
  test('describe request carries the document and filters only', () {
    final r = openApiDescribeRequest({
      'specUrl': ' https://x.example/o.json ',
      'includeTags': ['pets'],
      'headers': {'Authorization': 'Bearer \${secret:T}'},
      'baseUrl': 'https://other.example',
      'operation': 'deletePet',
    })!;
    expect(r['connector'], 'openapi');
    expect(r['config'], {'specUrl': 'https://x.example/o.json', 'includeTags': ['pets']});
  });

  test('url wins over inline spec; nothing set gives null', () {
    expect(openApiDescribeRequest({'spec': '{}', 'specUrl': 'https://x/y'})!['config'], {'specUrl': 'https://x/y'});
    expect(openApiDescribeRequest({'spec': '{}'})!['config'], {'spec': '{}'});
    expect(openApiDescribeRequest({'spec': '  '}), isNull);
    expect(openApiDescribeRequest({}), isNull);
  });

  test('parses operations with required arguments', () {
    final ops = parseOpenApiOperations({
      'operations': [
        {
          'name': 'getPet',
          'description': 'Find',
          'method': 'get',
          'path': '/pets/{id}',
          'inputSchema': {
            'properties': {'id': {}, 'verbose': {}},
            'required': ['id'],
          },
        },
      ],
    });
    expect(ops.single.method, 'GET');
    expect(ops.single.args, ['id', 'verbose']);
    expect(ops.single.required, ['id']);
    expect(ops.single.changesData, isFalse);
  });

  test('choosing a read keeps methods; choosing a delete widens only that method', () {
    const get = OpenApiOperation(name: 'getPet', description: '', method: 'GET', path: '/p', args: [], required: []);
    const del = OpenApiOperation(name: 'deletePet', description: '', method: 'DELETE', path: '/p', args: [], required: []);
    expect(withOpenApiOperation({'a': 1}, get), {'a': 1, 'operation': 'getPet'});
    final out = withOpenApiOperation({'methods': ['GET']}, del);
    expect(out['operation'], 'deletePet');
    expect(out['methods'], ['GET', 'DELETE']);
    expect(withOpenApiOperation({}, del)['methods'], ['GET', 'HEAD', 'DELETE']);
  });
}
