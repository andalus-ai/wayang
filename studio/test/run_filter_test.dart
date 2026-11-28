import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/runs/runs_screen.dart';
import 'package:wayang_studio/data/run_events.dart';
import 'package:wayang_studio/design_system/wds.dart';
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

  test('run statuses map to shared execution semantics without changing runtime status', () {
    expect(runExecutionStatus(RunStatus.running), WdsExecutionStatus.running);
    expect(runExecutionStatus(RunStatus.waiting), WdsExecutionStatus.queued);
    expect(runExecutionStatus(RunStatus.completed), WdsExecutionStatus.succeeded);
    expect(runExecutionStatus(RunStatus.failed), WdsExecutionStatus.failed);
    expect(runExecutionStatus(RunStatus.cancelled), WdsExecutionStatus.cancelled);
  });

  test('run event types map to WDS timeline states', () {
    expect(runEventExecutionStatus(const RunEventView(type: 'NODE_STARTED', runId: 'r1')), WdsExecutionStatus.running);
    expect(runEventExecutionStatus(const RunEventView(type: 'NODE_COMPLETED', runId: 'r1')), WdsExecutionStatus.succeeded);
    expect(runEventExecutionStatus(const RunEventView(type: 'NODE_FAILED', runId: 'r1')), WdsExecutionStatus.failed);
    expect(runEventExecutionStatus(const RunEventView(type: 'RUN_CANCELLED', runId: 'r1')), WdsExecutionStatus.cancelled);
    expect(runEventExecutionStatus(const RunEventView(type: 'CUSTOM_EVENT', runId: 'r1')), WdsExecutionStatus.pending);
    expect(runEventExecutionStatus(const RunEventView(type: 'RUN_INCOMPLETE', runId: 'r1')), WdsExecutionStatus.pending);
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
