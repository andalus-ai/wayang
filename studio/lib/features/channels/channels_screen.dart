import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';

final triggersProvider = FutureProvider.autoDispose<List<TriggerInfo>>((ref) => ref.watch(repositoryProvider).listTriggers());

final oauthConnectionsProvider =
    FutureProvider.autoDispose<List<OAuthConnectionInfo>>((ref) => ref.watch(repositoryProvider).listOAuthConnections());

/// Starting points for the config editor. Credentials are always `${secret:NAME}` references: the server refuses
/// literal tokens, and the editor never shows a stored value because there is none to show.
const _templates = <String, ({String kind, String label, Map<String, dynamic> config})>{
  'telegram': (
    kind: 'POLL',
    label: 'Telegram bot (polling)',
    config: {
      'source': 'telegram',
      'botToken': r'${secret:TELEGRAM_TOKEN}',
      'allowedChatIds': [123456789],
      'everySeconds': 10,
    },
  ),
  'gmail': (
    kind: 'POLL',
    label: 'Gmail inbox (polling)',
    config: {
      'source': 'gmail',
      'accessToken': r'${secret:oauth.CONNECTION_ID}',
      'allowedSenders': ['you@example.com'],
      'everySeconds': 60,
    },
  ),
  'imap': (
    kind: 'POLL',
    label: 'Email inbox (IMAP, any provider)',
    config: {
      'source': 'imap',
      'host': 'imap.example.com',
      'username': 'you@example.com',
      'password': r'${secret:IMAP_PASSWORD}',
      'authservId': 'imap.example.com',
      'allowedSenders': ['you@example.org'],
      'everySeconds': 60,
    },
  ),
  'slack': (
    kind: 'WEBHOOK',
    label: 'Slack (events webhook)',
    config: {
      'auth': 'slack',
      'signingSecret': r'${secret:SLACK_SIGNING}',
      'allowedChannels': ['D0123ABCD'],
    },
  ),
  'whatsapp': (
    kind: 'WEBHOOK',
    label: 'WhatsApp Cloud API (webhook)',
    config: {
      'auth': 'whatsapp',
      'appSecret': r'${secret:WA_APP}',
      'verifyToken': r'${secret:WA_VERIFY}',
      'allowedNumbers': ['628123456789'],
    },
  ),
  'github': (
    kind: 'WEBHOOK',
    label: 'GitHub (issues, comments, PRs, pushes)',
    config: {
      'auth': 'github',
      'secret': r'${secret:GH_HOOK}',
      'allowedRepos': ['owner/*'],
      'events': ['issues', 'issue_comment'],
    },
  ),
  'rss': (
    kind: 'POLL',
    label: 'RSS / Atom feed (polling)',
    config: {
      'source': 'rss',
      'url': 'https://example.org/feed.xml',
      'limit': 20,
      'everySeconds': 900,
    },
  ),
  'webhook': (
    kind: 'WEBHOOK',
    label: 'Generic webhook (shared secret)',
    config: <String, dynamic>{},
  ),
};

const _gmailScopes = {
  'Read mail': 'https://www.googleapis.com/auth/gmail.readonly',
  'Send mail': 'https://www.googleapis.com/auth/gmail.send',
  'Calendar events': 'https://www.googleapis.com/auth/calendar.events',
  'Calendar (read only)': 'https://www.googleapis.com/auth/calendar.readonly',
  'Sheets': 'https://www.googleapis.com/auth/spreadsheets',
};

/// Where messages come in (triggers) and which accounts are connected.
class ChannelsScreen extends ConsumerWidget {
  const ChannelsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return DefaultTabController(
      length: 2,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const ScreenHeader(
              title: 'Channels',
              subtitle: 'Start processes from chats and mail. Every channel needs an allow-list of who may reach the assistant.',
              icon: Icons.forum_rounded,
            ),
            const SizedBox(height: 8),
            const TabBar(tabs: [Tab(text: 'Triggers'), Tab(text: 'Connected accounts')]),
            const SizedBox(height: 12),
            const Expanded(child: TabBarView(children: [_TriggersTab(), _AccountsTab()])),
          ],
        ),
      ),
    );
  }
}

// ------------------------------------------------------------------ triggers

class _TriggersTab extends ConsumerWidget {
  const _TriggersTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final triggers = ref.watch(triggersProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          const Spacer(),
          IconButton(tooltip: 'Refresh', onPressed: () => ref.invalidate(triggersProvider), icon: const Icon(Icons.refresh_rounded)),
          const SizedBox(width: 4),
          ElevatedButton.icon(
            onPressed: () => _create(context, ref),
            icon: const Icon(Icons.add_rounded, size: 18),
            label: const Text('New trigger'),
          ),
        ]),
        const SizedBox(height: 8),
        Expanded(
          child: triggers.when(
            loading: () => const LoadingView(),
            error: (e, _) => ErrorView(message: errText(e), onRetry: () => ref.invalidate(triggersProvider)),
            data: (list) => list.isEmpty
                ? const EmptyState(icon: Icons.bolt_outlined, title: 'No triggers')
                : ListView(children: [for (final t in list) _TriggerCard(t: t)]),
          ),
        ),
      ],
    );
  }

  Future<void> _create(BuildContext context, WidgetRef ref) async {
    final processes = await ref.read(definitionsProvider(DefinitionKind.process).future).catchError((_) => <DefinitionSummary>[]);
    if (!context.mounted) return;
    final created = await showDialog<CreatedTrigger>(context: context, builder: (_) => _CreateTriggerDialog(processes: processes));
    if (created == null) return;
    ref.invalidate(triggersProvider);
    if (created.webhookSecret != null && context.mounted) {
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Webhook secret'),
          content: SizedBox(
            width: 460,
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('Shown once. Send it in the X-Wayang-Secret header; only a hash is stored.', style: TextStyle(fontSize: 12)),
              const SizedBox(height: 10),
              SelectableText(created.webhookSecret!, style: const TextStyle(fontFamily: 'monospace')),
              const SizedBox(height: 10),
              if (created.webhookPath != null) SelectableText('POST ${created.webhookPath}', style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
            ]),
          ),
          actions: [
            TextButton(
              onPressed: () => Clipboard.setData(ClipboardData(text: created.webhookSecret!)),
              child: const Text('Copy'),
            ),
            ElevatedButton(onPressed: () => Navigator.pop(ctx), child: const Text('Done')),
          ],
        ),
      );
    }
  }
}

class _TriggerCard extends ConsumerWidget {
  const _TriggerCard({required this.t});
  final TriggerInfo t;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final failing = t.lastError != null && t.lastError!.isNotEmpty;
    return Card(
      child: ListTile(
        leading: Icon(_icon(t.channel)),
        title: Row(children: [
          Text(t.id, style: const TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.w600)),
          const SizedBox(width: 8),
          StatusPill(label: t.channel, color: Colors.blueGrey, dense: true),
          const SizedBox(width: 6),
          if (!t.enabled) const StatusPill(label: 'disabled', color: Colors.grey, dense: true),
          if (failing) StatusPill(label: 'failing x${t.consecutiveFailures}', color: Colors.red, dense: true),
        ]),
        subtitle: Text(
          '${t.kind} → ${t.processId}'
          '${t.lastPolledAt == null ? '' : ' · ${t.isSchedule ? 'last ran' : 'polled'} ${formatDateShort(t.lastPolledAt!)}'}'
          '${failing ? '\n${t.lastError}' : ''}',
          style: const TextStyle(fontSize: 11),
        ),
        isThreeLine: failing,
        trailing: Row(mainAxisSize: MainAxisSize.min, children: [
          Switch(
            value: t.enabled,
            onChanged: (v) async {
              try {
                await ref.read(repositoryProvider).setTriggerEnabled(t.id, v);
                ref.invalidate(triggersProvider);
              } catch (e) {
                if (context.mounted) showToast(context, errText(e), isError: true);
              }
            },
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline_rounded, size: 18),
            onPressed: () async {
              final ok = await showConfirmDialog(context,
                  title: 'Delete ${t.id}?',
                  message: 'Messages arriving on this channel will no longer start runs.',
                  confirmLabel: 'Delete',
                  destructive: true);
              if (!ok) return;
              try {
                await ref.read(repositoryProvider).deleteTrigger(t.id);
                ref.invalidate(triggersProvider);
              } catch (e) {
                if (context.mounted) showToast(context, errText(e), isError: true);
              }
            },
          ),
        ]),
      ),
    );
  }

  static IconData _icon(String channel) => switch (channel) {
        'telegram' => Icons.send_rounded,
        'gmail' || 'imap' => Icons.mail_outline_rounded,
        'slack' => Icons.tag_rounded,
        'whatsapp' => Icons.chat_rounded,
        'github' => Icons.code_rounded,
        'rss' => Icons.rss_feed_rounded,
        'schedule' => Icons.schedule_rounded,
        _ => Icons.webhook_rounded,
      };
}

class _CreateTriggerDialog extends ConsumerStatefulWidget {
  const _CreateTriggerDialog({required this.processes});
  final List<DefinitionSummary> processes;

  @override
  ConsumerState<_CreateTriggerDialog> createState() => _CreateTriggerDialogState();
}

class _CreateTriggerDialogState extends ConsumerState<_CreateTriggerDialog> {
  final _id = TextEditingController();
  final _config = TextEditingController();
  String _template = 'telegram';
  String? _process;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _applyTemplate('telegram');
    if (widget.processes.isNotEmpty) _process = widget.processes.first.id;
  }

  void _applyTemplate(String key) {
    _template = key;
    _config.text = const JsonEncoder.withIndent('  ').convert(_templates[key]!.config);
  }

  @override
  void dispose() {
    _id.dispose();
    _config.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final cfg = tryParseJsonObject(_config.text);
    if (_id.text.trim().isEmpty) return setState(() => _error = 'Give the trigger an id');
    if (_process == null) return setState(() => _error = 'Publish a process first, then pick it here');
    if (cfg == null) return setState(() => _error = 'Config must be a JSON object');
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final created = await ref.read(repositoryProvider).createTrigger(
            id: _id.text.trim(),
            kind: _templates[_template]!.kind,
            processId: _process!,
            config: cfg.isEmpty ? <String, dynamic>{} : cfg,
          );
      if (mounted) Navigator.pop(context, created);
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = errText(e);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('New trigger'),
      content: SizedBox(
        width: 520,
        child: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            DropdownButtonFormField<String>(
              value: _template,
              decoration: const InputDecoration(labelText: 'Channel'),
              items: [for (final e in _templates.entries) DropdownMenuItem(value: e.key, child: Text(e.value.label))],
              onChanged: (v) => setState(() => _applyTemplate(v ?? 'telegram')),
            ),
            const SizedBox(height: 10),
            TextField(controller: _id, decoration: const InputDecoration(labelText: 'Trigger id (e.g. telegram-me)')),
            const SizedBox(height: 10),
            DropdownButtonFormField<String>(
              value: _process,
              decoration: const InputDecoration(labelText: 'Process to start'),
              items: [for (final p in widget.processes) DropdownMenuItem(value: p.id, child: Text('${p.name} (${p.id})'))],
              onChanged: (v) => setState(() => _process = v),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _config,
              maxLines: 10,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
              decoration: const InputDecoration(labelText: 'Config (JSON)', alignLabelWithHint: true),
            ),
            const SizedBox(height: 6),
            const Text(
              r'Credentials must be ${secret:NAME} references (set them under Secrets). The allow-list is required.',
              style: TextStyle(fontSize: 11),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error, fontSize: 12)),
            ],
          ]),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.pop(context), child: const Text('Cancel')),
        ElevatedButton(onPressed: _busy ? null : _submit, child: const Text('Create')),
      ],
    );
  }
}

// ---------------------------------------------------------- connected accounts

class _AccountsTab extends ConsumerWidget {
  const _AccountsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final conns = ref.watch(oauthConnectionsProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          const Spacer(),
          IconButton(tooltip: 'Refresh', onPressed: () => ref.invalidate(oauthConnectionsProvider), icon: const Icon(Icons.refresh_rounded)),
          const SizedBox(width: 4),
          ElevatedButton.icon(
            onPressed: () => _create(context, ref),
            icon: const Icon(Icons.add_rounded, size: 18),
            label: const Text('Connect Gmail'),
          ),
        ]),
        const SizedBox(height: 8),
        Expanded(
          child: conns.when(
            loading: () => const LoadingView(),
            error: (e, _) => ErrorView(message: errText(e), onRetry: () => ref.invalidate(oauthConnectionsProvider)),
            data: (list) => list.isEmpty
                ? const EmptyState(
                    icon: Icons.link_off_rounded,
                    title: 'No connected accounts',
                    message: 'Needs wayang.secrets.key and wayang.oauth.redirect-uri on the server.',
                  )
                : ListView(children: [for (final c in list) _ConnectionCard(c: c)]),
          ),
        ),
      ],
    );
  }

  Future<void> _create(BuildContext context, WidgetRef ref) async {
    final id = TextEditingController(text: 'work');
    final clientId = TextEditingController();
    final secretName = TextEditingController(text: 'GOOGLE_CLIENT_SECRET');
    final scopes = <String>{_gmailScopes['Read mail']!};
    String? error;
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('Connect Gmail'),
          content: SizedBox(
            width: 460,
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              TextField(controller: id, decoration: const InputDecoration(labelText: 'Connection id (letters, digits, _ -)')),
              const SizedBox(height: 10),
              TextField(controller: clientId, decoration: const InputDecoration(labelText: 'Google OAuth client id')),
              const SizedBox(height: 10),
              TextField(controller: secretName, decoration: const InputDecoration(labelText: 'Name of the secret holding the client secret')),
              const SizedBox(height: 8),
              for (final e in _gmailScopes.entries)
                CheckboxListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  title: Text(e.key),
                  value: scopes.contains(e.value),
                  onChanged: (v) => setState(() => v == true ? scopes.add(e.value) : scopes.remove(e.value)),
                ),
              if (error != null) Text(error!, style: TextStyle(color: Theme.of(ctx).colorScheme.error, fontSize: 12)),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
            ElevatedButton(
              onPressed: () async {
                try {
                  await ref.read(repositoryProvider).createOAuthConnection(
                        id: id.text.trim(),
                        clientId: clientId.text.trim(),
                        clientSecretName: secretName.text.trim(),
                        scopes: scopes.toList(),
                      );
                  ref.invalidate(oauthConnectionsProvider);
                  if (ctx.mounted) Navigator.pop(ctx);
                } catch (e) {
                  setState(() => error = errText(e));
                }
              },
              child: const Text('Create'),
            ),
          ],
        ),
      ),
    );
  }
}

class _ConnectionCard extends ConsumerWidget {
  const _ConnectionCard({required this.c});
  final OAuthConnectionInfo c;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final color = switch (c.status) {
      'CONNECTED' => Colors.green,
      'REVOKED' => Colors.red,
      _ => Colors.orange,
    };
    return Card(
      child: ListTile(
        leading: const Icon(Icons.account_circle_outlined),
        title: Row(children: [
          Text(c.id, style: const TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.w600)),
          const SizedBox(width: 8),
          StatusPill(label: c.status.toLowerCase(), color: color, dense: true),
        ]),
        subtitle: SelectableText(
          'Use as ${c.secretRef}${c.connectedAt == null ? '' : ' · connected ${formatDateShort(c.connectedAt!)}'}',
          style: const TextStyle(fontSize: 11, fontFamily: 'monospace'),
        ),
        trailing: Row(mainAxisSize: MainAxisSize.min, children: [
          OutlinedButton(
            onPressed: () => _authorize(context, ref),
            child: Text(c.status == 'CONNECTED' ? 'Reconnect' : 'Authorise'),
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline_rounded, size: 18),
            onPressed: () async {
              final ok = await showConfirmDialog(context,
                  title: 'Remove ${c.id}?',
                  message: 'Its refresh token is deleted. Triggers using it will fail until it is connected again.',
                  confirmLabel: 'Remove',
                  destructive: true);
              if (!ok) return;
              try {
                await ref.read(repositoryProvider).deleteOAuthConnection(c.id);
                ref.invalidate(oauthConnectionsProvider);
              } catch (e) {
                if (context.mounted) showToast(context, errText(e), isError: true);
              }
            },
          ),
        ]),
      ),
    );
  }

  /// The app has no URL launcher dependency, so the consent URL is shown to copy into a browser.
  Future<void> _authorize(BuildContext context, WidgetRef ref) async {
    try {
      final url = await ref.read(repositoryProvider).authorizeOAuthConnection(c.id);
      if (!context.mounted) return;
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Grant access'),
          content: SizedBox(
            width: 520,
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('Open this link in a browser and approve. It works once and expires in 10 minutes. Then press Refresh.',
                  style: TextStyle(fontSize: 12)),
              const SizedBox(height: 10),
              SelectableText(url, style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Clipboard.setData(ClipboardData(text: url)), child: const Text('Copy link')),
            ElevatedButton(
              onPressed: () {
                ref.invalidate(oauthConnectionsProvider);
                Navigator.pop(ctx);
              },
              child: const Text('Done'),
            ),
          ],
        ),
      );
    } catch (e) {
      if (context.mounted) showToast(context, errText(e), isError: true);
    }
  }
}
