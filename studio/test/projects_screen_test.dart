import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/wayang_repository.dart';
import 'package:wayang_studio/features/projects/projects_screen.dart';
import 'package:wayang_studio/design_system/wds.dart';
import 'package:wayang_studio/models/wayang.dart';
import 'package:wayang_studio/providers/app_providers.dart';

/// A repository that never touches the network: it keeps projects in memory and records what the screen asked for.
class _FakeRepo extends WayangRepository {
  _FakeRepo(this.projects) : super(ApiClient(const ApiConfig(baseUrl: 'http://localhost:0')));

  final List<Project> projects;
  final created = <String>[];

  @override
  Future<List<Project>> listProjects() async => List.of(projects);

  @override
  Future<Project> createProject(String name, {String? description}) async {
    created.add(name);
    final p = Project(id: 'p${created.length}', name: name);
    projects.add(p);
    return p;
  }
}

Future<void> _pump(WidgetTester tester, _FakeRepo repo) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [repositoryProvider.overrideWithValue(repo)],
    child: const MaterialApp(home: Scaffold(body: ProjectsScreen())),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the icon-only buttons of a project row say what they do', (tester) async {
    await _pump(tester, _FakeRepo([const Project(id: 'a', name: 'Billing')]));
    expect(find.byTooltip('Rename project'), findsOneWidget);
    expect(find.byTooltip('Delete project'), findsOneWidget);
  });

  testWidgets('no projects shows the empty state', (tester) async {
    await _pump(tester, _FakeRepo([]));
    expect(find.text('No projects yet'), findsOneWidget);
  });

  testWidgets('the name dialog refuses a blank name and a duplicate, then creates one', (tester) async {
    final repo = _FakeRepo([const Project(id: 'a', name: 'Billing')]);
    await _pump(tester, repo);

    await tester.tap(find.text('New project').first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.text('Enter a name.'), findsOneWidget);

    await tester.enterText(find.descendant(of: find.byType(AlertDialog), matching: find.byType(TextField)), ' billing ');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.text('Another project already has this name.'), findsOneWidget);
    expect(repo.created, isEmpty);

    await tester.enterText(find.descendant(of: find.byType(AlertDialog), matching: find.byType(TextField)), 'Support');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(repo.created, ['Support']);
    expect(find.text('Support'), findsOneWidget, reason: 'the list reloads and the dialog is gone');
  });

  testWidgets('projects catalog uses WDS table and keeps project row actions', (tester) async {
    await _pump(tester, _FakeRepo([const Project(id: 'a', name: 'Billing', description: 'Invoices')]));
    expect(find.byType(WdsDataTable<Project>), findsOneWidget);
    expect(find.text('Billing'), findsOneWidget);
    expect(find.byTooltip('Rename project'), findsOneWidget);
    expect(find.byTooltip('Delete project'), findsOneWidget);
  });

  testWidgets('project search filters the visible catalog without changing project data', (tester) async {
    await _pump(tester, _FakeRepo([
      const Project(id: 'a', name: 'Billing', description: 'Invoices'),
      const Project(id: 'b', name: 'Support', description: 'Customer care'),
    ]));
    await tester.enterText(find.descendant(of: find.byType(AlertDialog), matching: find.byType(TextField)).first, 'support customer');
    await tester.pumpAndSettle();
    expect(find.text('Support'), findsOneWidget);
    expect(find.text('Billing'), findsNothing);
    expect(find.byType(WdsDataTable<Project>), findsOneWidget);
  });

  test('project search matches all terms across name, id and description', () {
    final projects = [
      const Project(id: 'billing-prod', name: 'Billing', description: 'Invoices and payments'),
      const Project(id: 'support', name: 'Customer Care', description: 'Support queue'),
    ];
    expect(filterProjects(projects, 'billing invoices'), [projects.first]);
    expect(filterProjects(projects, 'support queue'), [projects.last]);
    expect(filterProjects(projects, 'no match'), isEmpty);
    expect(filterProjects(projects, '  '), projects);
  });

}
