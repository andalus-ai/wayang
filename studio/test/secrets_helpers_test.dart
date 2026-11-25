import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/secrets/secrets_screen.dart';
import 'package:wayang_studio/models/wayang.dart';

SecretMeta m(String n) => SecretMeta(name: n, version: 1, keyId: 'k1');

void main() {
  test('the reference never contains a value, only the name', () {
    expect(secretReference('CRM_TOKEN'), r'${secret:CRM_TOKEN}');
  });

  test('search matches every word of the name, ignoring case', () {
    final all = [m('CRM_TOKEN'), m('CRM_URL'), m('SMTP_PASSWORD')];
    expect(filterSecrets(all, '').map((e) => e.name), ['CRM_TOKEN', 'CRM_URL', 'SMTP_PASSWORD']);
    expect(filterSecrets(all, 'crm').map((e) => e.name), ['CRM_TOKEN', 'CRM_URL']);
    expect(filterSecrets(all, 'crm tok').map((e) => e.name), ['CRM_TOKEN']);
    expect(filterSecrets(all, 'zzz'), isEmpty);
  });
}
