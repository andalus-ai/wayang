import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../data/api_client.dart';
import '../../data/field_mapping_model.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';
import '../../providers/bpmn_provider.dart';
import '../../providers/node_test_provider.dart';
import '../../providers/openapi_operations_provider.dart';
import '../../data/component_inputs.dart';
import '../../data/openapi_model.dart';
import '../common/schema_form.dart';
import 'field_mapping_builder.dart';

const _mono = TextStyle(fontFamily: 'monospace', fontSize: 12);

/// The editor for a service node's `inputMapping` / `outputMapping`: a one-line summary and an "Edit mapping" button that opens the field mapping
/// builder with a live preview. [output] mappings are tried against `{variables..., result}`.
FieldBuilder mappingFieldBuilder({required bool output, required String nodeId, required Map<String, dynamic> props}) =>
    (context, value, onChanged) => MappingField(value: value, output: output, nodeId: nodeId, props: props, onChanged: onChanged);

class MappingField extends ConsumerWidget {
  const MappingField({super.key, required this.value, required this.output, required this.nodeId, required this.props, required this.onChanged});

  final Object? value;
  final bool output;
  final String nodeId;

  /// The node's properties: which connector and operation it runs decides what an input mapping can be filled from.
  final Map<String, dynamic> props;
  final ValueChanged<Object?> onChanged;

  /// "3 fields", "Custom mapping (JSON)", or "Not set".
  static String summary(Object? value) {
    if (value == null || (value is Map && value.isEmpty)) return 'Not set';
    final rows = rowsFromSpec(value);
    if (rows == null) return 'Custom mapping (edit as JSON)';
    return rows.length == 1 ? '1 field' : '${rows.length} fields';
  }

  /// The starting sample: for an output mapping, `{"result": <the last test output>}` when this node was tested, otherwise an empty one.
  static String sampleText(bool output, Object? lastTestOutput) {
    const enc = JsonEncoder.withIndent('  ');
    if (!output) return '{}';
    return enc.convert({'result': lastTestOutput ?? <String, dynamic>{}});
  }

  /// "Fill from ..." offers for an input mapping: the chosen OpenAPI operation's arguments, else the connector component's declared inputs.
  List<MappingSuggestion> suggestions(WidgetRef ref) {
    if (output) return const [];
    final connector = props['connector'];
    final opName = props['operation'];
    if (connector == 'openapi' && opName is String) {
      final ops = ref.read(openApiOperationsProvider(nodeId)) ?? const <OpenApiOperation>[];
      for (final op in ops) {
        if (op.name != opName || op.inputSchema.isEmpty) continue;
        return [
          MappingSuggestion(
            label: 'Fill from operation',
            tooltip: 'The arguments of ${op.method} ${op.path}',
            build: (sample, existing) => rowsFromSchema(op.inputSchema, sample: sample) ?? const [],
          ),
        ];
      }
    }
    final components = ref.read(componentCatalogProvider).valueOrNull ?? const <ComponentCatalogEntry>[];
    final entry = componentForNode(components, props);
    final inputs = entry == null ? const <ComponentInput>[] : componentInputs(entry);
    if (inputs.isEmpty) return const [];
    return [
      MappingSuggestion(
        label: 'Fill from connector inputs',
        tooltip: 'The inputs ${entry!.displayName} declares (required ones first)',
        build: (sample, existing) => rowsFromInputs(
          [for (final i in inputs) (name: i.name, type: i.type, mandatory: i.mandatory, description: i.description)],
          sample: sample,
          existing: existing,
        ),
      ),
    ];
  }

  /// [current] sample text with [variables] (a run's) merged in: the run's values win, except a non-empty `result` already in the sample
  /// (an output mapping's test output) is kept. An unreadable [current] is replaced.
  static String mergeVariables(String current, Map<String, dynamic> variables) {
    Map<String, dynamic> base;
    try {
      final v = jsonDecode(current.trim().isEmpty ? '{}' : current);
      base = v is Map ? Map<String, dynamic>.from(v) : <String, dynamic>{};
    } on FormatException {
      base = <String, dynamic>{};
    }
    final keep = base['result'];
    final out = <String, dynamic>{...variables};
    if (keep != null && !(keep is Map && keep.isEmpty)) out['result'] = keep;
    return const JsonEncoder.withIndent('  ').convert(out);
  }

  Future<void> _edit(BuildContext context, WidgetRef ref) async {
    final last = output ? ref.read(lastNodeTestOutputProvider(nodeId)) : null;
    final result = await showDialog<Map<String, dynamic>?>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _MappingDialog(
        initial: value is Map ? Map<String, dynamic>.from(value as Map) : <String, dynamic>{},
        output: output,
        sample: sampleText(output, last),
        sampleFromTest: last != null,
        suggestions: suggestions(ref),
        processId: ref.read(bpmnControllerProvider).diagram.id,
      ),
    );
    if (result == null) return; // cancelled
    onChanged(result.isEmpty ? null : result);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final set = !(value == null || (value is Map && (value as Map).isEmpty));
    return Row(children: [
      Expanded(child: Text(summary(value), style: TextStyle(fontSize: 12, color: set ? null : AppColors.textMutedDark))),
      OutlinedButton.icon(onPressed: () => _edit(context, ref), icon: const Icon(Icons.swap_horiz_rounded, size: 16), label: Text(set ? 'Edit mapping' : 'Add mapping')),
      if (set) IconButton(tooltip: 'Remove the mapping', visualDensity: VisualDensity.compact, icon: const Icon(Icons.close, size: 16), onPressed: () => onChanged(null)),
    ]);
  }
}

class _MappingDialog extends ConsumerStatefulWidget {
  const _MappingDialog({required this.initial, required this.output, required this.sample, required this.sampleFromTest, required this.suggestions, required this.processId});
  final Map<String, dynamic> initial;
  final bool output;
  final String sample;
  final bool sampleFromTest;
  final List<MappingSuggestion> suggestions;
  final String processId;

  @override
  ConsumerState<_MappingDialog> createState() => _MappingDialogState();
}

class _MappingDialogState extends ConsumerState<_MappingDialog> {
  late final _spec = TextEditingController(text: const JsonEncoder.withIndent('  ').convert(widget.initial));
  late final _sample = TextEditingController(text: widget.sample);
  final _result = ValueNotifier<Object?>(null);
  bool _builder = true;
  bool _omitNull = false;
  List<String> _problems = const [];
  bool _loadingRun = false;
  String? _runNote;
  String? _error;
  String? _preview;
  Timer? _debounce;
  int _seq = 0;

  @override
  void initState() {
    super.initState();
    _spec.addListener(_changed);
    _sample.addListener(_changed);
    _changed();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _spec.dispose();
    _sample.dispose();
    _result.dispose();
    super.dispose();
  }

  void _changed() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 600), _run);
  }

  Future<void> _run() async {
    final seq = ++_seq;
    Object? spec;
    Object? vars;
    try {
      spec = jsonDecode(_spec.text);
      vars = _sample.text.trim().isEmpty ? <String, dynamic>{} : jsonDecode(_sample.text);
    } on FormatException catch (e) {
      if (!mounted || seq != _seq) return;
      setState(() {
        _error = 'Not valid JSON: ${e.message}';
        _preview = null;
        _result.value = null;
      });
      return;
    }
    if (vars is! Map) {
      setState(() => _error = 'The sample must be a JSON object.');
      return;
    }
    try {
      final out = await ref.read(repositoryProvider).previewMapper({'spec': spec, 'vars': vars, 'omitNull': _omitNull});
      if (!mounted || seq != _seq) return;
      setState(() {
        _error = null;
        _preview = const JsonEncoder.withIndent('  ').convert(out['result']);
        _result.value = out['result'];
      });
    } catch (e) {
      if (!mounted || seq != _seq) return;
      setState(() {
        _error = errText(e);
        _preview = null;
        _result.value = null;
      });
    }
  }

  bool get _changedFromStart {
    final now = _specOrNull();
    return now == null || jsonEncode(now) != jsonEncode(widget.initial);
  }

  Future<void> _cancel() async {
    if (_changedFromStart) {
      final discard = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Discard your changes?'),
          content: const Text('The mapping on the node stays as it was.'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Keep editing')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Discard')),
          ],
        ),
      );
      if (discard != true || !mounted) return;
    }
    Navigator.pop(context);
  }

  Future<void> _loadRunVariables() async {
    setState(() {
      _loadingRun = true;
      _runNote = null;
    });
    try {
      final runs = await ref.read(repositoryProvider).listRuns(processId: widget.processId, limit: 1);
      if (!mounted) return;
      if (runs.isEmpty) {
        setState(() {
          _loadingRun = false;
          _runNote = 'No run of this process yet.';
        });
        return;
      }
      final text = MappingField.mergeVariables(_sample.text, runs.first.variables);
      if (text.length > 512 * 1024) {
        setState(() {
          _loadingRun = false;
          _runNote = 'The last run\'s variables are too large to use as a sample.';
        });
        return;
      }
      _sample.text = text;
      setState(() {
        _loadingRun = false;
        _runNote = 'Loaded the final variables of the latest run (${runs.first.status.name}); some may be set after this node.';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loadingRun = false;
        _runNote = errText(e);
      });
    }
  }

  Map<String, dynamic>? _specOrNull() {
    try {
      final v = jsonDecode(_spec.text);
      return v is Map ? Map<String, dynamic>.from(v) : null;
    } catch (_) {
      return null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final canApply = _specOrNull() != null;
    return AlertDialog(
      title: Text(widget.output ? 'Output mapping' : 'Input mapping'),
      content: SizedBox(
        width: (size.width * 0.9).clamp(320, 1100).toDouble(),
        height: (size.height * 0.7).clamp(360, 700).toDouble(),
        child: LayoutBuilder(builder: (context, c) {
          final wide = c.maxWidth > 760;
          final left = Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Row(children: [
              SegmentedButton<bool>(
                showSelectedIcon: false,
                segments: const [
                  ButtonSegment(value: true, label: Text('Builder'), icon: Icon(Icons.table_rows_rounded, size: 16)),
                  ButtonSegment(value: false, label: Text('JSON'), icon: Icon(Icons.code_rounded, size: 16)),
                ],
                selected: {_builder},
                onSelectionChanged: (v) => setState(() => _builder = v.first),
              ),
              const SizedBox(width: 12),
              FilterChip(
                label: const Text('Omit nulls'),
                selected: _omitNull,
                onSelected: (v) {
                  setState(() => _omitNull = v);
                  _run();
                },
              ),
            ]),
            const SizedBox(height: 8),
            Expanded(
              child: _builder
                  ? FieldMappingBuilder(controller: _spec, sample: _sample, result: _result, onProblems: (p) => setState(() => _problems = p), suggestions: widget.suggestions)
                  : TextField(controller: _spec, expands: true, maxLines: null, minLines: null, textAlignVertical: TextAlignVertical.top, style: _mono, decoration: const InputDecoration(border: OutlineInputBorder(), isDense: true)),
            ),
          ]);
          final right = Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(widget.output ? 'Sample (process variables plus "result")' : 'Sample (process variables)', style: const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w700)),
            if (widget.sampleFromTest) const Text('"result" is the output of this node\'s last test.', style: TextStyle(fontSize: 11, color: AppColors.textMutedDark)),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                onPressed: _loadingRun ? null : _loadRunVariables,
                icon: _loadingRun ? const SizedBox(width: 12, height: 12, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.history_rounded, size: 16),
                label: const Text('Use variables of the latest run', style: TextStyle(fontSize: 12)),
              ),
            ),
            if (_runNote != null) Text(_runNote!, style: const TextStyle(fontSize: 11, color: AppColors.textMutedDark)),
            const SizedBox(height: 4),
            Expanded(
              child: TextField(controller: _sample, expands: true, maxLines: null, minLines: null, textAlignVertical: TextAlignVertical.top, style: _mono, decoration: const InputDecoration(border: OutlineInputBorder(), isDense: true)),
            ),
            const SizedBox(height: 8),
            const Text('Result', style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w700)),
            const SizedBox(height: 4),
            Expanded(
              child: SingleChildScrollView(
                child: SelectableText(_error ?? _preview ?? 'Edit the mapping or the sample to see the result.',
                    style: _mono.copyWith(color: _error != null ? Theme.of(context).colorScheme.error : null)),
              ),
            ),
          ]);
          return wide
              ? Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [Expanded(flex: 3, child: left), const SizedBox(width: 12), Expanded(flex: 2, child: right)])
              : Column(children: [Expanded(child: left), const SizedBox(height: 8), SizedBox(height: 180, child: right)]);
        }),
      ),
      actions: [
        if (_problems.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(right: 8),
            child: Text('Fix the highlighted rows to apply.', style: TextStyle(fontSize: 11.5, color: Theme.of(context).colorScheme.error)),
          ),
        TextButton(onPressed: _cancel, child: const Text('Cancel')),
        FilledButton(
          onPressed: canApply && _problems.isEmpty ? () => Navigator.pop(context, _specOrNull()) : null,
          child: const Text('Apply'),
        ),
      ],
    );
  }
}
