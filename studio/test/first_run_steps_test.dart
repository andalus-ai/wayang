import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/dashboard/dashboard_screen.dart';

void main() {
  test('a new workspace sees all three steps, the first done once connected', () {
    final s = firstRunSteps(connected: true, processes: 0, runs: 0);
    expect(s.map((e) => e.done), [true, false, false]);
    expect(s.map((e) => e.navKey), ['settings', 'bpmn', 'runs']);
  });

  test('not connected: nothing is marked done', () {
    expect(firstRunSteps(connected: false, processes: 0, runs: 0).every((e) => !e.done), isTrue);
  });

  test('progress is tracked and the guide disappears when everything is done', () {
    expect(firstRunSteps(connected: true, processes: 2, runs: 0).map((e) => e.done), [true, true, false]);
    expect(firstRunSteps(connected: true, processes: 2, runs: 1), isEmpty);
  });

  test('a design-only server has no run step', () {
    expect(firstRunSteps(connected: true, processes: 0, runs: 0, executes: false).map((e) => e.navKey), ['settings', 'bpmn']);
    expect(firstRunSteps(connected: true, processes: 1, runs: 0, executes: false), isEmpty);
  });
}
