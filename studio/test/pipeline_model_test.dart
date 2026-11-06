import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/pipeline_model.dart';

// Not run in the environment that produced it (no Dart SDK there). The catalog itself is checked by a Java test that builds
// every template with the real pipeline loader.

void main() {
  final kinds = parseCatalog(File('assets/pipeline_steps.json').readAsStringSync());

  test('the catalog has unique types and every template carries its own type', () {
    expect(kinds.map((k) => k.type).toSet().length, kinds.length);
    for (final k in kinds) {
      expect(k.template['type'], k.type);
    }
  });

  test('instantiate gives an independent copy', () {
    final k = kinds.firstWhere((k) => k.type == 'choice');
    final a = k.instantiate();
    (a['branches'] as List).clear();
    expect((k.instantiate()['branches'] as List), isNotEmpty);
  });

  test('stepsOf reads steps and refuses anything else', () {
    expect(stepsOf('{"steps":[{"type":"label","name":"x"}]}')!.single['name'], 'x');
    expect(stepsOf('{}'), isEmpty);
    expect(stepsOf('not json'), isNull);
    expect(stepsOf('[1]'), isNull);
    expect(stepsOf('{"steps":"x"}'), isNull);
    expect(stepsOf('{"steps":[1]}'), isNull);
  });

  test('encodeSteps keeps other top-level keys and round-trips', () {
    final text = encodeSteps([
      {'type': 'label', 'name': 'a'}
    ], previous: '{"delivery":"x","steps":[]}');
    final doc = jsonDecode(text) as Map;
    expect(doc['delivery'], 'x');
    expect(stepsOf(text)!.single['name'], 'a');
    expect(stepsOf(encodeSteps([], previous: 'garbage')), isEmpty);
  });

  test('moved swaps neighbours and ignores moves off the ends', () {
    expect(moved([1, 2, 3], 0, 1), [2, 1, 3]);
    expect(moved([1, 2, 3], 2, -1), [1, 3, 2]);
    expect(moved([1, 2, 3], 0, -1), [1, 2, 3]);
    expect(moved([1, 2, 3], 2, 1), [1, 2, 3]);
    expect(moved([1, 2, 3], 5, -1), [1, 2, 3]);
    final original = [1, 2, 3];
    moved(original, 0, 1);
    expect(original, [1, 2, 3]);
  });

  test('describeStep summarises settings and nested steps', () {
    expect(describeStep({'type': 'parse', 'format': 'json'}), 'format: json');
    final d = describeStep({
      'type': 'choice',
      'branches': [
        {'when': 'a', 'steps': [{}, {}]}
      ],
      'otherwise': [{}]
    });
    expect(d, '3 nested steps');
    expect(describeStep({'type': 'x'}), '');
  });

  test('nestedStepGroups projects choice and switch lanes without changing JSON', () {
    final choice = {
      'type': 'choice',
      'branches': [
        {'when': 'body.vip', 'steps': [{'type': 'label', 'name': 'vip'}]},
        {'when': 'body.region == "eu"', 'steps': [{'type': 'label', 'name': 'eu'}]},
      ],
      'otherwise': [{'type': 'label', 'name': 'standard'}],
    };
    final groups = nestedStepGroups(choice);
    expect(groups.map((g) => g.label), ['Branch 1', 'Branch 2', 'Otherwise']);
    expect(groups.first.condition, 'body.vip');
    expect(groups.first.steps.single['name'], 'vip');
    expect(choice['branches'], hasLength(2));

    final switchStep = {
      'type': 'switch',
      'cases': {
        'paid': [{'type': 'label', 'name': 'paid'}],
        'trial': [{'type': 'label', 'name': 'trial'}],
      },
      'default': [{'type': 'label', 'name': 'other'}],
    };
    expect(nestedStepGroups(switchStep).map((g) => g.label),
        ['Case · paid', 'Case · trial', 'Default']);
  });

  test('nestedStepGroups supports multicast, load balance, stages and wrappers', () {
    expect(nestedStepGroups({
      'type': 'multicast',
      'branches': {
        'left': [{'type': 'label', 'name': 'left'}],
        'right': [{'type': 'label', 'name': 'right'}],
      },
    }).map((g) => g.label), ['Branch · left', 'Branch · right']);

    expect(nestedStepGroups({
      'type': 'loadBalance',
      'targets': {'one': [{'type': 'label'}], 'two': [{'type': 'label'}]},
    }).map((g) => g.label), ['Target · one', 'Target · two']);

    expect(nestedStepGroups({
      'type': 'circuitBreaker',
      'steps': [{'type': 'call'}],
      'fallback': [{'type': 'label'}],
    }).map((g) => g.label), ['Main steps', 'Fallback']);
  });

  test('routeSummaries projects router destinations and conditions', () {
    expect(routeSummaries({
      'type': 'router',
      'routes': [
        {'when': 'body.vip', 'to': 'fast'},
        {'to': 'standard'},
      ],
      'otherwise': 'slow',
    }), [
      'fast  when body.vip',
      'standard',
      'otherwise → slow',
    ]);
    expect(routeSummaries({'type': 'parse'}), isEmpty);
  });

  test('parseStep explains what is wrong', () {
    expect(parseStep('{"type":"label","name":"a"}')['name'], 'a');
    expect(() => parseStep('{'), throwsA(isA<FormatException>()));
    expect(() => parseStep('[]'), throwsA(isA<FormatException>()));
    expect(() => parseStep('{"name":"a"}'), throwsA(isA<FormatException>()));
    expect(() => parseStep('{"type":""}'), throwsA(isA<FormatException>()));
  });
}
