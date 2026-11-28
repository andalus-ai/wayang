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

    test('command filtering matches multiple tokens and keywords', () {
      const commands = [
        WdsCommandItem(
          id: 'new-agent',
          label: 'Create agent',
          icon: Icons.smart_toy_outlined,
          keywords: ['assistant', 'builder'],
          group: 'Create',
        ),
        WdsCommandItem(
          id: 'runs',
          label: 'Execution history',
          icon: Icons.history,
          group: 'Monitor',
        ),
      ];
      expect(filterWdsCommands(commands, 'create builder').map((e) => e.id), ['new-agent']);
      expect(filterWdsCommands(commands, 'missing'), isEmpty);
    });

    testWidgets('WdsCommandPalette reports selected command', (tester) async {
      WdsCommandItem? selected;
      const commands = [
        WdsCommandItem(
          id: 'new-agent',
          label: 'Create agent',
          icon: Icons.smart_toy_outlined,
          description: 'Start a new agent',
          group: 'Create',
        ),
      ];
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: WdsCommandPalette(items: commands, onSelected: (item) => selected = item),
        ),
      ));
      await tester.tap(find.text('Create agent'));
      expect(selected?.id, 'new-agent');
    });

    testWidgets('WdsPageHeader composes breadcrumbs, description and actions', (tester) async {
      var navigated = false;
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: SingleChildScrollView(
            child: WdsPageHeader(
              breadcrumbs: [
                WdsBreadcrumbItem(label: 'Workspace', onTap: () => navigated = true),
                const WdsBreadcrumbItem(label: 'Agents'),
              ],
              eyebrow: 'Agent builder',
              title: 'Agents',
              description: 'Design and manage reusable agents.',
              actions: [FilledButton(onPressed: () {}, child: const Text('Create agent'))],
            ),
          ),
        ),
      ));
      expect(find.text('Agents'), findsWidgets);
      expect(find.text('Design and manage reusable agents.'), findsOneWidget);
      await tester.tap(find.text('Workspace'));
      expect(navigated, isTrue);
    });

    testWidgets('WdsFilterToolbar wraps controls at compact widths', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 320,
              child: WdsFilterToolbar(
                search: const TextField(decoration: InputDecoration(hintText: 'Search')),
                filters: [FilterChip(label: const Text('Active'), selected: false, onSelected: (_) {})],
                actions: [FilledButton(onPressed: () {}, child: const Text('Create'))],
              ),
            ),
          ),
        ),
      ));
      expect(find.text('Search'), findsOneWidget);
      expect(find.text('Active'), findsOneWidget);
      expect(find.text('Create'), findsOneWidget);
      expect(tester.takeException(), isNull);
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

    testWidgets('WdsDataTable reports controlled sorting and row selection', (tester) async {
      var sortAscending = false;
      Set<Object>? selection;
      const rows = [
        ('agent-a', 'Alpha'),
        ('agent-b', 'Beta'),
      ];
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: WdsDataTable<(String, String)>(
            columns: [
              WdsDataColumn<(String, String)>(
                label: 'Name',
                sortable: true,
                cellBuilder: (context, row) => Text(row.$2),
              ),
            ],
            rows: rows,
            rowKey: (row) => row.$1,
            onSelectionChanged: (value) => selection = value,
            onSort: (_, ascending) => sortAscending = ascending,
          ),
        ),
      ));
      await tester.tap(find.text('Name'));
      expect(sortAscending, isTrue);
      await tester.tap(find.byType(Checkbox).at(1));
      expect(selection, contains('agent-a'));
    });

    testWidgets('WdsDataTable renders empty and loading states', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: WdsDataTable<String>(
            columns: [
              WdsDataColumn<String>(
                label: 'Name',
                cellBuilder: (context, row) => Text(row),
              ),
            ],
            rows: const [],
            emptyTitle: 'No agents yet',
          ),
        ),
      ));
      expect(find.text('No agents yet'), findsOneWidget);
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: WdsDataTable<String>(
            columns: [
              WdsDataColumn<String>(
                label: 'Name',
                cellBuilder: (context, row) => Text(row),
              ),
            ],
            rows: const [],
            isLoading: true,
          ),
        ),
      ));
      expect(find.byType(LinearProgressIndicator), findsOneWidget);
    });

    testWidgets('WDS inspector primitives compose fields and validation messages', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: const Scaffold(
          body: Padding(
            padding: EdgeInsets.all(16),
            child: WdsInspectorSection(
              title: 'Agent settings',
              description: 'Identity and runtime details',
              child: WdsFieldGroup(
                children: [
                  WdsPropertyRow(label: 'Name', value: Text('Support agent')),
                  WdsFieldMessage(message: 'A name is required'),
                ],
              ),
            ),
          ),
        ),
      ));
      expect(find.text('Agent settings'), findsOneWidget);
      expect(find.text('Support agent'), findsOneWidget);
      expect(find.text('A name is required'), findsOneWidget);
    });

    testWidgets('WdsEditorToolbar composes title and actions responsively', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: SizedBox(
            width: 360,
            child: WdsEditorToolbar(
              title: 'Customer onboarding',
              subtitle: 'Draft workflow',
              actions: [
                IconButton(
                  tooltip: 'Undo',
                  onPressed: () {},
                  icon: const Icon(Icons.undo),
                ),
              ],
            ),
          ),
        ),
      ));
      expect(find.text('Customer onboarding'), findsOneWidget);
      expect(find.text('Draft workflow'), findsOneWidget);
      expect(find.byTooltip('Undo'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('WdsEditorPanel renders header, content and footer', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: SizedBox(
            height: 500,
            child: WdsEditorPanel(
              title: 'Properties',
              description: 'Selected node',
              child: const Text('Inspector content'),
              footer: const Text('Panel footer'),
            ),
          ),
        ),
      ));
      expect(find.text('Properties'), findsOneWidget);
      expect(find.text('Inspector content'), findsOneWidget);
      expect(find.text('Panel footer'), findsOneWidget);
    });

    testWidgets('WdsNodeCard renders ports, status and callbacks', (tester) async {
      WdsNodePortData? selectedPort;
      var nodeSelected = false;
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: Center(
            child: WdsNodeCard(
              title: 'HTTP Request',
              subtitle: 'Integration',
              selected: true,
              status: const WdsBadge(label: 'Ready', tone: WdsBadgeTone.success),
              inputs: const [
                WdsNodePortData(id: 'url', label: 'URL', isRequired: true),
              ],
              outputs: const [
                WdsNodePortData(
                  id: 'response',
                  label: 'Response',
                  kind: WdsNodePortKind.event,
                  isConnected: true,
                ),
              ],
              onTap: () => nodeSelected = true,
              onPortTap: (port) => selectedPort = port,
            ),
          ),
        ),
      ));
      expect(find.text('HTTP Request'), findsOneWidget);
      expect(find.text('Ready'), findsOneWidget);
      expect(find.text('URL *'), findsOneWidget);
      expect(find.text('Response'), findsOneWidget);
      await tester.tap(find.text('HTTP Request'));
      expect(nodeSelected, isTrue);
      await tester.tap(find.text('URL *'));
      expect(selectedPort?.id, 'url');
    });

    testWidgets('execution status indicator covers semantic states', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: const Scaffold(
          body: Wrap(children: [
            WdsExecutionStatusIndicator(status: WdsExecutionStatus.pending),
            WdsExecutionStatusIndicator(status: WdsExecutionStatus.running),
            WdsExecutionStatusIndicator(status: WdsExecutionStatus.succeeded),
            WdsExecutionStatusIndicator(status: WdsExecutionStatus.failed),
            WdsExecutionStatusIndicator(status: WdsExecutionStatus.retrying),
          ]),
        ),
      ));
      expect(find.text('Pending'), findsOneWidget);
      expect(find.text('Running'), findsOneWidget);
      expect(find.text('Succeeded'), findsOneWidget);
      expect(find.text('Failed'), findsOneWidget);
      expect(find.text('Retrying'), findsOneWidget);
    });

    testWidgets('execution timeline renders metadata and reports selection', (tester) async {
      WdsExecutionStep? selected;
      const steps = [
        WdsExecutionStep(
          id: 'validate',
          title: 'Validate request',
          status: WdsExecutionStatus.succeeded,
          duration: Duration(milliseconds: 820),
          attempt: 1,
        ),
        WdsExecutionStep(
          id: 'invoke',
          title: 'Call integration',
          status: WdsExecutionStatus.running,
          message: 'Waiting for response',
        ),
      ];
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: WdsExecutionTimeline(
            steps: steps,
            activeStepId: 'invoke',
            onStepSelected: (step) => selected = step,
          ),
        ),
      ));
      expect(find.text('Validate request'), findsOneWidget);
      expect(find.text('820ms'), findsOneWidget);
      expect(find.text('Attempt 1'), findsOneWidget);
      expect(find.text('Waiting for response'), findsOneWidget);
      await tester.tap(find.text('Validate request'));
      expect(selected?.id, 'validate');
    });

    testWidgets('execution timeline presents an empty state', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: const Scaffold(body: WdsExecutionTimeline(steps: [])),
      ));
      expect(find.text('No execution steps'), findsOneWidget);
    });

    testWidgets('execution event list renders severity and reports selection', (tester) async {
      WdsExecutionEvent? selected;
      final event = WdsExecutionEvent(
        id: 'event-1',
        timestamp: DateTime(2026, 11, 28, 10, 30, 5),
        title: 'Connector failed',
        message: 'The upstream service returned an error.',
        level: WdsExecutionEventLevel.error,
        source: 'HTTP Request',
        details: 'HTTP 502',
      );
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: WdsExecutionEventList(
            events: [event],
            onEventSelected: (value) => selected = value,
          ),
        ),
      ));
      expect(find.text('Connector failed'), findsOneWidget);
      expect(find.text('ERROR'), findsOneWidget);
      expect(find.text('HTTP 502'), findsOneWidget);
      expect(find.text('10:30:05'), findsOneWidget);
      await tester.tap(find.text('Connector failed'));
      expect(selected?.id, 'event-1');
    });

    test('execution display formatters are stable', () {
      expect(formatWdsDuration(const Duration(milliseconds: 820)), '820ms');
      expect(formatWdsDuration(const Duration(seconds: 2)), '2s');
      expect(formatWdsDuration(const Duration(minutes: 1, seconds: 5)), '1m 5s');
      expect(formatWdsTimestamp(DateTime(2026, 11, 28, 9, 4, 7)), '09:04:07');
    });
  });
}
