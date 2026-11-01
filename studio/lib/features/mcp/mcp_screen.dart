import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';

final mcpServersProvider = FutureProvider.autoDispose<List<McpServerInfo>>((ref) => ref.watch(repositoryProvider).listMcpServers());

/// MCP servers generated from an OpenAPI / Swagger document. Any MCP client can use the endpoint; workflows call a tool
/// with a service task (connector `mcp`).
class McpScreen extends ConsumerWidget {
  const McpScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final servers = ref.watch(mcpServersProvider);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ScreenHeader(
            title: 'MCP servers',
            subtitle: 'Turn an OpenAPI / Swagger spec into an MCP server. Read-only operations by default.',
            icon: Icons.hub_rounded,
            actions: [
              IconButton(tooltip: 'Refresh', onPressed: () => ref.invalidate(mcpServersProvider), icon: const Icon(Icons.refresh_rounded)),
              const SizedBox(width: 4),
              ElevatedButton.icon(
                onPressed: () async {
                  final done = await showDialog<bool>(context: context, builder: (_) => const _ImportDialog());
                  if (done == true) ref.invalidate(mcpServersProvider);
                },
                icon: const Icon(Icons.add_rounded, size: 18),
                label: const Text('Import OpenAPI'),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Expanded(
            child: servers.when(
              loading: () => const LoadingView(),
              error: (e, _) => ErrorView(message: errText(e), onRetry: () => ref.invalidate(mcpServersProvider)),
              data: (list) => list.isEmpty
                  ? const EmptyState(icon: Icons.hub_outlined, title: 'No MCP servers', message: 'Import an OpenAPI document to create one.')
                  : ListView(children: [for (final s in list) _ServerCard(s: s)]),
            ),
          ),
        ],
      ),
    );
  }
}

class _ServerCard extends ConsumerWidget {
  const _ServerCard({required this.s});
  final McpServerInfo s;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Card(
      child: ExpansionTile(
        leading: const Icon(Icons.hub_rounded),
        title: Text(s.id, style: const TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.w600)),
        subtitle: Text('${s.title} · ${s.tools.length} tools · ${s.baseUrl}', style: const TextStyle(fontSize: 11)),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        expandedCrossAxisAlignment: CrossAxisAlignment.start,
        onExpansionChanged: (_) {},
        children: [
          Row(children: [
            Expanded(
              child: SelectableText('MCP endpoint (POST, Authorization: Bearer <Wayang token>): ${s.endpoint}',
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
            ),
            IconButton(tooltip: 'Copy endpoint', icon: const Icon(Icons.copy_rounded, size: 16), onPressed: () => Clipboard.setData(ClipboardData(text: s.endpoint))),
          ]),
          _ToolList(id: s.id),
          Row(children: [
            if (s.sourceUrl != null && s.sourceUrl!.isNotEmpty)
              OutlinedButton.icon(
                onPressed: () async {
                  try {
                    final n = await ref.read(repositoryProvider).refreshMcpServer(s.id);
                    ref.invalidate(mcpServersProvider);
                    if (context.mounted) showToast(context, 'Regenerated: ${n.tools.length} tools.');
                  } catch (e) {
                    if (context.mounted) showToast(context, errText(e), isError: true);
                  }
                },
                icon: const Icon(Icons.sync_rounded, size: 16),
                label: const Text('Re-import from URL'),
              ),
            const Spacer(),
            TextButton.icon(
              onPressed: () async {
                final ok = await showConfirmDialog(context,
                    title: 'Delete ${s.id}?', message: 'Clients using its endpoint will stop working.', confirmLabel: 'Delete', destructive: true);
                if (!ok) return;
                try {
                  await ref.read(repositoryProvider).deleteMcpServer(s.id);
                  ref.invalidate(mcpServersProvider);
                } catch (e) {
                  if (context.mounted) showToast(context, errText(e), isError: true);
                }
              },
              icon: const Icon(Icons.delete_outline_rounded, size: 16),
              label: const Text('Delete'),
            ),
          ]),
        ],
      ),
    );
  }
}

/// The list call omits tools, so the detail is fetched when a card is expanded.
class _ToolList extends ConsumerWidget {
  const _ToolList({required this.id});
  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return FutureBuilder<McpServerInfo>(
      future: ref.read(repositoryProvider).getMcpServer(id),
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) return const Padding(padding: EdgeInsets.all(12), child: LinearProgressIndicator());
        if (snap.hasError) return Text(errText(snap.error!), style: const TextStyle(fontSize: 12));
        return _Tools(info: snap.data!);
      },
    );
  }
}

class _Tools extends StatelessWidget {
  const _Tools({required this.info});
  final McpServerInfo info;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      if (info.security.isNotEmpty)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Text('Declared security: ${info.security.join(', ')}. Configure credentials as ${r'${secret:NAME}'} headers when importing.',
              style: const TextStyle(fontSize: 11)),
        ),
      for (final t in info.tools)
        ListTile(
          dense: true,
          contentPadding: EdgeInsets.zero,
          title: Row(children: [
            Text(t.name, style: const TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.w600)),
            const SizedBox(width: 8),
            StatusPill(label: t.readOnly ? 'read-only' : (t.destructive ? 'destructive' : 'writes'), color: t.readOnly ? Colors.green : (t.destructive ? Colors.red : Colors.orange), dense: true),
          ]),
          subtitle: Text('${t.args.isEmpty ? 'no arguments' : t.args.join(', ')}\n${t.description.split('\n').first}', style: const TextStyle(fontSize: 11)),
          isThreeLine: true,
        ),
      if (info.warnings.isNotEmpty) ...[
        const SizedBox(height: 6),
        const Text('Warnings', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 12)),
        for (final w in info.warnings.take(10)) Text('• $w', style: const TextStyle(fontSize: 11)),
      ],
    ]);
  }
}

class _ImportDialog extends ConsumerStatefulWidget {
  const _ImportDialog();

  @override
  ConsumerState<_ImportDialog> createState() => _ImportDialogState();
}

class _ImportDialogState extends ConsumerState<_ImportDialog> {
  final _id = TextEditingController();
  final _url = TextEditingController();
  final _spec = TextEditingController();
  final _baseUrl = TextEditingController();
  final _headers = TextEditingController();
  final _tags = TextEditingController();
  final _paths = TextEditingController();
  final _methods = <String>{'GET'};
  bool _useUrl = true;
  bool _busy = false;
  String? _error;
  McpServerInfo? _preview;

  @override
  void dispose() {
    for (final c in [_id, _url, _spec, _baseUrl, _headers, _tags, _paths]) {
      c.dispose();
    }
    super.dispose();
  }

  List<String> _csv(TextEditingController c) => [for (final x in c.text.split(',')) if (x.trim().isNotEmpty) x.trim()];

  Map<String, dynamic>? _request() {
    final headers = tryParseJsonObject(_headers.text);
    if (headers == null) {
      setState(() => _error = 'Headers must be a JSON object, e.g. {"Authorization": "Bearer \${secret:PETS_TOKEN}"}');
      return null;
    }
    if (_useUrl ? _url.text.trim().isEmpty : _spec.text.trim().isEmpty) {
      setState(() => _error = _useUrl ? 'Enter the spec URL' : 'Paste the spec');
      return null;
    }
    return {
      if (_useUrl) 'specUrl': _url.text.trim() else 'spec': _spec.text,
      if (_baseUrl.text.trim().isNotEmpty) 'baseUrl': _baseUrl.text.trim(),
      if (headers.isNotEmpty) 'headers': headers,
      'methods': _methods.toList(),
      if (_csv(_tags).isNotEmpty) 'includeTags': _csv(_tags),
      if (_csv(_paths).isNotEmpty) 'includePaths': _csv(_paths),
    };
  }

  Future<void> _run(bool create) async {
    final req = _request();
    if (req == null) return;
    if (create && _id.text.trim().isEmpty) return setState(() => _error = 'Give the server an id');
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final repo = ref.read(repositoryProvider);
      if (create) {
        await repo.createMcpServer(_id.text.trim(), req);
        if (mounted) Navigator.pop(context, true);
      } else {
        final p = await repo.previewMcpServer(req);
        if (mounted) setState(() => _preview = p);
      }
    } catch (e) {
      if (mounted) setState(() => _error = errText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Import OpenAPI / Swagger'),
      content: SizedBox(
        width: 640,
        child: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            SegmentedButton<bool>(
              segments: const [ButtonSegment(value: true, label: Text('From URL')), ButtonSegment(value: false, label: Text('Paste spec'))],
              selected: {_useUrl},
              onSelectionChanged: (v) => setState(() => _useUrl = v.first),
            ),
            const SizedBox(height: 10),
            if (_useUrl)
              TextField(controller: _url, decoration: const InputDecoration(labelText: 'Spec URL (OpenAPI 3.x or Swagger 2.0, JSON or YAML)'))
            else
              TextField(controller: _spec, maxLines: 8, style: const TextStyle(fontFamily: 'monospace', fontSize: 12), decoration: const InputDecoration(labelText: 'Spec (JSON or YAML)', alignLabelWithHint: true)),
            const SizedBox(height: 10),
            TextField(controller: _baseUrl, decoration: const InputDecoration(labelText: 'Base URL (only if the spec has none or a relative one)')),
            const SizedBox(height: 10),
            TextField(controller: _headers, maxLines: 2, style: const TextStyle(fontFamily: 'monospace', fontSize: 12), decoration: const InputDecoration(labelText: 'Credential headers (JSON; values must be \${secret:NAME})')),
            const SizedBox(height: 10),
            Wrap(spacing: 8, children: [
              for (final m in const ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'])
                FilterChip(
                  label: Text(m),
                  selected: _methods.contains(m),
                  onSelected: (v) => setState(() => v ? _methods.add(m) : _methods.remove(m)),
                ),
            ]),
            const Padding(padding: EdgeInsets.only(top: 4), child: Text('Only GET is on by default. Write methods let an agent change data in the target system.', style: TextStyle(fontSize: 11))),
            const SizedBox(height: 10),
            Row(children: [
              Expanded(child: TextField(controller: _tags, decoration: const InputDecoration(labelText: 'Only tags (comma separated)'))),
              const SizedBox(width: 10),
              Expanded(child: TextField(controller: _paths, decoration: const InputDecoration(labelText: 'Only paths (regex, comma separated)'))),
            ]),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error, fontSize: 12)),
            ],
            if (_preview != null) ...[
              const Divider(height: 24),
              Text('${_preview!.title} → ${_preview!.tools.length} tools · ${_preview!.baseUrl}', style: const TextStyle(fontWeight: FontWeight.w600)),
              _Tools(info: _preview!),
              const SizedBox(height: 8),
              TextField(controller: _id, decoration: const InputDecoration(labelText: 'Server id (letters, digits, _ -)')),
            ],
          ]),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.pop(context, false), child: const Text('Cancel')),
        OutlinedButton(onPressed: _busy ? null : () => _run(false), child: const Text('Preview')),
        ElevatedButton(onPressed: _busy || _preview == null ? null : () => _run(true), child: const Text('Create')),
      ],
    );
  }
}
