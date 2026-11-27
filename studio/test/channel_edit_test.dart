import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/channel_edit.dart';

void main() {
  test('trigger ids follow the server rule', () {
    expect(triggerIdProblem('telegram-me', const []), isNull);
    expect(triggerIdProblem('  ', const []), isNotNull);
    expect(triggerIdProblem('a b', const []), isNotNull);
    expect(triggerIdProblem('a.b', const []), isNotNull);
    expect(triggerIdProblem('x' * 65, const []), isNotNull);
    expect(triggerIdProblem('x' * 64, const []), isNull);
    expect(triggerIdProblem('t1', const ['t1']), isNotNull);
  });

  test('connection form checks every field', () {
    String? p({String id = 'work', String c = 'cid', String s = 'GOOGLE_CLIENT_SECRET', int n = 1, List<String> e = const []}) =>
        connectionProblem(id: id, clientId: c, secretName: s, scopeCount: n, existing: e);
    expect(p(), isNull);
    expect(p(id: ''), isNotNull);
    expect(p(id: 'a.b'), isNotNull);
    expect(p(id: 'work', e: ['work']), isNotNull);
    expect(p(c: ' '), isNotNull);
    expect(p(s: ''), isNotNull);
    expect(p(s: 'has space'), isNotNull);
    expect(p(n: 0), isNotNull);
  });
}
