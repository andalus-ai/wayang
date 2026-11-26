import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/runs/runs_screen.dart';
import 'package:wayang_studio/models/wayang.dart';

RunView r(String id, String process, RunStatus s) => RunView(id: id, processId: process, version: 1, status: s);

void main() {
  final all = [
    r('run-1', 'order-intake', RunStatus.completed),
    r('run-2', 'order-refund', RunStatus.failed),
    r('run-3', 'kyc-check', RunStatus.failed),
  ];

  test('no query and no status keeps every run', () {
    expect(filterRuns(all, '', null).length, 3);
  });

  test('the status narrows, and the words must all match process or run id', () {
    expect(filterRuns(all, '', RunStatus.failed).map((e) => e.id), ['run-2', 'run-3']);
    expect(filterRuns(all, 'ORDER', null).map((e) => e.id), ['run-1', 'run-2']);
    expect(filterRuns(all, 'order refund', null).map((e) => e.id), ['run-2']);
    expect(filterRuns(all, 'order', RunStatus.failed).map((e) => e.id), ['run-2']);
    expect(filterRuns(all, 'run-3', null).map((e) => e.id), ['run-3']);
    expect(filterRuns(all, 'zzz', null), isEmpty);
  });
}
