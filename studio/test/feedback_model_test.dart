import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/feedback_model.dart';

void main() {
  feedbackItemTests();
  test('summary reads the counts, ignores junk and marks a floor', () {
    final s = FeedbackSummary.fromJson({'open': 3, 'byKind': {'bug': 2, 'idea': 1, 'x': -4, 'y': 'z'}, 'up': 5, 'down': 2, 'sampled': false});
    expect(s.line, '3 open · 👍 5 · 👎 2');
    expect(s.openOf('bug'), 2);
    expect(s.openOf('error'), 0);
    expect(s.openByKind.containsKey('x'), isFalse);
    expect(FeedbackSummary.fromJson({'open': 500, 'sampled': true}).line, '500+ open · 👍 0+ · 👎 0+');
    expect(FeedbackSummary.fromJson({}).open, 0);
  });
  test('request trims, cuts to the server limits and drops empty context', () {
    final r = feedbackRequest(
      kind: FeedbackKind.bug,
      message: '  broken  ${'x' * 5000}',
      context: {'screen': 'runs', 'empty': '  ', 'stack': 's' * 9000, 'long': 'v' * 900},
    );
    expect(r['kind'], 'bug');
    expect((r['message'] as String).length, feedbackMaxMessage);
    expect((r['message'] as String).startsWith('broken'), isTrue);
    final ctx = r['context'] as Map;
    expect(ctx.containsKey('empty'), isFalse);
    expect((ctx['stack'] as String).length, feedbackMaxStack);
    expect((ctx['long'] as String).length, feedbackMaxValue);
    expect(ctx['screen'], 'runs');
  });

  test('a rating carries up/down and may have no message or context', () {
    final r = feedbackRequest(kind: FeedbackKind.rating, rating: 'down');
    expect(r, {'kind': 'rating', 'rating': 'down', 'message': ''});
  });

  test('at most twelve context entries are sent', () {
    final r = feedbackRequest(kind: FeedbackKind.comment, message: 'x', context: {for (var i = 0; i < 20; i++) 'k$i': 'v'});
    expect((r['context'] as Map).length, feedbackMaxContext);
  });

  test('problems', () {
    expect(feedbackProblem(FeedbackKind.comment, message: '  '), isNotNull);
    expect(feedbackProblem(FeedbackKind.comment, message: 'hi'), isNull);
    expect(feedbackProblem(FeedbackKind.rating), isNotNull);
    expect(feedbackProblem(FeedbackKind.rating, rating: 'up'), isNull);
    expect(feedbackProblem(FeedbackKind.rating, rating: 'meh'), isNotNull);
  });

  test('a captured error is cut to the limits', () {
    final e = CapturedError.of('e' * 900, StackTrace.fromString('t' * 9000), at: DateTime(2026));
    expect(e.message.length, feedbackMaxValue);
    expect(e.stack.length, feedbackMaxStack);
  });

  test('the preview shows everything that would be sent', () {
    final p = feedbackPreview(feedbackRequest(kind: FeedbackKind.error, message: 'boom', context: {'screen': 'runs', 'runId': 'r1'}));
    expect(p, contains('Kind: error'));
    expect(p, contains('boom'));
    expect(p, contains('screen: runs'));
    expect(p, contains('runId: r1'));
  });
}

void feedbackItemTests() {
  test('an entry is read from the API shape; an unknown kind is shown as it is', () {
    final f = FeedbackItem.fromJson({
      'id': 'a',
      'time': '2026-11-09T10:00:00Z',
      'user': 'vic',
      'kind': 'error',
      'message': 'boom',
      'context': {'runId': 'r1', 'n': 3},
    });
    expect(f.kindLabel, 'Error report');
    expect(f.rating, isNull);
    expect(f.context, {'runId': 'r1', 'n': '3'});
    expect(f.time, DateTime.utc(2026, 11, 9, 10));
    expect(FeedbackItem.fromJson({'id': 'b', 'kind': 'praise', 'rating': 'up'}).kindLabel, 'praise');
    expect(f.isDone, isFalse);
    expect(FeedbackItem.fromJson({'id': 'c', 'kind': 'bug', 'status': 'done'}).isDone, isTrue);
    expect(FeedbackItem.fromJson({'id': 'd', 'kind': 'bug', 'status': 'weird'}).status, 'open');
  });
}
