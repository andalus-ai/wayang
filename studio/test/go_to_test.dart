import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/shell/go_to.dart';

void main() {
  const all = [
    GoToEntry('dashboard', 'Overview', Icons.home),
    GoToEntry('bpmn', 'Process Designer', Icons.account_tree),
    GoToEntry('dmn', 'Decision Tables', Icons.table_chart),
    GoToEntry('runs', 'Runs', Icons.play_arrow),
    GoToEntry('secrets', 'Secrets', Icons.key),
  ];

  test('an empty query keeps every section in order', () {
    expect(filterGoTo(all, '  ').map((e) => e.key), ['dashboard', 'bpmn', 'dmn', 'runs', 'secrets']);
  });

  test('matches every word, ignoring case, and puts prefix matches first', () {
    expect(filterGoTo(all, 'design').map((e) => e.key), ['bpmn']);
    expect(filterGoTo(all, 'PROCESS des').map((e) => e.key), ['bpmn']);
    expect(filterGoTo(all, 'r').map((e) => e.key).first, 'runs');
    expect(filterGoTo(all, 'zzz'), isEmpty);
  });
}
