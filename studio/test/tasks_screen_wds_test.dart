import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/design_system/wds.dart';
import 'package:wayang_studio/features/tasks/tasks_screen.dart';
import 'package:wayang_studio/models/wayang.dart';

void main() {
  testWidgets('Tasks uses WDS inbox controls and semantic task badges', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        tasksProvider.overrideWith((ref) async => [
          TaskView(
            token: 't1',
            runId: 'r1',
            nodeId: 'approve',
            kind: 'human',
            title: 'Approve invoice',
            assignee: 'operator',
            dueAt: DateTime.now().subtract(const Duration(hours: 1)),
          ),
        ]),
      ],
      child: const MaterialApp(home: Scaffold(body: TasksScreen())),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(WdsPageHeader), findsOneWidget);
    expect(find.byType(WdsFilterToolbar), findsOneWidget);
    expect(find.byType(WdsSearchField), findsOneWidget);
    expect(find.byType(WdsSurface), findsOneWidget);
    expect(find.byType(WdsBadge), findsNWidgets(2));
    expect(find.byType(WdsButton), findsNWidgets(2));
    expect(find.text('OVERDUE'), findsOneWidget);
  });
}
