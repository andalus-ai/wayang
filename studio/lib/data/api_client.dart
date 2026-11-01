import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

/// Where the Wayang API lives and how to authenticate. The tenant is **not** configured here: the server takes it
/// from the verified token, never from the client.
class ApiConfig {
  const ApiConfig({required this.baseUrl, this.token, this.tenant});

  final String baseUrl;
  final String? token;

  /// Sent as `X-Tenant` (Numu mode): the tenant to act in. The server checks membership; it is a request, not a claim.
  final String? tenant;

  bool get isConfigured => baseUrl.trim().isNotEmpty;

  ApiConfig copyWith({String? baseUrl, String? token, bool clearToken = false, String? tenant}) => ApiConfig(
        baseUrl: baseUrl ?? this.baseUrl,
        token: clearToken ? null : (token ?? this.token),
        tenant: tenant ?? this.tenant,
      );
}

/// A non-2xx answer, in the server's error envelope `{code, message, details}`.
class WayangApiException implements Exception {
  const WayangApiException(this.status, this.code, this.message, [this.details = const []]);

  final int status;
  final String code;
  final String message;
  final List<String> details;

  bool get isConflict => status == 409;
  bool get isNotFound => status == 404;
  bool get isUnauthorized => status == 401;
  bool get isForbidden => status == 403;
  bool get isValidation => status == 422;

  /// Message plus the server's detail lines, for showing in a dialog.
  String get fullMessage => details.isEmpty ? message : '$message\n${details.join('\n')}';

  @override
  String toString() => message;
}

/// Thin JSON-over-HTTP wrapper: auth header, timeouts, and turning errors into [WayangApiException].
class ApiClient {
  ApiClient(this.config, {http.Client? client, this.timeout = const Duration(seconds: 30)}) : _http = client ?? http.Client();

  final ApiConfig config;
  final Duration timeout;
  final http.Client _http;

  Uri _uri(String path, Map<String, String?>? query) {
    final base = config.baseUrl.trim().replaceAll(RegExp(r'/+$'), '');
    final params = <String, String>{
      if (query != null)
        for (final e in query.entries)
          if (e.value != null && e.value!.isNotEmpty) e.key: e.value!,
    };
    return Uri.parse('$base$path').replace(queryParameters: params.isEmpty ? null : params);
  }

  Map<String, String> _headers({String? contentType, String? accept, Map<String, String>? extra}) => {
        'Accept': accept ?? 'application/json',
        if (contentType != null) 'Content-Type': contentType,
        if (config.token != null && config.token!.isNotEmpty) 'Authorization': 'Bearer ${config.token}',
        if (config.tenant != null && config.tenant!.isNotEmpty) 'X-Tenant': config.tenant!,
        ...?extra,
      };

  /// GET returning decoded JSON (null for an empty body).
  Future<dynamic> getJson(String path, {Map<String, String?>? query}) async {
    final r = await _run(() => _http.get(_uri(path, query), headers: _headers()));
    return _decode(r);
  }

  /// GET returning the raw text (used for the BPMN XML export).
  Future<String> getText(String path, {Map<String, String?>? query, String accept = 'application/xml'}) async {
    final r = await _run(() => _http.get(_uri(path, query), headers: _headers(accept: accept)));
    _throwIfError(r);
    return utf8.decode(r.bodyBytes);
  }

  Future<dynamic> sendJson(String method, String path, {Object? body, Map<String, String>? headers, Map<String, String?>? query}) async {
    final req = http.Request(method, _uri(path, query))
      ..headers.addAll(_headers(contentType: 'application/json', extra: headers));
    if (body != null) req.body = jsonEncode(body);
    final r = await _run(() async => http.Response.fromStream(await _http.send(req)));
    return _decode(r);
  }

  Future<dynamic> postText(String path, String body, {String contentType = 'application/xml'}) async {
    final r = await _run(() => _http.post(_uri(path, null), headers: _headers(contentType: contentType), body: body));
    return _decode(r);
  }

  Future<http.Response> _run(Future<http.Response> Function() call) async {
    try {
      return await call().timeout(timeout);
    } on TimeoutException {
      throw const WayangApiException(0, 'TIMEOUT', 'The server did not answer in time');
    } on http.ClientException catch (e) {
      throw WayangApiException(0, 'UNREACHABLE', 'Cannot reach the server: ${e.message}');
    }
  }

  dynamic _decode(http.Response r) {
    _throwIfError(r);
    if (r.bodyBytes.isEmpty) return null;
    return jsonDecode(utf8.decode(r.bodyBytes));
  }

  void _throwIfError(http.Response r) {
    if (r.statusCode >= 200 && r.statusCode < 300) return;
    String code = 'HTTP_${r.statusCode}';
    String message = 'Request failed (HTTP ${r.statusCode})';
    var details = <String>[];
    try {
      final body = jsonDecode(utf8.decode(r.bodyBytes));
      if (body is Map) {
        if (body['code'] is String) code = body['code'] as String;
        if (body['message'] is String) message = body['message'] as String;
        if (body['details'] is List) details = [for (final d in body['details'] as List) '$d'];
      }
    } catch (_) {
      // not JSON: keep the generic message
    }
    throw WayangApiException(r.statusCode, code, message, details);
  }
}

/// What to show a person for any failure.
String errText(Object e) => e is WayangApiException ? e.fullMessage : '$e';
