import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/soap_model.dart';

// Not run in the environment that produced it (no Dart SDK there).

void main() {
  test('describeRequest sends only wsdl, endpoint and port, trimmed', () {
    final r = describeRequest({
      'connector': 'soap',
      'wsdl': ' https://erp/svc?wsdl ',
      'endpoint': 'https://erp/svc',
      'port': '  ',
      'username': 'u',
      'password': r'${secret:P}',
      'headers': {'Authorization': 'x'},
      'operation': 'GetPrice',
    })!;
    expect(r['connector'], 'soap');
    expect(r['config'], {'wsdl': 'https://erp/svc?wsdl', 'endpoint': 'https://erp/svc'});
  });

  test('describeRequest is null without a WSDL', () {
    expect(describeRequest({}), isNull);
    expect(describeRequest({'wsdl': '  '}), isNull);
    expect(describeRequest({'wsdl': 3}), isNull);
  });

  test('parseOperations reads the server answer', () {
    final ops = parseOperations({
      'operations': [
        {'name': 'GetPrice', 'soapAction': 'urn:s#GetPrice', 'style': 'document', 'input': ['GetPrice']},
        {'name': 'Add', 'input': ['a', 'b']},
      ]
    });
    expect(ops.map((o) => o.name), ['GetPrice', 'Add']);
    expect(ops[0].soapAction, 'urn:s#GetPrice');
    expect(ops[1].style, 'document');
    expect(ops[1].input, ['a', 'b']);
    expect(parseOperations({}), isEmpty);
  });

  test('withOperation sets the operation and adds it once to the allow-list, keeping the rest', () {
    final a = withOperation({'wsdl': 'w', 'allowedOperations': ['Other']}, 'GetPrice');
    expect(a['operation'], 'GetPrice');
    expect(a['allowedOperations'], ['Other', 'GetPrice']);
    expect(a['wsdl'], 'w');
    final b = withOperation(a, 'GetPrice');
    expect(b['allowedOperations'], ['Other', 'GetPrice']);
    expect(withOperation({}, 'X')['allowedOperations'], ['X']);
  });
}
