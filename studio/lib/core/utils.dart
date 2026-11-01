import 'dart:convert';
import 'dart:math';

/// Lightweight, dependency-free id generator. Good enough for editor-local
/// entity ids (nodes, edges, drafts) — swap for a real UUID source if the
/// backend requires strict UUID formatting.
class IdGen {
  IdGen._();
  static final Random _rng = Random();

  static String next(String prefix) {
    final ts = DateTime.now().microsecondsSinceEpoch.toRadixString(36);
    final rand = _rng.nextInt(0xFFFFFF).toRadixString(36).padLeft(4, '0');
    return '$prefix-$ts$rand';
  }
}

/// Formats durations the way an ops dashboard would: "42ms", "1.2s", "3m 4s".
String formatDurationMillis(num millis) {
  if (millis < 1000) return '${millis.round()}ms';
  final seconds = millis / 1000;
  if (seconds < 60) return '${seconds.toStringAsFixed(seconds < 10 ? 2 : 1)}s';
  final minutes = seconds ~/ 60;
  final remSeconds = (seconds % 60).round();
  return '${minutes}m ${remSeconds}s';
}

/// Formats a [DateTime] as a short relative-ish timestamp, e.g. "14:32:08".
String formatClock(DateTime dt) {
  final local = dt.toLocal();
  String two(int v) => v.toString().padLeft(2, '0');
  return '${two(local.hour)}:${two(local.minute)}:${two(local.second)}';
}

/// Formats a [DateTime] as a compact date + time, e.g. "Sep 9, 14:32".
String formatDateShort(DateTime dt) {
  const months = [
    'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'
  ];
  final local = dt.toLocal();
  String two(int v) => v.toString().padLeft(2, '0');
  return '${months[local.month - 1]} ${local.day}, ${two(local.hour)}:${two(local.minute)}';
}

/// Relative "time ago" label for activity feeds.
String timeAgo(DateTime dt) {
  final diff = DateTime.now().difference(dt);
  if (diff.inSeconds < 60) return '${diff.inSeconds}s ago';
  if (diff.inMinutes < 60) return '${diff.inMinutes}m ago';
  if (diff.inHours < 24) return '${diff.inHours}h ago';
  return '${diff.inDays}d ago';
}

/// Converts an UPPER_SNAKE_CASE backend enum name to "Title Case" for display.
String titleCaseFromConstant(String constant) {
  return constant
      .split('_')
      .map((w) => w.isEmpty ? w : '${w[0]}${w.substring(1).toLowerCase()}')
      .join(' ');
}

/// Clamp helper for canvas zoom levels etc.
double clampD(double v, double min, double max) => v < min ? min : (v > max ? max : v);

/// Parses a JSON object typed by a person; null when it is not one.
Map<String, dynamic>? tryParseJsonObject(String text) {
  final t = text.trim();
  if (t.isEmpty) return <String, dynamic>{};
  try {
    final v = jsonDecode(t);
    return v is Map ? Map<String, dynamic>.from(v) : null;
  } catch (_) {
    return null;
  }
}
