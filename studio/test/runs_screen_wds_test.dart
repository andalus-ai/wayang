import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/design_system/wds.dart';
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
}
