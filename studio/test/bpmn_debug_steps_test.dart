import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/bpmn/debug_steps.dart';

void main() {
  const path = ['start', 'review', 'approve', 'end'];

  test('the whole path is marked until a step is chosen', () {
    const s = DebugSteps(path);
    expect(s.all, isTrue);
    expect(s.current, isNull);
    expect(s.marked, path.toSet());
  });

  test('next from the whole path starts at the first node, then walks to the end and stops', () {
    var s = const DebugSteps(path).next();
    expect(s.current, 'start');
    expect(s.marked, {'start'});
    s = s.next().next();
    expect(s.current, 'approve');
    expect(s.marked, {'start', 'review', 'approve'});
    s = s.next().next().next();
    expect(s.current, 'end');
  });

  test('previous from the whole path lands on the last node and stops at the first', () {
    var s = const DebugSteps(path).previous();
    expect(s.current, 'end');
    s = s.previous().previous().previous().previous();
    expect(s.current, 'start');
  });

  test('an empty path never throws', () {
    const s = DebugSteps([]);
    expect(s.next().current, isNull);
    expect(s.previous().marked, isEmpty);
  });

  test('a live run that grows keeps the position, and "all" stays "all"', () {
    final stepped = const DebugSteps(['a', 'b']).next().next();
    expect(stepped.withExecuted(['a', 'b', 'c']).current, 'b');
    expect(const DebugSteps(['a']).withExecuted(['a', 'b']).all, isTrue);
    expect(stepped.showAll().all, isTrue);
  });
}
