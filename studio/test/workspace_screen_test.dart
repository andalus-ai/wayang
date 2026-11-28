import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/design_system/wds.dart';
import 'package:wayang_studio/data/wayang_repository.dart';
import 'package:wayang_studio/data/workspace_model.dart';
import 'package:wayang_studio/features/workspace/workspace_screen.dart';
import 'package:wayang_studio/models/wayang.dart';
import 'package:wayang_studio/providers/app_providers.dart';

class _FakeRepo extends WayangRepository {
  _FakeRepo() : super(ApiClient(const ApiConfig(baseUrl: 'http://localhost:0')));

  final imports = <ImportOptions>[];
  var plans = 0;

  @override
  Future<List<Project>> listProjects() async => [const Project(id: 'p1', name: 'Billing')];

  @override
  Future<List<TemplateInfo>> listTemplates() async => const [];

  @override
  Future<ImportReport> importBundle(Map<String, dynamic> bundle, ImportOptions options) async {
    imports.add(options);
    return ImportReport.fromJson({'dryRun': options.dryRun, 'ok': true});
  }

  @override
  Future<ReleasePlan> planRelease(String projectId, ReleaseForm form) async {
    plans++;
    return ReleasePlan.fromJson({'target': form.target, 'artifactId': 'billing', 'version': form.version});
  }
}

Finder _field(String label) => find.byWidgetPredicate((w) => w is TextField && w.decoration?.labelText == label);

Future<_FakeRepo> _open(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1600, 4000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final repo = _FakeRepo();
  await tester.pumpWidget(ProviderScope(
    overrides: [repositoryProvider.overrideWithValue(repo)],
    child: const MaterialApp(home: Scaffold(body: WorkspaceScreen())),
  ));
  await tester.pumpAndSettle();
  expect(find.byType(WdsPageHeader), findsOneWidget);
  expect(find.byType(WdsSurface), findsWidgets);
  expect(find.byType(WdsSectionHeader), findsOneWidget);
  return repo;
}

void main() {
  testWidgets('a risky import asks before Apply; a plain one does not', (tester) async {
    final repo = await _open(tester);
    await tester.enterText(find.byWidgetPredicate((w) => w is TextField && w.decoration?.hintText == 'Paste a bundle here'),
        '{"format":"wayang.workspace","formatVersion":1}');
    await tester.pumpAndSettle();

    // Plain: check, then apply with no confirmation.
    await tester.tap(find.text('Check (dry run)'));
    await tester.pumpAndSettle();
    expect(find.byType(WdsBadge), findsOneWidget, reason: 'dry-run result uses semantic WDS status');
    await tester.tap(find.text('Apply'));
    await tester.pumpAndSettle();
    expect(find.text('Apply this import?'), findsNothing);
    expect(repo.imports.map((o) => o.dryRun), [true, false]);

    // Overwrite: a changed option needs a new check, and Apply then asks first.
    await tester.tap(find.text('If it exists: fail'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('If it exists: overwrite'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Check (dry run)'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Apply'));
    await tester.pumpAndSettle();
    expect(find.text('Apply this import?'), findsOneWidget);
    expect(find.textContaining('overwritten'), findsOneWidget);
    expect(repo.imports, hasLength(3), reason: 'nothing applied yet');

    await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('Cancel')));
    await tester.pumpAndSettle();
    expect(repo.imports, hasLength(3));

    await tester.tap(find.text('Apply'));
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('Apply')));
    await tester.pumpAndSettle();
    expect(repo.imports.map((o) => o.dryRun), [true, false, true, false]);
    expect(repo.imports.last.onConflict, 'overwrite');
  });

  testWidgets('the release plan disappears when a release setting changes', (tester) async {
    final repo = await _open(tester);
    await tester.tap(find.text('Project'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Billing').last);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Plan release'));
    await tester.pumpAndSettle();
    expect(repo.plans, 1);
    expect(find.text('Download'), findsOneWidget);

    await tester.enterText(_field('version'), '2.0.0');
    await tester.pumpAndSettle();
    expect(find.text('Download'), findsNothing, reason: 'the old plan no longer describes the form');
  });
}
