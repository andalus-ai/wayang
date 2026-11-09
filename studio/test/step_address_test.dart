import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/step_address.dart';

void main() {
  group('StepAddress', () {
    test('addresses list branches and preserves branch metadata', () {
      final source = {
        'delivery': {'mode': 'at-least-once'},
        'steps': [
          {'type': 'choice', 'branches': [
            {'when': 'vip', 'description': 'VIP lane', 'steps': [
              {'type': 'label', 'name': 'vip-a'},
              {'type': 'label', 'name': 'vip-b'},
            ]},
            {'when': 'standard', 'steps': [{'type': 'label', 'name': 'standard'}]},
          ]},
        ],
      };
      final child = StepAddress.root(0).field('branches').at(0).field('steps').at(1);
      expect(stepAtAddress(source, child)?['name'], 'vip-b');

      final updated = replaceStepAtAddress(source, child,
        {'type': 'label', 'name': 'changed'})!;
      expect(stepAtAddress(updated, child)?['name'], 'changed');
      expect(stepAtAddress(source, child)?['name'], 'vip-b');
      final branch = _at(updated, StepAddress.root(0).field('branches').at(0)) as Map;
      expect(branch['when'], 'vip');
      expect(branch['description'], 'VIP lane');
      expect(updated['delivery'], {'mode': 'at-least-once'});
    });

    test('addresses keyed cases, multicast branches and load-balance targets', () {
      final source = {
        'steps': [{
          'type': 'switch',
          'cases': {'paid': [{'type': 'label', 'name': 'paid'}]},
          'branches': {'primary': [{'type': 'call', 'name': 'primary'}]},
          'targets': {'backup': [{'type': 'call', 'name': 'backup'}]},
        }],
      };
      final paid = StepAddress.root(0).field('cases').field('paid').at(0);
      final primary = StepAddress.root(0).field('branches').field('primary').at(0);
      final backup = StepAddress.root(0).field('targets').field('backup').at(0);
      expect(stepAtAddress(source, paid)?['name'], 'paid');
      expect(stepAtAddress(source, primary)?['name'], 'primary');
      expect(stepAtAddress(source, backup)?['name'], 'backup');
    });

    test('inserts, moves and removes items in a keyed lane sequence', () {
      final source = {'steps': [{'type': 'choice', 'cases': {
        'paid': [{'type': 'label', 'name': 'a'}, {'type': 'label', 'name': 'b'}],
      }}]};
      final lane = StepAddress.root(0).field('cases').field('paid');
      final inserted = insertStepAtAddress(source, lane, 1,
        {'type': 'label', 'name': 'middle'})!;
      expect(stepAtAddress(inserted, lane.at(1))?['name'], 'middle');
      final moved = moveStepAtAddress(inserted, lane.at(0), 2)!;
      expect(stepAtAddress(moved, lane.at(2))?['name'], 'a');
      final removed = removeStepAtAddress(moved, lane.at(1))!;
      expect(_at(removed, lane), hasLength(2));
      expect(_at(source, lane), hasLength(2));
    });

    test('supports otherwise, default, fallback, and routing slip stages', () {
      final source = {'steps': [{
        'type': 'switch',
        'otherwise': [{'type': 'label', 'name': 'otherwise'}],
        'default': [{'type': 'label', 'name': 'default'}],
        'fallback': [{'type': 'label', 'name': 'fallback'}],
        'stages': {'first': [{'type': 'call', 'name': 'stage'}]},
      }]};
      final root = StepAddress.root(0);
      expect(stepAtAddress(source, root.field('otherwise').at(0))?['name'], 'otherwise');
      expect(stepAtAddress(source, root.field('default').at(0))?['name'], 'default');
      expect(stepAtAddress(source, root.field('fallback').at(0))?['name'], 'fallback');
      expect(stepAtAddress(source, root.field('stages').field('first').at(0))?['name'], 'stage');
    });

    test('rejects stale, wrong-container, and out-of-range addresses safely', () {
      final source = {'steps': [{'type': 'choice', 'branches': {'x': []}}]};
      final wrong = StepAddress.root(0).field('branches').at(0);
      final missing = StepAddress.root(0).field('branches').field('missing').at(0);
      expect(stepAtAddress(source, wrong), isNull);
      expect(stepAtAddress(source, missing), isNull);
      expect(replaceStepAtAddress(source, missing, {'type': 'x'}), isNull);
      expect(removeStepAtAddress(source, missing), isNull);
      expect(insertStepAtAddress(source, missing, 0, {'type': 'x'}), isNull);
      expect(moveStepAtAddress(source, missing, 1), isNull);
    });

    test('address equality and string representation are structural', () {
      final a = StepAddress.root(0).field('cases').field('paid').at(2);
      final b = StepAddress([
        const StepAddressSegment.key('steps'), const StepAddressSegment.index(0),
        const StepAddressSegment.key('cases'), const StepAddressSegment.key('paid'),
        const StepAddressSegment.index(2),
      ]);
      expect(a, b);
      expect(a.toString(), ' .steps[0].cases.paid[2]'.trim());
      expect(a.hashCode, b.hashCode);
    });
  });
}

Object? _at(Map<String, dynamic> document, StepAddress address) {
  Object? current = document;
  for (final segment in address.segments) {
    if (segment.isIndex) {
      if (current is! List || segment.index! >= current.length) return null;
      current = current[segment.index!];
    } else {
      if (current is! Map) return null;
      current = current[segment.key];
    }
  }
  return current;
}
