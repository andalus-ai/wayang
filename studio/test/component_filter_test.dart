import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/components/component_catalog_screen.dart';
import 'package:wayang_studio/models/wayang.dart';

ComponentCatalogEntry c(String id, String name, String category, {String desc = '', List<String> caps = const []}) =>
    ComponentCatalogEntry.fromJson({'id': id, 'displayName': name, 'category': category, 'description': desc, 'capabilities': caps});

void main() {
  final all = [
    c('wayang.endpoints.jms-send', 'JMS send', 'endpoints', desc: 'Send one persistent text message', caps: ['jms.send']),
    c('wayang.endpoints.amqp-publish', 'AMQP publish', 'endpoints', caps: ['amqp.publish']),
    c('wayang.eip.splitter', 'Splitter', 'eip'),
  ];

  test('an empty query keeps everything', () {
    expect(filterComponents(all, '  ').length, 3);
  });

  test('every word must match, in any field, ignoring case', () {
    expect(filterComponents(all, 'ENDPOINTS').map((e) => e.displayName), ['JMS send', 'AMQP publish']);
    expect(filterComponents(all, 'endpoints publish').map((e) => e.displayName), ['AMQP publish']);
    expect(filterComponents(all, 'jms.send').map((e) => e.displayName), ['JMS send']);
    expect(filterComponents(all, 'persistent').map((e) => e.displayName), ['JMS send']);
    expect(filterComponents(all, 'zzz'), isEmpty);
  });
}
