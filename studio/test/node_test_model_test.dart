import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/node_test_model.dart';

// Not run in the environment that produced it (no Dart SDK there).

void main() {
  test('only reading operations are offered', () {
    expect(canTestNode({'connector': 'github', 'operation': 'getIssue'}), isTrue);
    expect(canTestNode({'connector': 'github', 'operation': 'createIssue'}), isFalse);
    expect(canTestNode({'connector': 'http'}), isTrue);
    expect(canTestNode({'connector': 'http', 'method': 'post'}), isFalse);
    expect(canTestNode({'connector': 'soap', 'mode': 'describe'}), isTrue);
    expect(canTestNode({'connector': 'soap', 'operation': 'GetPrice'}), isFalse);
    expect(canTestNode({'connector': 'slack', 'operation': 'send'}), isFalse);
    expect(canTestNode({}), isFalse);
    expect(canTestNode({'connector': 3}), isFalse);
  });

  test('testRequest keeps static settings and drops run-only keys', () {
    final r = testRequest({
      'connector': 'github',
      'operation': 'getIssue',
      'token': r'${secret:GH}',
      'allowedRepos': ['o/r'],
      'inputs': {'repo': 'x'},
      'inputMapping': {'a': 'b'},
      'retries': 3,
      'timeoutMs': 5000,
    }, {'repo': 'o/r', 'number': 1}, confirmed: true);
    expect(r['connector'], 'github');
    expect(r['confirm'], isTrue);
    expect(r['inputs'], {'repo': 'o/r', 'number': 1});
    expect(r['config'], {'operation': 'getIssue', 'token': r'${secret:GH}', 'allowedRepos': ['o/r'], 'timeoutMs': 5000});
    expect(testRequest({'connector': 'http'}, {}, confirmed: false)['confirm'], isFalse);
  });
}
