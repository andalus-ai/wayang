import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/feedback_model.dart';
import 'package:wayang_studio/data/wayang_repository.dart';
import 'package:wayang_studio/features/feedback/feedback_dialog.dart';
import 'package:wayang_studio/features/feedback/feedback_screen.dart';
import 'package:wayang_studio/providers/app_providers.dart';

class _FakeRepo extends WayangRepository {
  _FakeRepo(this.items) : super(ApiClient(const ApiConfig(baseUrl: 'http://localhost:0')));

  final List<FeedbackItem> items;
  final sent = <Map<String, dynamic>>[];

  @override
  Future<List<FeedbackItem>> listFeedback({String? kind, int limit = 100, String? before}) async =>
      [for (final i in items) if (kind == null || i.kind == kind) i];

  @override
  Future<FeedbackSummary> feedbackSummary() async =>
      const FeedbackSummary(open: 0, openByKind: {}, up: 0, down: 0, sampled: false);

  @override
  Future<Map<String, dynamic>> sendFeedback(Map<String, dynamic> request) async {
    sent.add(request);
    return {};
  }
}

FeedbackItem _item(String id, String msg, {String status = 'open'}) =>
    FeedbackItem(id: id, time: null, user: 'ann', kind: 'bug', rating: null, message: msg, status: status, context: const {});

Future<void> _pump(WidgetTester tester, _FakeRepo repo, Widget body) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [repositoryProvider.overrideWithValue(repo)],
    child: MaterialApp(home: Scaffold(body: body)),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('search and Hide done narrow the list', (tester) async {
    final repo = _FakeRepo([_item('1', 'Save button broken'), _item('2', 'Crash on run'), _item('3', 'Old crash', status: 'done')]);
    await _pump(tester, repo, const FeedbackScreen());
    expect(find.text('Save button broken'), findsOneWidget);
    expect(find.text('Old crash'), findsOneWidget);

    await tester.enterText(find.byType(TextField), 'crash');
    await tester.pumpAndSettle();
    expect(find.text('Save button broken'), findsNothing);
    expect(find.text('Crash on run'), findsOneWidget);

    await tester.tap(find.text('Hide done'));
    await tester.pumpAndSettle();
    expect(find.text('Old crash'), findsNothing);

    await tester.enterText(find.byType(TextField), 'zzz');
    await tester.pumpAndSettle();
    expect(find.text('Nothing matches'), findsOneWidget);
  });

  testWidgets('Cancel asks before throwing away typed text, and not when nothing was typed', (tester) async {
    final repo = _FakeRepo([]);
    await _pump(
      tester,
      repo,
      Builder(builder: (context) => TextButton(onPressed: () => showFeedbackDialog(context), child: const Text('open'))),
    );

    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(find.text('Send feedback'), findsNothing, reason: 'nothing typed: closes at once');

    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'something I wrote');
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(find.text('Discard this feedback?'), findsOneWidget);

    await tester.tap(find.text('Discard'));
    await tester.pumpAndSettle();
    expect(find.text('Send feedback'), findsNothing);
    expect(repo.sent, isEmpty);
  });
}
