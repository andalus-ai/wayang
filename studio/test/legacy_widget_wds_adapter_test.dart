import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/core/widgets.dart';
import 'package:wayang_studio/design_system/wds.dart';

void main() {
  group('Legacy widget WDS adapters', () {
    testWidgets('EmptyState delegates to WdsEmptyState and preserves its API', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: EmptyState(
            icon: Icons.inbox_outlined,
            title: 'No workflows',
            message: 'Create your first workflow.',
            action: FilledButton(onPressed: () {}, child: const Text('Create')),
          ),
        ),
      ));

      expect(find.byType(WdsEmptyState), findsOneWidget);
      expect(find.text('No workflows'), findsOneWidget);
      expect(find.text('Create your first workflow.'), findsOneWidget);
      expect(find.text('Create'), findsOneWidget);
    });

    testWidgets('ScreenHeader delegates heading and actions while retaining icon slot', (tester) async {
      await tester.view.setPhysicalSize(const Size(900, 700));
      await tester.view.setDevicePixelRatio(1);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: ScreenHeader(
            title: 'Workflow catalog',
            subtitle: 'Manage reusable workflows',
            icon: Icons.account_tree_outlined,
            actions: [TextButton(onPressed: () {}, child: const Text('Create'))],
          ),
        ),
      ));

      expect(find.byType(WdsSectionHeader), findsOneWidget);
      expect(find.byIcon(Icons.account_tree_outlined), findsOneWidget);
      expect(find.text('Workflow catalog'), findsOneWidget);
      expect(find.text('Manage reusable workflows'), findsOneWidget);
      expect(find.text('Create'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('KeyValueRow delegates to WdsPropertyRow and preserves monospace', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: const Scaffold(
          body: KeyValueRow(label: 'Run ID', value: 'run-123', mono: true),
        ),
      ));

      expect(find.byType(WdsPropertyRow), findsOneWidget);
      expect(find.text('Run ID'), findsOneWidget);
      final value = tester.widget<Text>(find.text('run-123'));
      expect(value.style?.fontFamily, 'monospace');
    });

    testWidgets('legacy surface and search facades continue to render', (tester) async {
      final controller = TextEditingController();
      addTearDown(controller.dispose);
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: Column(
            children: [
              SurfacePanel(child: const Text('Panel content')),
              AppSearchField(hint: 'Search workflows', controller: controller),
            ],
          ),
        ),
      ));

      expect(find.byType(WdsSurface), findsOneWidget);
      expect(find.byType(TextField), findsOneWidget);
      expect(find.text('Panel content'), findsOneWidget);
    });
  });
}
