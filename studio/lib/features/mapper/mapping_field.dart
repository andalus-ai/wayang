import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../data/api_client.dart';
import '../../data/field_mapping_model.dart';
import '../../providers/app_providers.dart';
import '../common/schema_form.dart';
import 'field_mapping_builder.dart';

const _mono = TextStyle(fontFamily: 'monospace', fontSize: 12);

/// The editor for a service node's `inputMapping` / `outputMapping`: a one-line summary and an "Edit mapping" button that opens the field mapping
/// builder with a live preview. [output] mappings are tried against `{variables..., result}`.
FieldBuilder mappingFieldBuilder({required bool output}) =>
    (context, value, onChanged) => MappingField(value: value, output: output, onChanged: onChanged);

class MappingField extends StatelessWidget {
  const MappingField({super.key, required this.value, required this.output, required this.onChanged});

  final Object? value;
  final bool output;
  final ValueChanged<Object?> onChanged;

  /// "3 fields", "Custom mapping (JSON)", or "Not set".
  static String summary(Object? value) {
    if (value == null || (value is Map && value.isEmpty)) return 'Not set';
    final rows = rowsFromSpec(value);
    if (rows == null) return 'Custom mapping (edit as JSON)';
    return rows.length == 1 ? '1 field' : '${rows.length} fields';
  }

  Future<void> _edit(BuildContext context) async {
    final result = await showDialog<Map<String, dynamic>?>(
      context: context,
      builder: (_) => _MappingDialog(initial: value is Map ? Map<String, dynamic>.from(value as Map) : <String, dynamic>{}, output: output),
    );
    if (result == null) return; // cancelled
    onChanged(result.isEmpty ? null : result);
  }

  @override
  Widget build(BuildContext context) {
    final set = !(value == null || (value is Map && (value as Map).isEmpty));
    return Row(children: [
      Expanded(child: Text(summary(value), style: TextStyle(fontSize: 12, color: set ? null : AppColors.textMutedDark))),
      OutlinedButton.icon(onPressed: () => _edit(context), icon: const Icon(Icons.swap_horiz_rounded, size: 16), label: Text(set ? 'Edit mapping' : 'Add mapping')),
      if (set) IconButton(tooltip: 'Remove the mapping', visualDensity: VisualDensity.compact, icon: const Icon(Icons.close, size: 16), onPressed: () => onChanged(null)),
    ]);
  }
}

class _MappingDialog extends ConsumerStatefulWidget {
  const _MappingDialog({required this.initial, required this.output});
  final Map<String, dynamic> initial;
  final bool output;

  @override
  ConsumerState<_MappingDialog> createState() => _MappingDialogState();
}

class _MappingDialogState extends ConsumerState<_MappingDialog> {
  late final _spec = TextEditingController(text: const JsonEncoder.withIndent('  ').convert(widget.initial));
  late final _sample = TextEditingController(text: widget.output ? '{\n  "result": {}\n}' : '{}');
  final _result = ValueNotifier<Object?>(null);
  bool _builder = true;
  bool _omitNull = false;
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
                  ? FieldMappingBuilder(controller: _spec, sample: _sample, result: _result)
                  : TextField(controller: _spec, expands: true, maxLines: null, minLines: null, textAlignVertical: TextAlignVertical.top, style: _mono, decoration: const InputDecoration(border: OutlineInputBorder(), isDense: true)),
            ),
          ]);
          final right = Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(widget.output ? 'Sample (process variables plus "result")' : 'Sample (process variables)', style: const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w700)),
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
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          onPressed: canApply ? () => Navigator.pop(context, _specOrNull()) : null,
          child: const Text('Apply'),
        ),
      ],
    );
  }
}
