import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/wayang_repository.dart';
import 'package:wayang_studio/features/channels/channels_screen.dart';
import 'package:wayang_studio/models/wayang.dart';
import 'package:wayang_studio/providers/app_providers.dart';

class _FakeRepo extends WayangRepository {
  _FakeRepo() : super(ApiClient(const ApiConfig(baseUrl: 'http://localhost:0')));

  final triggers = <TriggerInfo>[
    const TriggerInfo(id: 't1', kind: 'telegram', processId: 'orders', enabled: true, config: {}),
  ];
  final createdTriggers = <String>[];
  final createdConnections = <String>[];

  @override
  Future<List<DefinitionSummary>> listDefinitions(DefinitionKind kind, {String? projectId}) async =>
      [DefinitionSummary(kind: kind, id: 'orders', name: 'Orders', hasDraft: false, latestVersion: 1)];

  @override
  Future<List<TriggerInfo>> listTriggers() async => List.of(triggers);

  @override
  Future<CreatedTrigger> createTrigger({
    required String id,
    required String kind,
    required String processId,
    required Map<String, dynamic> config,
  }) async {
    createdTriggers.add(id);
    final t = TriggerInfo(id: id, kind: kind, processId: processId, enabled: false, config: config);
    triggers.add(t);
    return CreatedTrigger(trigger: t);
  }

  @override
  Future<List<OAuthConnectionInfo>> listOAuthConnections() async => const [
        OAuthConnectionInfo(id: 'old', provider: 'google', clientId: 'c', scopes: [], status: 'CONNECTED', secretRef: r'${secret:oauth.old}'),
      ];

  @override
  Future<OAuthConnectionInfo> createOAuthConnection({
    required String id,
    required String clientId,
    required String clientSecretName,
    required List<String> scopes,
    String provider = 'google',
  }) async {
    createdConnections.add(id);
    return OAuthConnectionInfo(id: id, provider: provider, clientId: clientId, scopes: scopes, status: 'PENDING', secretRef: '');
  }
}

Finder _field(String label) =>
    find.byWidgetPredicate((w) => w is TextField && (w.decoration?.labelText ?? '').startsWith(label));

Future<_FakeRepo> _open(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1600, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final repo = _FakeRepo();
  await tester.pumpWidget(ProviderScope(
    overrides: [repositoryProvider.overrideWithValue(repo)],
    child: const MaterialApp(home: Scaffold(body: ChannelsScreen())),
  ));
  await tester.pumpAndSettle();
  return repo;
}

void main() {
  testWidgets('the trigger dialog refuses a bad or taken id, then creates one', (tester) async {
    final repo = await _open(tester);

    await tester.tap(find.text('New trigger'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(find.text('Give the trigger an id.'), findsOneWidget);

    await tester.enterText(_field('Trigger id'), 'my trigger');
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(find.textContaining("letters, digits, '_' and '-'"), findsOneWidget);

    await tester.enterText(_field('Trigger id'), 't1');
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(find.text('A trigger with this id already exists.'), findsOneWidget);
    expect(repo.createdTriggers, isEmpty);

    await tester.enterText(_field('Trigger id'), 'telegram-me');
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(repo.createdTriggers, ['telegram-me']);
    expect(find.text('New trigger'), findsOneWidget, reason: 'only the header button is left: the dialog closed');
  });

  testWidgets('the Gmail connection dialog checks every field before sending', (tester) async {
    final repo = await _open(tester);

    await tester.tap(find.text('Connected accounts'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Connect Gmail'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(find.text('Enter the Google OAuth client id.'), findsOneWidget);

    await tester.enterText(_field('Google OAuth client id'), 'client-123');
    await tester.enterText(_field('Name of the secret'), 'bad name');
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Secret name:'), findsOneWidget);

    await tester.enterText(_field('Name of the secret'), 'GOOGLE_CLIENT_SECRET');
    await tester.tap(find.byType(CheckboxListTile).first); // the one preselected scope
    await tester.pumpAndSettle();
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(find.text('Pick at least one scope.'), findsOneWidget);

    await tester.tap(find.byType(CheckboxListTile).first);
    await tester.enterText(_field('Connection id'), 'old');
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(find.text('A connection with this id already exists.'), findsOneWidget);
    expect(repo.createdConnections, isEmpty);

    await tester.enterText(_field('Connection id'), 'work');
    await tester.tap(find.text('Create'));
    await tester.pumpAndSettle();
    expect(repo.createdConnections, ['work']);
  });
}
