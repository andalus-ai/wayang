import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/bpmn_mapper.dart';
import 'package:wayang_studio/data/dmn_mapper.dart';

// Not run in the environment that produced it (no Dart SDK there). Same fixtures the Java ContractFixturesTest
// validates, publishes and executes: if the mappers reproduce them, what the Studio saves is what the server runs.

Map<String, dynamic> fixture(String name) =>
    jsonDecode(File('../docs/contract/$name.json').readAsStringSync()) as Map<String, dynamic>;

/// Deep equality where 1 and 1.0 are the same number (JSON has one number type).
bool same(Object? a, Object? b) {
  if (a is num && b is num) return a.toDouble() == b.toDouble();
  if (a is Map && b is Map) {
    if (a.length != b.length) return false;
    for (final k in a.keys) {
      if (!b.containsKey(k) || !same(a[k], b[k])) return false;
    }
    return true;
  }
  if (a is List && b is List) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (!same(a[i], b[i])) return false;
    }
    return true;
  }
  return a == b;
}

void main() {
  test('process fixture survives document -> diagram -> document', () {
    final doc = fixture('process-loan');
    final back = bpmnToDocument(bpmnFromDocument(doc));
    expect(same(back, doc), isTrue, reason: 'got ${jsonEncode(back)}');
  });

  for (final name in ['decision-risk', 'decision-tier']) {
    test('$name survives document -> table -> document', () {
      final doc = fixture(name);
      final back = dmnToDocument(dmnFromDocument(doc));
      // The Studio adds its own layout metadata (studio.*) to a document that had none; the server ignores it. Everything else must match.
      if (!doc.containsKey('studio')) back.remove('studio');
      expect(same(back, doc), isTrue, reason: 'got ${jsonEncode(back)}');
    });
  }

  test('an element type this Studio does not know is refused, not dropped', () {
    final doc = fixture('process-loan');
    (doc['elements'] as List).add({'id': 'x', 'type': 'FUTURE_THING', 'name': 'x', 'config': {}});
    expect(() => bpmnFromDocument(doc), throwsA(isA<DocumentFormatException>()));
  });

  test('studio.mcp survives a load and save, and is absent when never set', () {
    final doc = fixture('process-loan');
    final studio = Map<String, dynamic>.from((doc['studio'] as Map?) ?? {});
    studio['mcp'] = {'expose': true, 'waitSeconds': 5, 'inputSchema': {'type': 'object'}};
    doc['studio'] = studio;
    final saved = bpmnToDocument(bpmnFromDocument(doc));
    expect(same((saved['studio'] as Map)['mcp'], studio['mcp']), isTrue);
    final plain = bpmnToDocument(bpmnFromDocument(fixture('process-loan')));
    expect((plain['studio'] as Map).containsKey('mcp'), isFalse);
  });
}
