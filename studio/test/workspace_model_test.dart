import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/workspace_model.dart';

// Not run in the environment that produced it (no Dart SDK there).

void main() {
  test('import options default to a dry run that fails on conflicts and serialise what the server reads', () {
    const o = ImportOptions();
    expect(o.toJson(), {
      'dryRun': true,
      'onConflict': 'fail',
      'publish': false,
      'triggers': true,
      'projectConfig': true,
      'tenantConfig': false,
      'allowUnsigned': false,
    });
    final t = o.copyWith(targetProject: 'p1', dryRun: false, onConflict: 'skip');
    expect(t.toJson()['targetProject'], 'p1');
    expect(t.toJson()['dryRun'], false);
    expect(t.copyWith(targetProject: null).toJson().containsKey('targetProject'), isFalse, reason: 'null means a new project');
    expect(t.copyWith(publish: true).targetProject, 'p1', reason: 'other changes keep the target');
  });

  test('sameAs ignores dry-run only', () {
    const a = ImportOptions();
    expect(a.sameAs(a.copyWith(dryRun: false)), isTrue);
    expect(a.sameAs(a.copyWith(onConflict: 'overwrite')), isFalse);
    expect(a.sameAs(a.copyWith(targetProject: 'x')), isFalse);
    expect(a.sameAs(a.copyWith(tenantConfig: true)), isFalse);
    expect(a.sameAs(a.copyWith(allowUnsigned: true)), isFalse);
    expect(a.sameAs(a.copyWith(publish: true)), isFalse);
    expect(a.sameAs(a.copyWith(triggers: false)), isFalse);
    expect(a.sameAs(a.copyWith(projectConfig: false)), isFalse);
  });

  test('a report reads the server answer and only a clean dry run can be applied', () {
    final r = ImportReport.fromJson({
      'dryRun': true,
      'ok': true,
      'project': null,
      'items': [
        {'type': 'project', 'id': 'Shop', 'action': 'create', 'status': 'ok', 'message': 'A new project is created'},
        {'type': 'process', 'id': 'orders', 'action': 'conflict', 'status': 'error', 'message': 'Already exists'},
      ],
      'problems': [],
      'warnings': ['w'],
      'missingSecrets': ['SLACK'],
      'secretsChecked': true,
      'missingConfig': [],
      'oauthConnections': ['oauth.work'],
      'webhookSecrets': {},
    });
    expect(r.canApply, isTrue);
    expect(r.needsSetup, isTrue);
    expect(r.items[1].isError, isTrue);
    expect(r.missingSecrets, ['SLACK']);
    expect(ImportReport.fromJson({'dryRun': true, 'ok': false}).canApply, isFalse);
    expect(ImportReport.fromJson({'dryRun': false, 'ok': true}).canApply, isFalse, reason: 'already applied');
    expect(ImportReport.fromJson({}).needsSetup, isFalse);
    expect(ImportReport.fromJson({'webhookSecrets': {'hook1': 'abc'}}).webhookSecrets, {'hook1': 'abc'});
  });

  test('settings the server would refuse are caught before sending', () {
    expect(configProblem('BASE_URL', 'https://x'), isNull);
    expect(configProblem('', 'v'), isNotNull);
    expect(configProblem('a b', 'v'), isNotNull);
    expect(configProblem('API_TOKEN', 'v'), contains('credential'));
    expect(configProblem('db.Password', 'v'), contains('credential'));
    expect(configProblem('K', r'x ${secret:Y}'), isNotNull);
    expect(configProblem('K', 'x' * 4097), isNotNull);
    expect(configProblem('K', 'x' * 4096), isNull);
  });

  test('bundle summary counts without reading definitions, and tolerates odd shapes', () {
    expect(
        bundleSummary({
          'project': {'name': 'Shop'},
          'definitions': [1, 2],
          'triggers': [1],
          'config': {'project': {'A': 'x'}, 'tenant': {'B': 'y', 'C': 'z'}}
        }),
        'Shop: 2 definition(s), 1 trigger(s), 3 setting(s)');
    expect(bundleSummary({}), 'workspace: 0 definition(s), 0 trigger(s), 0 setting(s)');
    expect(bundleSummary({'definitions': 'x', 'config': 5}), 'workspace: 0 definition(s), 0 trigger(s), 0 setting(s)');
    expect(looksLikeBundle({'format': 'wayang.workspace', 'formatVersion': 1}), isTrue);
    expect(looksLikeBundle({'format': 'other', 'formatVersion': 1}), isFalse);
    expect(looksLikeBundle({'format': 'wayang.workspace'}), isFalse);
  });

  test('config items read the server view', () {
    final c = ConfigItem.fromJson({'key': 'CHANNEL', 'value': '#orders', 'version': 2, 'project': 'p1', 'updatedBy': 'ann'});
    expect(c.project, 'p1');
    expect(c.version, 2);
    expect(ConfigItem.fromJson({'key': 'K', 'project': null}).project, isNull);
  });

  test('key status reads the server view and summarises rotation progress', () {
    final k = KeyStatus.fromJson({'activeKeyId': 'k2', 'secretsByKey': {'k1': 2, 'k2': 3}, 'needRotation': 2});
    expect(k.total, 5);
    expect(k.summary, contains('2 still under an older key'));
    final done = KeyStatus.fromJson({'activeKeyId': 'k2', 'secretsByKey': {'k2': 1}, 'needRotation': 0});
    expect(done.summary, contains('all sealed'));
    expect(KeyStatus.fromJson({'activeKeyId': 'k1'}).summary, contains('no secrets'));
    expect(KeyStatus.fromJson({'secretsByKey': 'x', 'needRotation': 'y'}).total, 0);
  });

  test('secret names are checked like the server does', () {
    expect(secretNameProblem('CRM_TOKEN'), isNull);
    expect(secretNameProblem('a.b-c'), isNull);
    expect(secretNameProblem(''), isNotNull);
    expect(secretNameProblem('has space'), isNotNull);
    expect(secretNameProblem('x' * 101), isNotNull);
    expect(secretNameProblem('vault.X'), contains('Vault'));
    expect(secretNameProblem('file.X'), contains('files'));
    expect(secretNameProblem('oauth-x'), contains('reserved'));
    expect(secretNameProblem('aws.X'), contains('cloud'));
    expect(secretNameProblem('azure.X'), contains('cloud'));
    expect(secretNameProblem('gcp.X'), contains('cloud'));
    expect(secretNameProblem('awsome'), isNull);
    expect(secretNameProblem('vaults.X'), isNull);
  });

  test('release form validates like the server and serialises only what is set', () {
    const f = ReleaseForm();
    expect(f.problem, isNull);
    expect(f.toJson(), {'target': 'SERVICE', 'groupId': 'com.example', 'version': '1.0.0', 'port': 8080});
    final g = f.copyWith(artifactId: 'shop', packageName: 'com.shop.release', target: 'STANDALONE');
    expect(g.toJson()['artifactId'], 'shop');
    expect(g.toJson()['package'], 'com.shop.release');
    expect(g.toJson()['target'], 'STANDALONE');
    expect(f.copyWith(groupId: 'Com.Acme').problem, contains('groupId'));
    expect(f.copyWith(artifactId: 'A B').problem, contains('artifactId'));
    expect(f.copyWith(version: '1<').problem, contains('version'));
    expect(f.copyWith(packageName: 'com.class').problem, contains('keyword'));
    expect(f.copyWith(packageName: 'com.x;evil').problem, contains('package'));
    expect(f.copyWith(port: 0).problem, contains('port'));
    expect(f.copyWith(port: 70000).problem, contains('port'));
  });

  test('release plan reads the server view and builds a token-free download command', () {
    final p = ReleasePlan.fromJson({
      'target': 'SERVICE',
      'artifactId': 'shop',
      'version': '1.0.0',
      'processes': ['orders'],
      'connectors': ['slack'],
      'usesAgents': false,
      'requiredSecrets': ['SLACK'],
      'requiredConfig': ['CHANNEL'],
      'files': ['pom.xml'],
      'notes': ['n1'],
      'bundleSha256': 'abc',
    });
    expect(p.summary, '1 process(es), connectors: slack, 1 secret(s), 1 setting(s)');
    expect(p.zipName, 'shop-1.0.0.zip');
    expect(ReleasePlan.fromJson({}).summary, '0 process(es), no connectors, 0 secret(s), 0 setting(s)');
    final cmd = releaseDownloadCommand('https://w.example.com/', 'p1', const ReleaseForm(artifactId: "it's"), p.zipName);
    expect(cmd, contains("https://w.example.com/api/v1/projects/p1/release'"));
    expect(cmd, contains(r'Bearer $WAYANG_TOKEN'));
    expect(cmd, contains("it'\\''s"));
    expect(cmd, endsWith("-o 'shop-1.0.0.zip'"));
  });

  test('assistant template fields are checked before sending', () {
    expect(assistantFieldProblem('chatId', '-100123'), isNull);
    expect(assistantFieldProblem('chatId', 'abc'), isNotNull);
    expect(assistantFieldProblem('channelId', 'D0123ABCD'), isNull);
    expect(assistantFieldProblem('channelId', 'd0123'), isNotNull);
    expect(assistantFieldProblem('channelId', '123456'), isNotNull);
    expect(assistantFieldProblem('model', 'openai/gpt-4o-mini'), isNull);
    expect(assistantFieldProblem('model', 'gpt4'), isNotNull);
    expect(assistantFieldProblem('persona', 'x' * 1001), isNotNull);
    expect(assistantFieldProblem('name', ' '), isNotNull);
    expect(assistantFieldProblem('lookupUrl', ''), isNull);
    expect(assistantFieldProblem('lookupUrl', 'https://api.example.com/v1/search'), isNull);
    expect(assistantFieldProblem('lookupUrl', 'http://api.example.com'), isNotNull);
    expect(assistantFieldProblem('lookupUrl', 'https://u:p@api.example.com'), isNotNull);
    expect(assistantFieldProblem('lookupUrl', 'https://api.example.com/?a=1'), isNotNull);
    expect(assistantFieldProblem('lookupDescription', 'x' * 201), isNotNull);
    expect(assistantFieldProblem('calendarConnection', 'work_1'), isNull);
    expect(assistantFieldProblem('calendarConnection', 'a b'), isNotNull);
    expect(assistantFieldProblem('topic', 'the day ahead'), isNull);
    expect(assistantFieldProblem('topic', ' '), isNotNull);
    expect(assistantFieldProblem('topic', 'x' * 301), isNotNull);
    expect(assistantFieldProblem('cron', '0 8 * * 1-5'), isNull);
    expect(assistantFieldProblem('cron', '@daily'), isNull);
    expect(assistantFieldProblem('cron', '0 8 * *'), isNotNull);
    expect(assistantFieldProblem('timezone', 'Asia/Jakarta'), isNull);
    expect(assistantFieldProblem('timezone', 'Mars X'), isNotNull);
    expect(assistantFieldProblem('lookupSecret', 'NOTES_TOKEN'), isNull);
    expect(assistantFieldProblem('lookupSecret', ''), isNull);
    expect(assistantFieldProblem('lookupSecret', 'a-b'), isNotNull);
  });
}
