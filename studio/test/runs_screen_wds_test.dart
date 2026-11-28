import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/design_system/wds.dart';
import 'package:wayang_studio/data/run_events.dart';
import 'package:wayang_studio/features/runs/runs_screen.dart';
import 'package:wayang_studio/models/wayang.dart';

void main() {
  testWidgets('Runs uses WDS page, filters and empty state', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        runsProvider.overrideWith((ref) async => <RunView>[]),
      ],
      child: const MaterialApp(home: Scaffold(body: RunsScreen())),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(WdsPageHeader), findsOneWidget);
    expect(find.byType(WdsFilterToolbar), findsOneWidget);
    expect(find.byType(WdsSearchField), findsOneWidget);
    expect(find.byType(WdsButton), findsOneWidget);
    expect(find.byType(WdsEmptyState), findsOneWidget);
  });
  testWidgets('selected run renders the shared event timeline', (tester) async {
    final run = RunView(
      id: 'r1',
      processId: 'orders',
      version: 2,
      status: RunStatus.running,
      executedNodes: const ['validate', 'charge'],
    );
    await tester.pumpWidget(ProviderScope(
      overrides: [
        runsProvider.overrideWith((ref) async => [run]),
        selectedRunIdProvider.overrideWith((ref) => 'r1'),
        runDetailProvider('r1').overrideWith((ref) => Stream.value(run)),
        runEventsProvider('r1').overrideWith((ref) => Stream.value(
          RunEventView(
            type: 'NODE_STARTED',
            runId: 'r1',
            nodeId: 'charge',
            time: DateTime(2026, 11, 28, 10, 30),
          ),
        )),
      ],
      child: const MaterialApp(home: Scaffold(body: RunsScreen())),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 20));

    expect(find.byType(WdsExecutionStatusIndicator), findsWidgets);
    expect(find.byType(WdsExecutionTimeline), findsOneWidget);
    expect(find.text('NODE_STARTED'), findsOneWidget);
    expect(find.text('charge'), findsWidgets);
  });

}
