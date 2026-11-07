import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/nested_step_path.dart';

void main() {
  test('reads, replaces, inserts, removes and moves nested steps', () {
    final source = {
      'delivery': 'keep',
      'steps': [
        {'type': 'choice', 'steps': [
          {'type': 'label', 'name': 'a'},
          {'type': 'label', 'name': 'b'},
        ]},
      ],
    };
    const parent = StepPath([0]);
    expect(stepAtPath(source, parent.child(1))?['name'], 'b');

    final replaced = replaceStepAtPath(source, parent.child(1),
        {'type': 'label', 'name': 'updated'})!;
    expect(stepAtPath(replaced, parent.child(1))?['name'], 'updated');
    expect(stepAtPath(source, parent.child(1))?['name'], 'b');
    expect(replaced['delivery'], 'keep');

    final inserted = insertStepAtPath(source, parent, 1,
        {'type': 'label', 'name': 'middle'})!;
    expect(stepAtPath(inserted, parent.child(1))?['name'], 'middle');

    final moved = moveStepAtPath(inserted, parent.child(0), 2)!;
    expect(stepAtPath(moved, parent.child(2))?['name'], 'a');

    final removed = removeStepAtPath(moved, parent.child(1))!;
    expect((stepAtPath(removed, parent) ?? {})['steps'], hasLength(2));
  });

  test('invalid and empty paths fail safely', () {
    final doc = {'steps': [{'type': 'guard', 'steps': []}]};
    expect(stepAtPath(doc, const StepPath([])), isNull);
    expect(stepAtPath(doc, const StepPath([2])), isNull);
    expect(replaceStepAtPath(doc, const StepPath([0, 0]), {'type': 'x'}), isNull);
    expect(insertStepAtPath(doc, const StepPath([0, 0]), 0, {'type': 'x'}), isNull);
    expect(removeStepAtPath(doc, const StepPath([0, 0])), isNull);
  });

  test('path equality is structural', () {
    expect(const StepPath([1, 2]), const StepPath([1, 2]));
    expect(const StepPath([1, 2]), isNot(const StepPath([2, 1])));
  });
}
