import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/runs/runs_screen.dart';

void main() {
  test('short text is shown whole', () {
    expect(previewText('{"a": 1}'), '{"a": 1}');
  });

  test('long text is cut at a line end near the limit', () {
    final text = List.generate(500, (i) => '  "k$i": $i,').join('\n');
    final p = previewText(text, max: 1000);
    expect(p.length, lessThanOrEqualTo(1000));
    expect(text.startsWith(p), isTrue);
    expect(p.endsWith(','), isTrue, reason: 'cut between lines, not inside one');
  });

  test('a single huge line is cut at the limit', () {
    expect(previewText('x' * 5000, max: 100).length, 100);
  });
}
