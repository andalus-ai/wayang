import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/bpmn/shortcuts_help.dart';

void main() {
  test('every group has entries with non-empty keys and descriptions', () {
    expect(bpmnShortcutHelp, isNotEmpty);
    for (final g in bpmnShortcutHelp.entries) {
      expect(g.value, isNotEmpty, reason: g.key);
      for (final s in g.value) {
        expect(s.keys.trim(), isNotEmpty);
        expect(s.does.trim(), isNotEmpty);
      }
    }
  });

  test('the sheet mentions each keyboard binding the canvas registers', () {
    final canvas = File('lib/features/bpmn/bpmn_canvas.dart').readAsStringSync();
    final sheet = bpmnShortcutHelp.values.expand((g) => g).map((s) => '${s.keys} ${s.does}').join('\n').toLowerCase();
    final bound = <String, String>{
      'LogicalKeyboardKey.delete': 'delete',
      'LogicalKeyboardKey.escape': 'esc',
      'LogicalKeyboardKey.keyA': 'ctrl/cmd + a',
      'LogicalKeyboardKey.keyC': 'copy',
      'LogicalKeyboardKey.keyF,': 'find',
      'LogicalKeyboardKey.keyZ': 'undo',
      'LogicalKeyboardKey.keyY': 'redo',
      'LogicalKeyboardKey.arrowLeft': 'arrow',
      'LogicalKeyboardKey.f1': 'f1',
    };
    for (final e in bound.entries) {
      if (canvas.contains(e.key)) expect(sheet, contains(e.value), reason: '${e.key} is bound but not on the sheet');
    }
  });
}
