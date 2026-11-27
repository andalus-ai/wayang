import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/design_system/wds.dart';

void _ignoreSelection(String key) {}

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

    testWidgets('WdsNavigationRail reports destination selection', (tester) async {
      String? selected;
      const items = [
        WdsNavigationItem(key: 'dashboard', label: 'Dashboard', icon: Icons.dashboard_outlined),
        WdsNavigationItem(key: 'agents', label: 'Agents', icon: Icons.smart_toy_outlined),
      ];
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true, brightness: Brightness.dark),
        home: Scaffold(
          body: Row(children: [
            WdsNavigationRail(
              items: items,
              selectedKey: 'dashboard',
              onSelected: (key) => selected = key,
            ),
            const Expanded(child: SizedBox()),
          ]),
        ),
      ));
      await tester.tap(find.text('Agents'));
      expect(selected, 'agents');
      expect(find.text('Wayang'), findsOneWidget);
    });

    testWidgets('WdsCompactNavigationBar renders supplied destinations', (tester) async {
      String? selected;
      const items = [
        WdsNavigationItem(key: 'dashboard', label: 'Home', icon: Icons.home_outlined),
        WdsNavigationItem(key: 'runs', label: 'Runs', icon: Icons.play_arrow),
      ];
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          bottomNavigationBar: WdsCompactNavigationBar(
            items: items,
            selectedKey: 'dashboard',
            onSelected: (key) => selected = key,
          ),
        ),
      ));
      expect(find.text('Home'), findsOneWidget);
      await tester.tap(find.text('Runs'));
      expect(selected, 'runs');
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
