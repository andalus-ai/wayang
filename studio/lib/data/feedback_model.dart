/// What a person can send: a rating of something, a comment, a bug, an idea, or an error report. The names are the API's.
enum FeedbackKind {
  comment('comment', 'Comment'),
  bug('bug', 'Bug'),
  idea('idea', 'Idea'),
  error('error', 'Error report'),
  rating('rating', 'Rating');

  const FeedbackKind(this.api, this.label);
  final String api;
  final String label;
}

/// The limits the server enforces (it refuses longer text rather than cutting it, so the form cuts first).
const feedbackMaxMessage = 4000;
const feedbackMaxStack = 8000;
const feedbackMaxValue = 500;
const feedbackMaxContext = 12;

/// An error caught by the app itself, kept until the person decides to report it. Nothing is sent automatically.
class CapturedError {
  const CapturedError({required this.message, required this.stack, required this.at});

  factory CapturedError.of(Object error, StackTrace? stack, {DateTime? at}) => CapturedError(
        message: _clip('$error', feedbackMaxValue),
        stack: _clip(stack?.toString() ?? '', feedbackMaxStack),
        at: at ?? DateTime.now(),
      );

  final String message;
  final String stack;
  final DateTime at;
}

String _clip(String s, int max) => s.length <= max ? s : s.substring(0, max);

/// The request body for `POST /api/v1/feedback`. Text is trimmed and cut to the server's limits; empty context values are dropped. The
/// server also masks anything that looks like a credential, but nothing secret should be put in here in the first place: [context] is for
/// short, named details (a run id, the screen, the platform), never request or response bodies.
Map<String, dynamic> feedbackRequest({
  required FeedbackKind kind,
  String? rating,
  String message = '',
  Map<String, String> context = const {},
}) {
  final ctx = <String, String>{};
  for (final e in context.entries) {
    final v = e.value.trim();
    if (v.isEmpty || ctx.length >= feedbackMaxContext) continue;
    ctx[e.key] = _clip(v, e.key == 'stack' ? feedbackMaxStack : feedbackMaxValue);
  }
  return {
    'kind': kind.api,
    if (rating != null) 'rating': rating,
    'message': _clip(message.trim(), feedbackMaxMessage),
    if (ctx.isNotEmpty) 'context': ctx,
  };
}

/// What stops the form from being sent, or null when it can be: a rating needs up/down, everything else needs a message.
String? feedbackProblem(FeedbackKind kind, {String? rating, String message = ''}) {
  if (kind == FeedbackKind.rating) return rating == 'up' || rating == 'down' ? null : 'Choose thumbs up or down.';
  return message.trim().isEmpty ? 'Write a few words first.' : null;
}

/// The plain-text preview of a request, so the person sees exactly what would leave their browser.
String feedbackPreview(Map<String, dynamic> request) {
  final b = StringBuffer('Kind: ${request['kind']}');
  if (request['rating'] != null) b.write('\nRating: ${request['rating']}');
  final msg = request['message'];
  if (msg is String && msg.isNotEmpty) b.write('\n\n$msg');
  final ctx = request['context'];
  if (ctx is Map) {
    for (final e in ctx.entries) {
      b.write('\n${e.key}: ${e.value}');
    }
  }
  return b.toString();
}

/// One stored feedback entry as `GET /api/v1/feedback` returns it (an admin's view).
class FeedbackItem {
  const FeedbackItem({this.status = 'open', required this.id, required this.time, required this.user, required this.kind, required this.rating, required this.message, required this.context});

  factory FeedbackItem.fromJson(Map<String, dynamic> j) => FeedbackItem(
        id: '${j['id']}',
        time: DateTime.tryParse('${j['time']}'),
        user: '${j['user'] ?? ''}',
        kind: '${j['kind']}',
        rating: j['rating'] is String ? j['rating'] as String : null,
        message: '${j['message'] ?? ''}',
        status: j['status'] == 'done' ? 'done' : 'open',
        context: {
          if (j['context'] is Map) for (final e in (j['context'] as Map).entries) '${e.key}': '${e.value}',
        },
      );

  final String id;
  final DateTime? time;
  final String user;
  final String kind;
  final String? rating;
  final String message;
  final Map<String, String> context;

  /// `open` (new) or `done` (an admin dealt with it).
  final String status;

  bool get isDone => status == 'done';

  /// The label of [kind] as the form shows it; an unknown kind (a newer server) is shown as it is.
  String get kindLabel => FeedbackKind.values.where((k) => k.api == kind).map((k) => k.label).firstOrNull ?? kind;
}

/// What `GET /api/v1/feedback/summary` says is waiting: [open] entries (and [openByKind]) and the thumbs. [sampled] means the server counted
/// only its newest entries, so the numbers are a floor.
class FeedbackSummary {
  const FeedbackSummary({required this.open, required this.openByKind, required this.up, required this.down, required this.sampled});

  factory FeedbackSummary.fromJson(Map<String, dynamic> j) {
    int n(Object? v) => v is num && v >= 0 ? v.toInt() : 0;
    final by = j['byKind'];
    return FeedbackSummary(
      open: n(j['open']),
      openByKind: {
        if (by is Map)
          for (final e in by.entries)
            if (e.key is String && n(e.value) > 0) e.key as String: n(e.value),
      },
      up: n(j['up']),
      down: n(j['down']),
      sampled: j['sampled'] == true,
    );
  }

  final int open;
  final Map<String, int> openByKind;
  final int up;
  final int down;
  final bool sampled;

  /// One line for the header, e.g. `3 open · 👍 5 · 👎 2` (with a trailing `+` on counts that may be a floor).
  String get line {
    final plus = sampled ? '+' : '';
    return '$open$plus open · 👍 $up$plus · 👎 $down$plus';
  }

  /// How many open entries of [kind] (0 when none).
  int openOf(String kind) => openByKind[kind] ?? 0;
}
