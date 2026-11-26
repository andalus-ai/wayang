import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/tasks/tasks_screen.dart';
import 'package:wayang_studio/models/wayang.dart';

TaskView t(String id, String kind, {String? title, DateTime? due, String? assignee, String run = 'r1'}) =>
    TaskView(token: id, runId: run, nodeId: 'n$id', kind: kind, title: title, dueAt: due, assignee: assignee);

void main() {
  final now = DateTime.utc(2026, 11, 25, 12);
  final late = t('a', 'human', title: 'Approve loan', due: now.subtract(const Duration(hours: 2)), assignee: 'dewi');
  final soon = t('b', 'human', title: 'Review contract', due: now.add(const Duration(hours: 1)));
  final noDue = t('c', 'message', title: 'Payment received');
  final timer = t('d', 'timer', title: 'Reminder', run: 'r9');
  final all = [noDue, soon, timer, late];

  test('overdue first, then by due time, then the rest in their order', () {
    expect(visibleTasks(all, const TaskFilter(), now).map((e) => e.token), ['a', 'b', 'c', 'd']);
    expect(isOverdue(late, now), isTrue);
    expect(isOverdue(soon, now), isFalse);
    expect(isOverdue(noDue, now), isFalse);
  });

  test('search matches every word across title, assignee, run and node', () {
    expect(visibleTasks(all, const TaskFilter(query: 'approve dewi'), now).map((e) => e.token), ['a']);
    expect(visibleTasks(all, const TaskFilter(query: 'R9'), now).map((e) => e.token), ['d']);
    expect(visibleTasks(all, const TaskFilter(query: 'nope'), now), isEmpty);
  });

  test('the kind filter narrows to one kind', () {
    expect(visibleTasks(all, const TaskFilter(kind: 'human'), now).map((e) => e.token), ['a', 'b']);
    expect(visibleTasks(all, const TaskFilter(kind: 'message', query: 'payment'), now).map((e) => e.token), ['c']);
  });
}
