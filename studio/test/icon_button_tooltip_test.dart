import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Every IconButton is icon-only, so its tooltip is also its screen-reader label. This keeps new ones from shipping without.
void main() {
  test('every IconButton in lib/ has a tooltip', () {
    final missing = <String>[];
    for (final f in Directory('lib').listSync(recursive: true).whereType<File>().where((f) => f.path.endsWith('.dart'))) {
      final s = f.readAsStringSync();
      for (final m in RegExp(r'\bIconButton(?:\.\w+)?\(').allMatches(s)) {
        var i = m.end;
        var depth = 1;
        while (depth > 0 && i < s.length) {
          final c = s[i++];
          if (c == '(') depth++;
          if (c == ')') depth--;
        }
        if (!s.substring(m.start, i).contains('tooltip')) {
          missing.add('${f.path}:${s.substring(0, m.start).split('\n').length}');
        }
      }
    }
    expect(missing, isEmpty, reason: 'IconButton without tooltip: ${missing.join(', ')}');
  });
}
