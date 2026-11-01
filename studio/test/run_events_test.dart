import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/run_events.dart';

void main() {
  test('eventsUri maps http to ws and keeps the base path', () {
    expect(eventsUri('http://localhost:8080', 't1').toString(), 'ws://localhost:8080/ws/events?ticket=t1');
    expect(eventsUri('https://x.example/wayang/', 'a b').toString(), 'wss://x.example/wayang/ws/events?ticket=a+b');
  });

  test('RunEventView parses an event and tolerates missing fields', () {
    final e = RunEventView.fromJson({'type': 'NODE_COMPLETED', 'runId': 'r1', 'nodeId': 'n', 'time': '2026-10-24T10:00:00Z', 'data': {}});
    expect(e.type, 'NODE_COMPLETED');
    expect(e.nodeId, 'n');
    expect(e.time, isNotNull);
    expect(RunEventView.fromJson({}).type, 'UNKNOWN');
    expect(RunEventView.fromJson({'type': 'OVERFLOW'}).isOverflow, isTrue);
  });
}
