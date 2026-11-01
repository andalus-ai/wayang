import 'dart:async';
import 'dart:convert';

import 'package:web_socket_channel/web_socket_channel.dart';

import 'api_client.dart';

/// One event pushed by the server for a run (`{"event":{type, runId, nodeId?, time, data}}`).
class RunEventView {
  const RunEventView({required this.type, required this.runId, this.nodeId, this.time, this.data = const {}});

  final String type;
  final String runId;
  final String? nodeId;
  final DateTime? time;
  final Object? data;

  factory RunEventView.fromJson(Map<String, dynamic> j) => RunEventView(
        type: '${j['type'] ?? 'UNKNOWN'}',
        runId: '${j['runId'] ?? ''}',
        nodeId: j['nodeId'] is String ? j['nodeId'] as String : null,
        time: j['time'] is String ? DateTime.tryParse(j['time'] as String) : null,
        data: j['data'],
      );

  bool get isOverflow => type == 'OVERFLOW';
}

/// `http(s)://host/base` becomes `ws(s)://host/base/ws/events?ticket=...`. Pure so it can be tested.
Uri eventsUri(String baseUrl, String ticket) {
  final base = Uri.parse(baseUrl.trim().replaceAll(RegExp(r'/+$'), ''));
  final scheme = base.scheme == 'https' ? 'wss' : 'ws';
  return base.replace(scheme: scheme, path: '${base.path}/ws/events', queryParameters: {'ticket': ticket});
}

/// Connects to `/ws/events` with a single-use ticket (the bearer token never goes into the URL) and subscribes to one run.
///
/// The stream ends when the socket closes or fails; the caller falls back to polling. It never throws into the listener.
class RunEventsClient {
  RunEventsClient({required this.config, required this.fetchTicket, WebSocketChannel Function(Uri)? connect})
      : _connect = connect ?? WebSocketChannel.connect;

  final ApiConfig config;
  final Future<String> Function() fetchTicket;
  final WebSocketChannel Function(Uri) _connect;

  Stream<RunEventView> watch(String runId) {
    WebSocketChannel? channel;
    StreamSubscription<dynamic>? sub;
    late final StreamController<RunEventView> out;
    out = StreamController<RunEventView>(
      onListen: () async {
        try {
          final ticket = await fetchTicket();
          if (out.isClosed) return;
          channel = _connect(eventsUri(config.baseUrl, ticket));
          sub = channel!.stream.listen(
            (frame) {
              final ev = _parse(frame);
              if (ev != null && !out.isClosed) out.add(ev);
            },
            onError: (Object _) {
              if (!out.isClosed) out.close();
            },
            onDone: () {
              if (!out.isClosed) out.close();
            },
            cancelOnError: true,
          );
          channel!.sink.add(jsonEncode({'id': '1', 'op': 'subscribe', 'runId': runId}));
        } catch (_) {
          if (!out.isClosed) await out.close();
        }
      },
      onCancel: () async {
        await sub?.cancel();
        try {
          await channel?.sink.close();
        } catch (_) {
          // already closed
        }
      },
    );
    return out.stream;
  }

  /// Replies (`{"id","ok"}`) and malformed frames yield null; only `{"event":{...}}` frames are events.
  static RunEventView? _parse(dynamic frame) {
    try {
      final j = jsonDecode(frame is String ? frame : utf8.decode(frame as List<int>));
      if (j is Map && j['event'] is Map) return RunEventView.fromJson(Map<String, dynamic>.from(j['event'] as Map));
    } catch (_) {
      // ignore bad frame
    }
    return null;
  }
}
