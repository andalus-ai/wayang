import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/wayang_repository.dart';

void main() {
  test('the CSV export asks for text/csv, the kind and the 500 cap, with the token only in the header', () async {
    late http.Request seen;
    final client = MockClient((req) async {
      seen = req;
      return http.Response('time,kind\n2026-11-09,bug\n', 200, headers: {'content-type': 'text/csv'});
    });
    final repo = WayangRepository(ApiClient(const ApiConfig(baseUrl: 'http://localhost:8080/', token: 'tok'), client: client));
    final csv = await repo.exportFeedbackCsv(kind: 'bug');
    expect(csv, startsWith('time,kind'));
    expect(seen.url.path, '/api/v1/feedback/export');
    expect(seen.url.queryParameters, {'kind': 'bug', 'limit': '500'});
    expect(seen.headers['accept'], 'text/csv');
    expect(seen.url.toString().contains('tok'), isFalse);
  });

  test('without a kind the filter is left out', () async {
    late Uri url;
    final repo = WayangRepository(ApiClient(const ApiConfig(baseUrl: 'http://localhost:8080'), client: MockClient((req) async {
      url = req.url;
      return http.Response('time\n', 200);
    })));
    await repo.exportFeedbackCsv();
    expect(url.queryParameters, {'limit': '500'});
  });
}
