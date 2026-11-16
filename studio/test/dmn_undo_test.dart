import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/wayang_repository.dart';
import 'package:wayang_studio/models/dmn.dart';
import 'package:wayang_studio/providers/dmn_provider.dart';

DmnDecisionTable _table() => const DmnDecisionTable(
      id: 't',
      name: 'Risk',
      inputs: [DmnColumn(id: 'in1', label: 'Age', expression: 'age', type: DmnType.number)],
      outputs: [DmnColumn(id: 'out1', label: 'Tier', type: DmnType.string)],
      rules: [
        DmnRule(id: 'a', inputEntries: ['< 18'], outputEntries: ['"minor"']),
        DmnRule(id: 'b', inputEntries: ['>= 18'], outputEntries: ['"adult"']),
      ],
    );

(DmnController, void Function(Duration)) _controller() {
  var t = DateTime(2026, 1, 1);
  final c = DmnController(WayangRepository(ApiClient(const ApiConfig(baseUrl: 'http://127.0.0.1:1'))), '', now: () => t);
  c.seed(_table());
  return (c, (d) => t = t.add(d));
}

void main() {
  test('each structural edit is one undo step; redo replays it; a new edit clears redo', () {
    final (c, _) = _controller();
    c.removeRule('a');
    expect(c.state.table!.rules.length, 1);
    expect(c.state.canUndo, isTrue);
    c.undo();
    expect(c.state.table!.rules.length, 2);
    expect(c.state.canRedo, isTrue);
    c.redo();
    expect(c.state.table!.rules.length, 1);
    c.undo();
    c.renameTable('Other');
    expect(c.state.canRedo, isFalse);
  });

  test('typing in one cell within a second is a single step; a pause or another cell starts a new one', () {
    final (c, tick) = _controller();
    c.setInputEntry('a', 0, '< 1');
    tick(const Duration(milliseconds: 200));
    c.setInputEntry('a', 0, '< 19');
    tick(const Duration(milliseconds: 200));
    c.setInputEntry('a', 0, '< 190');
    c.undo();
    expect(c.state.table!.rules.first.inputEntries.first, '< 18', reason: 'the three keystrokes were one step');
    c.redo();
    tick(const Duration(seconds: 3));
    c.setInputEntry('a', 0, '< 5'); // after a pause: new step
    c.setInputEntry('b', 0, '>= 5'); // different cell: new step
    c.undo();
    expect(c.state.table!.rules.last.inputEntries.first, '>= 18');
    expect(c.state.table!.rules.first.inputEntries.first, '< 5');
  });

  test('undo and redo bump the epoch so the fields rebuild, and mark the table unsaved', () {
    final (c, _) = _controller();
    final e0 = c.state.epoch;
    c.renameTable('X');
    expect(c.state.epoch, e0);
    c.undo();
    expect(c.state.epoch, e0 + 1);
    expect(c.state.dirty, isTrue);
    c.redo();
    expect(c.state.epoch, e0 + 2);
  });

  test('undo with nothing to undo does nothing', () {
    final (c, _) = _controller();
    c.undo();
    c.redo();
    expect(c.state.table!.rules.length, 2);
    expect(c.state.canUndo, isFalse);
  });

  test('duplicate puts a copy right below with a new id; move swaps; the ends do not move', () {
    final (c, _) = _controller();
    c.duplicateRule('a');
    expect(c.state.table!.rules.map((r) => r.id).toList().length, 3);
    expect(c.state.table!.rules[1].id == 'a', isFalse);
    expect(c.state.table!.rules[1].inputEntries, ['< 18']);
    expect(c.state.table!.rules[2].id, 'b');
    c.moveRule('b', -1);
    expect(c.state.table!.rules[1].id, 'b');
    c.moveRule('a', -1); // already first
    expect(c.state.table!.rules.first.id, 'a');
    c.moveRule('nope', 1);
    c.undo();
    c.undo();
    expect(c.state.table!.rules.length, 2);
  });

  test('history is capped', () {
    final (c, tick) = _controller();
    for (var i = 0; i < 130; i++) {
      tick(const Duration(seconds: 2));
      c.renameTable('n$i');
    }
    var steps = 0;
    while (c.state.canUndo) {
      c.undo();
      steps++;
    }
    expect(steps, 100);
  });
}
