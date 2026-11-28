import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/design_system/wds.dart';

void main() {
  group('WdsComponentShowcase', () {
    final examples = [
      WdsShowcaseExample(
        title: 'Primary action',
        category: 'Actions',
        description: 'A primary action button.',
        preview: const WdsButton(label: 'Run', onPressed: _noop),
        code: "WdsButton(label: 'Run', onPressed: onRun)",
        tags: const ['button', 'primary'],
      ),
      WdsShowcaseExample(
        title: 'Success badge',
        category: 'Feedback',
        description: 'A semantic success indicator.',
        preview: const WdsBadge(label: 'Ready', tone: WdsBadgeTone.success),
        code: "WdsBadge(label: 'Ready', tone: WdsBadgeTone.success)",
        tags: const ['status'],
      ),
    ];

    testWidgets('renders catalog examples and category filters', (tester) async {
      tester.view.physicalSize = const Size(1200, 1600);
      tester.view.devicePixelRatio = 1;
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: WdsComponentShowcase(examples: examples),
        ),
      ));

      expect(find.text('Primary action'), findsOneWidget);
      expect(find.text('Success badge'), findsOneWidget);
      await tester.tap(find.widgetWithText(ChoiceChip, 'Actions'));
      await tester.pumpAndSettle();
      expect(find.text('Primary action'), findsOneWidget);
      expect(find.text('Success badge'), findsNothing);
      tester.view.resetPhysicalSize();
    });

    testWidgets('search filters examples by title and tags', (tester) async {
      await tester.view.setPhysicalSize(const Size(1200, 1600));
      await tester.view.setDevicePixelRatio(1);
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: WdsComponentShowcase(examples: examples),
        ),
      ));

      await tester.enterText(find.byType(TextField), 'status');
      await tester.pumpAndSettle();
      expect(find.text('Success badge'), findsOneWidget);
      expect(find.text('Primary action'), findsNothing);
      await tester.view.resetPhysicalSize();
    });

    testWidgets('can switch preview theme', (tester) async {
      await tester.view.setPhysicalSize(const Size(1200, 1600));
      await tester.view.setDevicePixelRatio(1);
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: WdsComponentShowcase(examples: examples),
        ),
      ));

      expect(find.text('Light preview'), findsOneWidget);
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(find.text('Dark preview'), findsOneWidget);
      await tester.view.resetPhysicalSize();
    });

    testWidgets('shows an empty state when no examples match', (tester) async {
      await tester.view.setPhysicalSize(const Size(1200, 1600));
      await tester.view.setDevicePixelRatio(1);
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: WdsComponentShowcase(examples: examples),
        ),
      ));

      await tester.enterText(find.byType(TextField), 'not-a-component');
      await tester.pumpAndSettle();
      expect(find.text('No components found'), findsOneWidget);
      await tester.view.resetPhysicalSize();
    });
  });
}

void _noop() {}
