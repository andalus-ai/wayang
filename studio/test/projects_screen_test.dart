import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/wayang_repository.dart';
import 'package:wayang_studio/features/projects/projects_screen.dart';
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

    await tester.enterText(find.byType(TextField), ' billing ');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.text('Another project already has this name.'), findsOneWidget);
    expect(repo.created, isEmpty);

    await tester.enterText(find.byType(TextField), 'Support');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(repo.created, ['Support']);
    expect(find.text('Support'), findsOneWidget, reason: 'the list reloads and the dialog is gone');
  });
}
