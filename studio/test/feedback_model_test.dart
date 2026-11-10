import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/feedback_model.dart';

void main() {
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
