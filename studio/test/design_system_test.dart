import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/design_system/wds.dart';

void main() {
  group('Wayang Design System', () {
    test('exposes a consistent spacing and responsive scale', () {
      expect(WdsSpacing.md, 12);
      expect(WdsRadius.lg, 16);
      expect(WdsBreakpoint.expanded, greaterThan(WdsBreakpoint.medium));
      expect(WdsLayout.inspectorWidth, greaterThan(0));
    });

    testWidgets('WdsButton invokes its action', (tester) async {
      var pressed = false;
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: WdsButton(label: 'Create workflow', onPressed: () => pressed = true),
        ),
      ));
      await tester.tap(find.text('Create workflow'));
      expect(pressed, isTrue);
    });

    testWidgets('WdsButton disables interaction while loading', (tester) async {
      var pressed = false;
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: WdsButton(label: 'Saving', isLoading: true, onPressed: () => pressed = true),
        ),
      ));
      await tester.tap(find.byType(FilledButton));
      expect(pressed, isFalse);
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
    });

    testWidgets('WdsBadge renders semantic label', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: const Scaffold(body: WdsBadge(label: 'Published', tone: WdsBadgeTone.success)),
      ));
      expect(find.text('Published'), findsOneWidget);
    });

    testWidgets('WdsEmptyState uses the supplied icon and action', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: WdsEmptyState(
            icon: Icons.account_tree_outlined,
            title: 'No workflows',
            description: 'Create your first workflow.',
            action: FilledButton(onPressed: () {}, child: const Text('Create')),
          ),
        ),
      ));
      expect(find.byIcon(Icons.account_tree_outlined), findsOneWidget);
      expect(find.text('No workflows'), findsOneWidget);
      expect(find.text('Create'), findsOneWidget);
    });
  });
}
