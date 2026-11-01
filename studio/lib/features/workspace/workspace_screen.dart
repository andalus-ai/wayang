import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/workspace_model.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';

/// Move a project between environments: export it as a bundle (definitions, triggers, settings; never a secret value), and
/// import one with a dry run first. Importing never starts anything: triggers arrive disabled.
class WorkspaceScreen extends ConsumerStatefulWidget {
  const WorkspaceScreen({super.key});

  @override
  ConsumerState<WorkspaceScreen> createState() => _WorkspaceScreenState();
}

class _WorkspaceScreenState extends ConsumerState<WorkspaceScreen> {
  String? _exportProject;
  bool _withTenantConfig = false;
  final _bundleText = TextEditingController();
  ImportOptions _options = const ImportOptions();
  ImportOptions? _checkedWith;
  String? _checkedText;
  ImportReport? _report;
  String? _error;
  bool _busy = false;
  ReleaseForm _release = const ReleaseForm();
  ReleasePlan? _plan;
  String? _planError;

  @override
  void dispose() {
    _bundleText.dispose();
    super.dispose();
  }

  Future<void> _export() async {
    final id = _exportProject;
    if (id == null) return;
    setState(() => _busy = true);
    try {
      final b = await ref.read(repositoryProvider).exportProject(id, includeTenantConfig: _withTenantConfig);
      final text = const JsonEncoder.withIndent('  ').convert(b);
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Bundle'),
          content: SizedBox(width: 640, height: 420, child: SingleChildScrollView(child: SelectableText(text, style: const TextStyle(fontFamily: 'monospace', fontSize: 11)))),
          actions: [
            TextButton(
              onPressed: () async {
                await Clipboard.setData(ClipboardData(text: text));
                if (ctx.mounted) showToast(ctx, 'Copied. It holds no secret values.');
              },
              child: const Text('Copy'),
            ),
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Close')),
          ],
        ),
      );
    } catch (e) {
      if (mounted) showToast(context, errText(e), isError: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _planRelease() async {
    final id = _exportProject;
    if (id == null) return;
    final problem = _release.problem;
    if (problem != null) {
      setState(() {
        _plan = null;
        _planError = problem;
      });
      return;
    }
    setState(() {
      _busy = true;
      _planError = null;
    });
    try {
      final plan = await ref.read(repositoryProvider).planRelease(id, _release);
      if (mounted) setState(() => _plan = plan);
    } catch (e) {
      if (mounted) {
        setState(() {
          _plan = null;
          _planError = errText(e);
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Map<String, dynamic>? _parse() {
    try {
      final v = jsonDecode(_bundleText.text);
      if (v is Map<String, dynamic> && looksLikeBundle(v)) return v;
      setState(() => _error = 'This is not a Wayang workspace bundle.');
    } catch (_) {
      setState(() => _error = 'This is not valid JSON.');
    }
    return null;
  }

  Future<void> _run({required bool dry}) async {
    setState(() => _error = null);
    final bundle = _parse();
    if (bundle == null) return;
    setState(() => _busy = true);
    try {
      final opts = _options.copyWith(dryRun: dry);
      final r = await ref.read(repositoryProvider).importBundle(bundle, opts);
      if (!mounted) return;
      setState(() {
        _report = r;
        _checkedWith = dry ? opts : null;
        _checkedText = dry ? _bundleText.text : null;
      });
      if (!dry) {
        ref.invalidate(projectsProvider);
        if (r.ok) showToast(context, 'Imported. Triggers are disabled until you enable them.');
      }
    } catch (e) {
      if (mounted) setState(() => _error = errText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Apply is offered only for exactly what was checked: same text, same options.
  bool get _canApply => _report?.canApply == true && _checkedWith != null && _checkedWith!.sameAs(_options) && _checkedText == _bundleText.text;

  @override
  Widget build(BuildContext context) {
    final projects = ref.watch(projectsProvider);
    final list = projects.valueOrNull ?? const <Project>[];
    return Padding(
      padding: const EdgeInsets.all(20),
      child: ListView(
        children: [
          const ScreenHeader(
            title: 'Workspace',
            subtitle: 'Export a project as a bundle and import one, with a dry run first. Secret values never travel in a bundle.',
            icon: Icons.inventory_2_outlined,
          ),
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('Export', style: TextStyle(fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                Row(children: [
                  DropdownButton<String>(
                    hint: const Text('Project'),
                    value: list.any((p) => p.id == _exportProject) ? _exportProject : null,
                    items: [for (final p in list) DropdownMenuItem(value: p.id, child: Text(p.name))],
                    onChanged: (v) => setState(() => _exportProject = v),
                  ),
                  const SizedBox(width: 12),
                  Checkbox(value: _withTenantConfig, onChanged: (v) => setState(() => _withTenantConfig = v ?? false)),
                  const Text('include tenant-wide settings'),
                  const Spacer(),
                  ElevatedButton(onPressed: _busy || _exportProject == null ? null : _export, child: const Text('Export')),
                ]),
              ]),
            ),
          ),
          const SizedBox(height: 12),
          _releaseCard(),
          const SizedBox(height: 12),
          _templateCard(),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('Import', style: TextStyle(fontWeight: FontWeight.w700)),
                const SizedBox(height: 8),
                TextField(
                  controller: _bundleText,
                  minLines: 4,
                  maxLines: 10,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
                  decoration: InputDecoration(hintText: 'Paste a bundle here', errorText: _error),
                  onChanged: (_) => setState(() {}),
                ),
                const SizedBox(height: 10),
                Wrap(spacing: 14, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [
                  DropdownButton<String?>(
                    value: list.any((p) => p.id == _options.targetProject) ? _options.targetProject : null,
                    items: [
                      const DropdownMenuItem<String?>(value: null, child: Text('Into a new project')),
                      for (final p in list) DropdownMenuItem<String?>(value: p.id, child: Text('Into ${p.name}')),
                    ],
                    onChanged: (v) => setState(() => _options = _options.copyWith(targetProject: v)),
                  ),
                  DropdownButton<String>(
                    value: _options.onConflict,
                    items: [for (final m in ImportOptions.conflictModes) DropdownMenuItem(value: m, child: Text('If it exists: $m'))],
                    onChanged: (v) => setState(() => _options = _options.copyWith(onConflict: v)),
                  ),
                  _check('publish', _options.publish, (v) => _options = _options.copyWith(publish: v)),
                  _check('triggers', _options.triggers, (v) => _options = _options.copyWith(triggers: v)),
                  _check('project settings', _options.projectConfig, (v) => _options = _options.copyWith(projectConfig: v)),
                  _check('tenant settings', _options.tenantConfig, (v) => _options = _options.copyWith(tenantConfig: v)),
                  _check('accept unsigned', _options.allowUnsigned, (v) => _options = _options.copyWith(allowUnsigned: v)),
                ]),
                const SizedBox(height: 10),
                Row(children: [
                  OutlinedButton(onPressed: _busy || _bundleText.text.trim().isEmpty ? null : () => _run(dry: true), child: const Text('Check (dry run)')),
                  const SizedBox(width: 8),
                  ElevatedButton(onPressed: _busy || !_canApply ? null : () => _run(dry: false), child: const Text('Apply')),
                ]),
                if (_report != null) ...[const SizedBox(height: 12), _ReportView(report: _report!)],
              ]),
            ),
          ),
        ],
      ),
    );
  }

  final _tplChat = TextEditingController();
  final _tplModel = TextEditingController(text: 'openai/gpt-4o-mini');
  final _tplPersona = TextEditingController();
  final _tplName = TextEditingController(text: 'Personal assistant');
  final _tplLookup = TextEditingController();
  final _tplLookupDesc = TextEditingController();
  final _tplLookupSecret = TextEditingController();
  final _tplTopic = TextEditingController();
  final _tplCron = TextEditingController(text: '0 8 * * 1-5');
  final _tplZone = TextEditingController(text: 'UTC');
  bool _tplSlack = false;
  bool _tplBrief = false;
  bool _tplCalendar = false;
  final _tplCalConn = TextEditingController(text: 'google');
  ImportReport? _tplReport;

  Future<void> _createFromTemplate({required bool dry}) async {
    setState(() => _busy = true);
    try {
      final r = await ref.read(repositoryProvider).instantiateTemplate(_tplBrief ? (_tplSlack ? 'daily-brief-slack' : 'daily-brief-telegram') : (_tplSlack ? 'assistant-slack' : 'assistant-telegram'), {
        _tplSlack ? 'channelId' : 'chatId': _tplChat.text.trim(),
        'model': _tplModel.text.trim(),
        'persona': _tplPersona.text.trim(),
        'lookupUrl': _tplLookup.text.trim(),
        'lookupDescription': _tplLookupDesc.text.trim(),
        'lookupSecret': _tplLookupSecret.text.trim(),
        'name': _tplName.text.trim(),
        if (!_tplBrief && _tplCalendar) ...{'calendar': 'true', 'calendarConnection': _tplCalConn.text.trim()},
        if (_tplBrief) ...{'topic': _tplTopic.text.trim(), 'cron': _tplCron.text.trim(), 'timezone': _tplZone.text.trim()},
      }, dryRun: dry);
      if (!mounted) return;
      setState(() => _tplReport = r);
      if (!dry) {
        ref.invalidate(projectsProvider);
        if (r.ok && _tplBrief) {
          showToast(context, 'Created. The schedule is off: set ${_tplSlack ? 'SLACK_BOT_TOKEN' : 'TELEGRAM_TOKEN'} under Secrets, then enable it.');
        } else if (r.ok) showToast(context, _tplSlack
            ? 'Created. The Slack trigger is off: set SLACK_BOT_TOKEN and SLACK_SIGNING_SECRET under Secrets, then enable it.'
            : 'Created. The Telegram trigger is off: set TELEGRAM_TOKEN under Secrets, then enable it.');
      }
    } catch (e) {
      if (mounted) setState(() => _tplReport = null);
      if (mounted) showToast(context, errText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _templateCard() {
    final idKey = _tplSlack ? 'channelId' : 'chatId';
    final fields = {idKey: _tplChat, 'model': _tplModel, 'persona': _tplPersona, 'name': _tplName, 'lookupUrl': _tplLookup, 'lookupDescription': _tplLookupDesc, 'lookupSecret': _tplLookupSecret, if (_tplBrief) ...{'cron': _tplCron, 'timezone': _tplZone}, if (!_tplBrief && _tplCalendar) 'calendarConnection': _tplCalConn};
    final ok = fields.entries.every((e) => assistantFieldProblem(e.key, e.value.text) == null) && (!_tplBrief || assistantFieldProblem('topic', _tplTopic.text) == null);
    Widget field(String key, String label, TextEditingController c, {int lines = 1}) => SizedBox(
          width: 260,
          child: TextField(
            controller: c,
            maxLines: lines,
            decoration: InputDecoration(labelText: label, errorText: c.text.isEmpty && key != 'name' && key != 'topic' ? null : assistantFieldProblem(key, c.text), isDense: true),
            onChanged: (_) => setState(() {}),
          ),
        );
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Start from a template: ${_tplBrief ? 'daily brief' : 'personal assistant'} on ${_tplSlack ? 'Slack' : 'Telegram'}', style: const TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 6),
          SegmentedButton<bool>(
            segments: const [ButtonSegment(value: false, label: Text('Telegram')), ButtonSegment(value: true, label: Text('Slack'))],
            selected: {_tplSlack},
            onSelectionChanged: (v) => setState(() {
              _tplSlack = v.first;
              _tplChat.clear();
              _tplReport = null;
            }),
          ),
          SwitchListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: const Text('Daily brief on a schedule instead of replies'),
            value: _tplBrief,
            onChanged: (v) => setState(() {
              _tplBrief = v;
              _tplReport = null;
            }),
          ),
          if (!_tplBrief)
            SwitchListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              title: const Text('Google Calendar: read events, propose new ones (created only after you answer yes in the chat)'),
              value: _tplCalendar,
              onChanged: (v) => setState(() {
                _tplCalendar = v;
                _tplReport = null;
              }),
            ),
          const SizedBox(height: 4),
          Text(_tplBrief
              ? 'An agent writes a short brief on a cron schedule and sends it to you. A run missed while the release was down is skipped, not sent late. The schedule is created switched off.'
              : 'An agent drafts replies; a decision table keeps anything about money, passwords or deleting, or any reply with a link, for your approval. '
              'The trigger is created switched off.'),
          const SizedBox(height: 10),
          Wrap(spacing: 12, runSpacing: 10, children: [
            field(idKey, _tplSlack ? 'Slack channel or DM id' : 'Your Telegram chat id', _tplChat),
            field('model', 'Model (provider/model)', _tplModel),
            field('name', 'Project name', _tplName),
            field('persona', 'Persona (optional)', _tplPersona),
            field('lookupUrl', 'Read-only lookup address (optional, https)', _tplLookup),
            field('lookupDescription', 'What that address answers (optional)', _tplLookupDesc),
            field('lookupSecret', 'Secret name sent as a bearer token (optional)', _tplLookupSecret),
            if (!_tplBrief && _tplCalendar) field('calendarConnection', 'Google OAuth connection id', _tplCalConn),
            if (_tplBrief) ...[
              field('topic', 'What the brief covers', _tplTopic),
              field('cron', 'Cron (minute hour day month weekday)', _tplCron),
              field('timezone', 'Time zone, e.g. Asia/Jakarta', _tplZone),
            ],
          ]),
          const SizedBox(height: 10),
          Row(children: [
            OutlinedButton(onPressed: _busy || !ok || _tplChat.text.trim().isEmpty ? null : () => _createFromTemplate(dry: true), child: const Text('Check')),
            const SizedBox(width: 8),
            ElevatedButton(onPressed: _busy || !ok || _tplChat.text.trim().isEmpty ? null : () => _createFromTemplate(dry: false), child: const Text('Create project')),
          ]),
          if (_tplReport != null) ...[const SizedBox(height: 12), _ReportView(report: _tplReport!)],
        ]),
      ),
    );
  }

  Widget _releaseCard() {
    final plan = _plan;
    final base = ref.watch(apiConfigProvider).baseUrl;
    Widget field(String label, String value, void Function(String) set, {double width = 170}) => SizedBox(
          width: width,
          child: TextFormField(
            initialValue: value,
            decoration: InputDecoration(labelText: label, isDense: true),
            onChanged: (v) => setState(() => set(v.trim())),
          ),
        );
    Widget lines(String title, List<String> xs) => xs.isEmpty
        ? const SizedBox.shrink()
        : Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
              for (final x in xs) Text('• $x', style: const TextStyle(fontSize: 12)),
            ]),
          );
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('Release', style: TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 4),
          const Text(
            'Turn the published versions of the project selected above into a project that runs without this server: a command-line program or an HTTP service '
            'that depends only on what the workflows use. Drafts, triggers and secret values are never included.',
            style: TextStyle(fontSize: 12),
          ),
          const SizedBox(height: 10),
          Wrap(spacing: 12, runSpacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
            DropdownButton<String>(
              value: _release.target,
              items: [for (final t in ReleaseForm.targets) DropdownMenuItem(value: t, child: Text(switch (t) { 'SERVICE' => 'HTTP service', 'GAMELAN' => 'Durable service (Gamelan)', _ => 'Standalone program' }))],
              onChanged: (v) => setState(() => _release = _release.copyWith(target: v)),
            ),
            field('groupId', _release.groupId, (v) => _release = _release.copyWith(groupId: v)),
            field('artifactId (optional)', _release.artifactId, (v) => _release = _release.copyWith(artifactId: v)),
            field('version', _release.version, (v) => _release = _release.copyWith(version: v), width: 110),
            field('package (optional)', _release.packageName, (v) => _release = _release.copyWith(packageName: v)),
            field('port', '${_release.port}', (v) => _release = _release.copyWith(port: int.tryParse(v) ?? 0), width: 80),
          ]),
          const SizedBox(height: 10),
          Row(children: [
            OutlinedButton(onPressed: _busy || _exportProject == null ? null : _planRelease, child: const Text('Plan release')),
            const SizedBox(width: 10),
            if (_exportProject == null) const Text('Pick a project in Export above.', style: TextStyle(fontSize: 12)),
          ]),
          if (_planError != null)
            Padding(padding: const EdgeInsets.only(top: 8), child: Text(_planError!, style: TextStyle(color: Theme.of(context).colorScheme.error, fontSize: 12))),
          if (plan != null) ...[
            const SizedBox(height: 10),
            Text(plan.summary, style: const TextStyle(fontWeight: FontWeight.w600)),
            lines('Secrets to provide at run time (WAYANG_SECRET_<NAME>)', plan.requiredSecrets),
            lines('Settings (WAYANG_CONFIG_<NAME>, bundled value is the default)', plan.requiredConfig),
            lines('Know before you deploy', plan.notes),
            const SizedBox(height: 10),
            const Text('Download', style: TextStyle(fontWeight: FontWeight.w700)),
            const Text('Run this in a shell where WAYANG_TOKEN holds your API token:', style: TextStyle(fontSize: 12)),
            Row(children: [
              Expanded(
                child: SelectableText(releaseDownloadCommand(base, _exportProject ?? '', _release, plan.zipName),
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
              ),
              TextButton(
                onPressed: () async {
                  await Clipboard.setData(ClipboardData(text: releaseDownloadCommand(base, _exportProject ?? '', _release, plan.zipName)));
                  if (mounted) showToast(context, 'Copied. The command holds no token.');
                },
                child: const Text('Copy'),
              ),
            ]),
          ],
        ]),
      ),
    );
  }

  Widget _check(String label, bool value, void Function(bool) set) => Row(mainAxisSize: MainAxisSize.min, children: [
        Checkbox(value: value, onChanged: (v) => setState(() => set(v ?? false))),
        Text(label),
      ]);
}

class _ReportView extends StatelessWidget {
  const _ReportView({required this.report});
  final ImportReport report;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    Widget lines(String title, List<String> xs, {Color? color}) => xs.isEmpty
        ? const SizedBox.shrink()
        : Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: TextStyle(fontWeight: FontWeight.w700, color: color)),
              for (final x in xs) Text('• $x', style: const TextStyle(fontSize: 12)),
            ]),
          );
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(
        report.ok ? (report.dryRun ? 'Looks fine. Nothing was written.' : 'Done.') : (report.dryRun ? 'Problems found. Nothing was written.' : 'Stopped.'),
        style: TextStyle(fontWeight: FontWeight.w700, color: report.ok ? Colors.green : scheme.error),
      ),
      lines('Problems', report.problems, color: scheme.error),
      lines('Warnings', report.warnings),
      if (report.needsSetup) ...[
        lines(report.secretsChecked ? 'Secrets missing here' : 'Secrets this needs (could not check)', report.missingSecrets),
        lines('Settings missing here', report.missingConfig),
        lines('Connected accounts to set up', report.oauthConnections),
      ],
      if (report.webhookSecrets.isNotEmpty) ...[
        const SizedBox(height: 8),
        const Text('New webhook secrets (shown once)', style: TextStyle(fontWeight: FontWeight.w700)),
        for (final e in report.webhookSecrets.entries) SelectableText('${e.key}: ${e.value}', style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
      ],
      const SizedBox(height: 8),
      for (final i in report.items)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 1),
          child: Text('${i.status == 'error' ? '✗' : '✓'} ${i.type} ${i.id}: ${i.action}${i.message.isEmpty ? '' : ' (${i.message})'}',
              style: TextStyle(fontSize: 12, color: i.isError ? scheme.error : null)),
        ),
    ]);
  }
}
