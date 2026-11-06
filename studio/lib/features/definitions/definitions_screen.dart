import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';
import '../mapper/pipeline_builder.dart';

/// A JSON editor for definition kinds that have no visual designer yet (agents, pipelines). It uses the same
/// API as the designers: optimistic-concurrency draft save, server-side validation, publish.
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
  bool _visualBuilder = true;
  String? _error;
  String? _info;
  List<ValidationItem> _items = const [];

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<void> _open(String id) async {
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
    if (doc == null) return;
    try {
      final r = await ref.read(repositoryProvider).validate(widget.kind, doc);
      setState(() {
        _items = r.items;
        _error = null;
        _info = r.valid ? 'Valid.' : null;
      });
    } catch (e) {
      setState(() => _error = errText(e));
    }
  }

  Future<bool> _save() async {
    final doc = _doc();
    final id = _id;
    if (doc == null || id == null) return false;
    try {
      final saved = await ref.read(repositoryProvider).saveDraft(widget.kind, id, doc, revision: _revision);
      _revision = saved.revision;
      ref.invalidate(definitionsProvider(widget.kind));
      setState(() {
        _error = null;
        _info = 'Draft saved (revision ${saved.revision}).';
      });
      return true;
    } catch (e) {
      setState(() => _error = errText(e));
      return false;
    }
  }

  Future<void> _publish() async {
    if (!await _save()) return;
    try {
      final v = await ref.read(repositoryProvider).publish(widget.kind, _id!);
      ref.invalidate(definitionsProvider(widget.kind));
      setState(() => _info = 'Published as version ${v.version}.');
    } catch (e) {
      setState(() => _error = errText(e));
    }
  }

  Future<void> _new() async {
    final ctrl = TextEditingController();
    final id = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('New ${widget.kind.name}'),
        content: TextField(controller: ctrl, autofocus: true, decoration: const InputDecoration(labelText: 'Id (e.g. support-triage)')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          ElevatedButton(onPressed: () => Navigator.pop(ctx, ctrl.text), child: const Text('Create')),
        ],
      ),
    );
    if (id == null || id.trim().isEmpty) return;
    setState(() {
      _id = id.trim();
      _revision = null;
      _items = const [];
      _info = null;
      _error = null;
      _text.text = const JsonEncoder.withIndent('  ').convert({'id': _id, 'name': _id});
    });
  }

  @override
  Widget build(BuildContext context) {
    final list = ref.watch(definitionsProvider(widget.kind));
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ScreenHeader(
            title: widget.kind.label,
            subtitle: widget.kind == DefinitionKind.pipeline
                ? 'Compose integration steps visually or edit the underlying JSON. Server validation remains authoritative.'
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
                    error: (e, _) => ErrorView(message: errText(e)),
                    data: (items) => ListView(children: [
                      for (final d in items)
                        ListTile(
                          dense: true,
                          selected: d.id == _id,
                          title: Text(d.name, overflow: TextOverflow.ellipsis),
                          subtitle: Text(d.isPublished ? 'v${d.latestVersion}${d.hasDraft ? ' + draft' : ''}' : 'draft only', style: const TextStyle(fontSize: 11)),
                          onTap: () => _open(d.id),
                        ),
                    ]),
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
                                  if (widget.kind == DefinitionKind.pipeline) ...[
                                    SegmentedButton<bool>(
                                      showSelectedIcon: false,
                                      segments: const [
                                        ButtonSegment(value: true, icon: Icon(Icons.view_list_rounded, size: 15), label: Text('Builder')),
                                        ButtonSegment(value: false, icon: Icon(Icons.code_rounded, size: 15), label: Text('JSON')),
                                      ],
                                      selected: {_visualBuilder},
                                      onSelectionChanged: (value) => setState(() => _visualBuilder = value.first),
                                    ),
                                    const SizedBox(width: 10),
                                  ],
                                  OutlinedButton(onPressed: _validate, child: const Text('Validate')),
                                  const SizedBox(width: 8),
                                  OutlinedButton(onPressed: _save, child: const Text('Save draft')),
                                  const SizedBox(width: 8),
                                  ElevatedButton(onPressed: _publish, child: const Text('Publish')),
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
                                  child: widget.kind == DefinitionKind.pipeline && _visualBuilder
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
    );
  }
}
