import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show LogicalKeyboardKey;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/unsaved_edits.dart';
import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/definition_edit.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';
import '../mapper/pipeline_builder.dart';
import 'agent_builder.dart';

/// Definition editor for agents and pipelines. Pipelines expose a visual EIP graph
/// projection alongside JSON editing; both use the same canonical document and API.
class DefinitionsScreen extends ConsumerStatefulWidget {
  const DefinitionsScreen({super.key, required this.kind});
  final DefinitionKind kind;

  @override
  ConsumerState<DefinitionsScreen> createState() => _DefinitionsScreenState();
}

class _DefinitionsScreenState extends ConsumerState<DefinitionsScreen> {
  String? _id;
  int? _revision;
  final _text = TextEditingController();
  bool _loading = false;
  bool _busy = false; // validate / save / publish in flight
  String _baseline = ''; // the text as loaded or last saved; differs => unsaved edits
  String _query = '';
  bool _visualBuilder = true;
  String? _error;
  String? _info;
  List<ValidationItem> _items = const [];

  @override
  void initState() {
    super.initState();
    _text.addListener(_edited);
  }

  void _edited() {
    if (mounted) setState(() {}); // the dirty marker and the guards read the text
  }

  bool get _dirty => definitionDirty(_id, _text.text, _baseline);

  @override
  void dispose() {
    _text.removeListener(_edited);
    _text.dispose();
    super.dispose();
  }

  /// True when it is fine to leave what is open: nothing unsaved, or the person saved or discarded it.
  Future<bool> _canLeave() async {
    if (!_dirty) return true;
    return confirmDiscardEdits(context, save: _save, what: '"$_id"');
  }

  Future<void> _open(String id) async {
    if (id != _id && !await _canLeave()) return;
    if (!mounted) return;
    setState(() {
      _id = id;
      _loading = true;
      _error = null;
      _info = null;
      _items = const [];
    });
    try {
      final d = await ref.read(repositoryProvider).loadForEditing(widget.kind, id);
      _revision = d.isDraft ? d.revision : null;
      _text.text = const JsonEncoder.withIndent('  ').convert(d.document);
      _baseline = _text.text;
      setState(() => _loading = false);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = errText(e);
      });
    }
  }

  Map<String, dynamic>? _doc() {
    final m = tryParseJsonObject(_text.text);
    if (m == null) setState(() => _error = 'Not a JSON object');
    return m;
  }

  Future<void> _validate() async {
    final doc = _doc();
    if (doc == null || _busy) return;
    setState(() => _busy = true);
    try {
      final r = await ref.read(repositoryProvider).validate(widget.kind, doc);
      if (!mounted) return;
      setState(() {
        _items = r.items;
        _error = null;
        _info = r.valid ? 'Valid.' : null;
      });
    } catch (e) {
      if (mounted) setState(() => _error = errText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<bool> _save() async {
    final doc = _doc();
    final id = _id;
    if (doc == null || id == null || _busy) return false;
    final saving = _text.text;
    setState(() => _busy = true);
    try {
      final saved = await ref.read(repositoryProvider).saveDraft(widget.kind, id, doc, revision: _revision);
      _revision = saved.revision;
      _baseline = saving; // edits typed while the save ran stay unsaved
      ref.invalidate(definitionsProvider(widget.kind));
      if (mounted) {
        setState(() {
          _error = null;
          _info = 'Draft saved (revision ${saved.revision}).';
        });
      }
      return true;
    } catch (e) {
      if (mounted) setState(() => _error = errText(e));
      return false;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _publish() async {
    if (_busy || !await _save()) return;
    setState(() => _busy = true);
    try {
      final v = await ref.read(repositoryProvider).publish(widget.kind, _id!);
      ref.invalidate(definitionsProvider(widget.kind));
      if (mounted) setState(() => _info = 'Published as version ${v.version}.');
    } catch (e) {
      if (mounted) setState(() => _error = errText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _new() async {
    if (!await _canLeave() || !mounted) return;
    final existing = (ref.read(definitionsProvider(widget.kind)).valueOrNull ?? const <DefinitionSummary>[]).map((d) => d.id).toList();
    final ctrl = TextEditingController();
    final id = await showDialog<String>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, set) {
          final problem = ctrl.text.isEmpty ? null : definitionIdProblem(ctrl.text, existing);
          void submit() {
            if (definitionIdProblem(ctrl.text, existing) == null) Navigator.pop(ctx, ctrl.text.trim());
          }

          return AlertDialog(
            title: Text('New ${widget.kind.name}'),
            content: TextField(
              controller: ctrl,
              autofocus: true,
              onChanged: (_) => set(() {}),
              onSubmitted: (_) => submit(),
              decoration: InputDecoration(labelText: 'Id (e.g. support-triage)', errorText: problem),
            ),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
              ElevatedButton(onPressed: definitionIdProblem(ctrl.text, existing) == null ? submit : null, child: const Text('Create')),
            ],
          );
        },
      ),
    );
    if (id == null || !mounted) return;
    setState(() {
      _id = id;
      _revision = null;
      _items = const [];
      _info = null;
      _error = null;
      _baseline = ''; // a new document is unsaved until it is saved
      _text.text = const JsonEncoder.withIndent('  ').convert({'id': _id, 'name': _id});
    });
  }

  @override
  Widget build(BuildContext context) {
    final list = ref.watch(definitionsProvider(widget.kind));
    reportUnsaved(ref, 'definition:${widget.kind.name}', _dirty);
    return CallbackShortcuts(
      bindings: {
        const SingleActivator(LogicalKeyboardKey.keyS, control: true): _save,
        const SingleActivator(LogicalKeyboardKey.keyS, meta: true): _save,
      },
      child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ScreenHeader(
            title: widget.kind.label,
            subtitle: widget.kind == DefinitionKind.pipeline
                ? 'Compose integration steps visually or edit the underlying JSON. Server validation remains authoritative.'
                : widget.kind == DefinitionKind.agent
                    ? 'Build an agent with the form or edit the underlying JSON. The server validates before anything can be published.'
                    : 'JSON editor — the document is validated by the server before it can be published.',
            icon: widget.kind == DefinitionKind.agent ? Icons.smart_toy_outlined : Icons.hub_outlined,
            actions: [ElevatedButton.icon(onPressed: _new, icon: const Icon(Icons.add_rounded, size: 18), label: const Text('New'))],
          ),
          const SizedBox(height: 16),
          Expanded(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SizedBox(
                  width: 260,
                  child: list.when(
                    loading: () => const LoadingView(),
                    error: (e, _) => ErrorView(message: errText(e), needsSettings: needsSettings(e)),
                    data: (all) {
                      final items = filterDefinitions(all, _query);
                      return Column(children: [
                        TextField(
                          decoration: const InputDecoration(isDense: true, prefixIcon: Icon(Icons.search, size: 18), hintText: 'Search'),
                          onChanged: (v) => setState(() => _query = v),
                        ),
                        const SizedBox(height: 8),
                        if (all.isEmpty)
                          Padding(padding: const EdgeInsets.all(12), child: Text('No ${widget.kind.label.toLowerCase()} yet. Create one with New.', style: const TextStyle(fontSize: 12)))
                        else if (items.isEmpty)
                          const Padding(padding: EdgeInsets.all(12), child: Text('Nothing matches.', style: TextStyle(fontSize: 12))),
                        Expanded(
                          child: ListView(children: [
                      for (final d in items)
                        ListTile(
                          dense: true,
                          selected: d.id == _id,
                          title: Text('${d.id == _id && _dirty ? '• ' : ''}${d.name}', overflow: TextOverflow.ellipsis),
                          subtitle: Text(d.isPublished ? 'v${d.latestVersion}${d.hasDraft ? ' + draft' : ''}' : 'draft only', style: const TextStyle(fontSize: 11)),
                          onTap: () => _open(d.id),
                        ),
                          ]),
                        ),
                      ]);
                    },
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: _id == null
                      ? const EmptyState(icon: Icons.edit_note_rounded, title: 'Pick or create one')
                      : _loading
                          ? const LoadingView()
                          : Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(children: [
                                  Text(_id!, style: const TextStyle(fontWeight: FontWeight.w700)),
                                  const Spacer(),
                                  if (widget.kind == DefinitionKind.pipeline || widget.kind == DefinitionKind.agent) ...[
                                    SegmentedButton<bool>(
                                      showSelectedIcon: false,
                                      segments: [
                                        ButtonSegment(
                                            value: true,
                                            icon: Icon(widget.kind == DefinitionKind.agent ? Icons.tune_rounded : Icons.account_tree_outlined, size: 15),
                                            label: Text(widget.kind == DefinitionKind.agent ? 'Form' : 'Graph')),
                                        ButtonSegment(value: false, icon: Icon(Icons.code_rounded, size: 15), label: Text('JSON')),
                                      ],
                                      selected: {_visualBuilder},
                                      onSelectionChanged: (value) => setState(() => _visualBuilder = value.first),
                                    ),
                                    const SizedBox(width: 10),
                                  ],
                                  if (_busy) const Padding(padding: EdgeInsets.only(right: 10), child: SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))),
                                  OutlinedButton(onPressed: _busy ? null : _validate, child: const Text('Validate')),
                                  const SizedBox(width: 8),
                                  OutlinedButton(onPressed: _busy ? null : _save, child: Text(_dirty ? 'Save draft *' : 'Save draft')),
                                  const SizedBox(width: 8),
                                  ElevatedButton(onPressed: _busy ? null : _publish, child: const Text('Publish')),
                                ]),
                                if (_error != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(_error!, style: const TextStyle(color: AppColors.danger, fontSize: 12))),
                                if (_info != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(_info!, style: const TextStyle(color: AppColors.success, fontSize: 12))),
                                for (final i in _items)
                                  Padding(
                                    padding: const EdgeInsets.only(top: 4),
                                    child: Text('${i.isError ? 'Error' : 'Warning'}${i.elementId == null ? '' : ' (${i.elementId})'}: ${i.message}',
                                        style: TextStyle(fontSize: 12, color: i.isError ? AppColors.danger : AppColors.warning)),
                                  ),
                                const SizedBox(height: 10),
                                Expanded(
                                  child: widget.kind == DefinitionKind.agent && _visualBuilder
                                      ? AgentBuilder(controller: _text)
                                      : widget.kind == DefinitionKind.pipeline && _visualBuilder
                                      ? PipelineBuilder(controller: _text)
                                      : TextField(
                                          controller: _text,
                                          expands: true,
                                          maxLines: null,
                                          minLines: null,
                                          textAlignVertical: TextAlignVertical.top,
                                          style: const TextStyle(fontFamily: 'monospace', fontSize: 12.5),
                                          decoration: const InputDecoration(
                                            border: OutlineInputBorder(),
                                            alignLabelWithHint: true,
                                          ),
                                        ),
                                ),
                              ],
                            ),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
    );
  }
}
