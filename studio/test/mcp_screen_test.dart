import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/wayang_repository.dart';
import 'package:wayang_studio/features/mcp/mcp_screen.dart';
import 'package:wayang_studio/models/wayang.dart';
import 'package:wayang_studio/providers/app_providers.dart';
import 'package:wayang_studio/design_system/wds.dart';

McpServerInfo _server(String id) => McpServerInfo(
      id: id,
      title: 'Pets',
      baseUrl: 'https://pets.example',
      endpoint: '/mcp/$id',
      tools: const [McpToolInfo(name: 'listPets', description: 'List pets', readOnly: true, destructive: false, args: [])],
      warnings: const [],
      security: const [],
    );

class _FakeRepo extends WayangRepository {
  _FakeRepo() : super(ApiClient(const ApiConfig(baseUrl: 'http://localhost:0')));

  final previews = <Map<String, dynamic>>[];
  final created = <String>[];

  @override
  Future<List<McpServerInfo>> listMcpServers() async => [_server('pets')];

  @override
  @override
  Future<McpServerInfo> getMcpServer(String id) async => _server(id);

  Future<McpServerInfo> previewMcpServer(Map<String, dynamic> request) async {
    previews.add(request);
    return _server('preview');
  }

  @override
  Future<McpServerInfo> createMcpServer(String id, Map<String, dynamic> request) async {
    created.add(id);
    return _server(id);
  }
}

Finder _field(String label) =>
    find.byWidgetPredicate((w) => w is TextField && (w.decoration?.labelText ?? '').startsWith(label));

void main() {
  testWidgets('the import dialog checks inputs, drops a stale preview and validates the id', (tester) async {
    tester.view.physicalSize = const Size(1600, 1400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repo = _FakeRepo();
    await tester.pumpWidget(ProviderScope(
      overrides: [repositoryProvider.overrideWithValue(repo)],
      child: const MaterialApp(home: Scaffold(body: McpScreen())),
    ));
    await tester.pumpAndSettle();
    expect(find.byType(WdsPageHeader), findsOneWidget);
    expect(find.byType(WdsSurface), findsOneWidget);
    await tester.tap(find.text('pets'));
    await tester.pumpAndSettle();
    expect(find.byType(WdsBadge), findsOneWidget);

    await tester.tap(find.text('Import OpenAPI'));
    await tester.pumpAndSettle();

    // A path pattern that is not a regular expression stops the preview before any request.
    await tester.enterText(_field('Spec URL'), 'https://pets.example/openapi.json');
    await tester.enterText(_field('Only paths'), '([unclosed');
    await tester.tap(find.text('Preview'));
    await tester.pumpAndSettle();
    expect(find.textContaining('not a valid regular expression'), findsOneWidget);
    expect(repo.previews, isEmpty);

    await tester.enterText(_field('Only paths'), '^/pets');
    await tester.tap(find.text('Preview'));
    await tester.pumpAndSettle();
    expect(repo.previews, hasLength(1));
    expect(_field('Server id'), findsOneWidget, reason: 'a preview lets you name the server');

    // Changing an input makes the preview stale: the id field (and Create) go away until it is previewed again.
    await tester.enterText(_field('Base URL'), 'https://other.example');
    await tester.pumpAndSettle();
    expect(_field('Server id'), findsNothing);

    await tester.tap(find.text('Preview'));
    await tester.pumpAndSettle();
    expect(repo.previews, hasLength(2));

    await tester.enterText(_field('Server id'), 'pets');
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(find.textContaining('exists already'), findsOneWidget);

    await tester.enterText(_field('Server id'), 'bad id');
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(find.textContaining('letters, digits'), findsWidgets);
    expect(repo.created, isEmpty);

    await tester.enterText(_field('Server id'), 'orders-api');
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(repo.created, ['orders-api']);
  });
}
