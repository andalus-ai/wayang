import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/data/wayang_repository.dart';

/// A design-only server (WAYANG_MODE=design) must make Studio hide run actions; anything unclear keeps them (an older server has no endpoint).
Future<HttpServer> _serve(int status, Object body) async {
  final s = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  s.listen((req) {
    req.response
      ..statusCode = status
      ..headers.contentType = ContentType.json
      ..write(jsonEncode(body))
      ..close();
  });
  return s;
}

Future<bool> _executes(HttpServer s) =>
    WayangRepository(ApiClient(ApiConfig(baseUrl: 'http://127.0.0.1:${s.port}'))).executes();

void main() {
  test('a design-only server does not execute', () async {
    final s = await _serve(200, {'mode': 'design', 'executes': false});
    expect(await _executes(s), isFalse);
    await s.close(force: true);
  });

  test('a community server executes', () async {
    final s = await _serve(200, {'mode': 'community', 'executes': true});
    expect(await _executes(s), isTrue);
    await s.close(force: true);
  });

  test('an older server without the endpoint is treated as executing', () async {
    final s = await _serve(404, {'code': 'NOT_FOUND', 'message': 'no'});
    expect(await _executes(s), isTrue);
    await s.close(force: true);
  });

  test('an unreachable server is treated as executing, not hidden', () async {
    expect(await WayangRepository(ApiClient(const ApiConfig(baseUrl: 'http://127.0.0.1:1'))).executes(), isTrue);
  });
}
