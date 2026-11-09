import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../providers/app_providers.dart';
import 'pipeline_builder.dart';

const _mono = TextStyle(fontFamily: 'monospace', fontSize: 12);

/// A starting point per mode; the editor runs against POST /api/v1/mapper/preview (no side effects, no connectors).
class _Sample {
  const _Sample(this.label, this.pipeline, this.spec, this.input);
  final String label;
  final bool pipeline;
  final String spec;
  final String input;
}

const _samples = <_Sample>[
  _Sample(
    'Service node: output mapping',
    false,
    '{\n  "tier": "result.data.tier",\n  "ok": "result.status = 200",\n  "greeting": {"\$template": "Hi \${customer.name}"}\n}',
    '{\n  "customer": {"name": "Ada"},\n  "result": {"status": 200, "data": {"tier": "gold"}}\n}',
  ),
  _Sample(
    'Pipeline: JSON to CSV',
    true,
    '{\n  "steps": [\n    {"type": "parse", "format": "json"},\n    {"type": "map", "mapping": {"\$each": "body.lines", "\$as": "l", "\$map": {"sku": "l.sku", "qty": "l.qty"}}},\n    {"type": "serialize", "format": "csv"}\n  ]\n}',
    '"{\\"lines\\":[{\\"sku\\":\\"a\\",\\"qty\\":1},{\\"sku\\":\\"b\\",\\"qty\\":2}]}"',
  ),
  _Sample(
    'Pipeline: route and dead-letter',
    true,
    '{\n  "steps": [\n    {"type": "guard", "deadLetter": "dlq", "steps": [\n      {"type": "choice", "branches": [\n        {"when": "body.vip", "steps": [{"type": "map", "headers": {"lane": {"\$const": "fast"}}}]}\n      ], "otherwise": [{"type": "map", "headers": {"lane": {"\$const": "slow"}}}]}\n    ]}\n  ]\n}',
    '{"vip": true}',
  ),
];

/// Try a mapper spec or an EIP pipeline against sample input, live.
class MapperScreen extends ConsumerStatefulWidget {
  const MapperScreen({super.key});

  @override
  ConsumerState<MapperScreen> createState() => _MapperScreenState();
}

class _MapperScreenState extends ConsumerState<MapperScreen> {
  final _spec = TextEditingController();
  final _input = TextEditingController();
  bool _pipeline = false;
  bool _omitNull = false;
  bool _builder = true; // pipeline mode: step builder (true) or raw JSON (false)
  bool _busy = false;
  String? _error;
  String? _result;
  Timer? _debounce;
  int _seq = 0;

  @override
  void initState() {
    super.initState();
    _load(_samples.first);
    _spec.addListener(_changed);
    _input.addListener(_changed);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _spec.dispose();
    _input.dispose();
    super.dispose();
  }

  void _load(_Sample s) {
    _pipeline = s.pipeline;
    _spec.text = s.spec;
    _input.text = s.input;
    _result = null;
    _error = null;
  }

  void _changed() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 600), _run);
  }

  Future<void> _run() async {
    final seq = ++_seq;
    dynamic spec;
    dynamic input;
    try {
      spec = jsonDecode(_spec.text);
      input = _input.text.trim().isEmpty ? null : jsonDecode(_input.text);
    } catch (e) {
      if (!mounted || seq != _seq) return;
      setState(() {
        _error = 'Not valid JSON: ${e is FormatException ? e.message : e}';
        _result = null;
      });
      return;
    }
    final request = _pipeline
        ? <String, dynamic>{'pipeline': spec, 'body': input}
        : <String, dynamic>{'spec': spec, 'vars': input is Map ? input : <String, dynamic>{}, 'omitNull': _omitNull};
    setState(() => _busy = true);
    try {
      final out = await ref.read(repositoryProvider).previewMapper(request);
      if (!mounted || seq != _seq) return; // a newer edit superseded this answer
      setState(() {
        _busy = false;
        _error = null;
        _result = const JsonEncoder.withIndent('  ').convert(_pipeline ? out['messages'] : out['result']);
      });
    } catch (e) {
      if (!mounted || seq != _seq) return;
      setState(() {
        _busy = false;
        _error = errText(e);
        _result = null;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ScreenHeader(
            title: 'Mapper',
            subtitle: 'Try a field mapping or an integration pipeline on sample data. Nothing is saved or called.',
            icon: Icons.swap_horiz_rounded,
            actions: [
              PopupMenuButton<_Sample>(
                tooltip: 'Examples',
                icon: const Icon(Icons.lightbulb_outline_rounded),
                onSelected: (s) {
                  setState(() => _load(s));
                  _run();
                },
                itemBuilder: (_) => [for (final s in _samples) PopupMenuItem(value: s, child: Text(s.label))],
              ),
              const SizedBox(width: 4),
              ElevatedButton.icon(
                onPressed: _busy ? null : _run,
                icon: const Icon(Icons.play_arrow_rounded, size: 18),
                label: const Text('Run'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Row(children: [
            SegmentedButton<bool>(
              segments: const [
                ButtonSegment(value: false, label: Text('Field mapping')),
                ButtonSegment(value: true, label: Text('Pipeline')),
              ],
              selected: {_pipeline},
              onSelectionChanged: (s) {
                setState(() => _pipeline = s.first);
                _run();
              },
            ),
            const SizedBox(width: 16),
            if (!_pipeline)
              FilterChip(
                label: const Text('Omit nulls'),
                selected: _omitNull,
                onSelected: (v) {
                  setState(() => _omitNull = v);
                  _run();
                },
              ),
          ]),
          const SizedBox(height: 12),
          Expanded(
            child: LayoutBuilder(builder: (context, c) {
              final wide = c.maxWidth > 900;
              final panes = [
                _pane(_pipeline ? 'Pipeline (JSON)' : 'Mapping spec (JSON)', child: _pipeline ? _pipelineEditor() : _editor(_spec)),
                _pane(_pipeline ? 'Message body (JSON; use a string for text)' : 'Variables (JSON; add "result" to test an output mapping)',
                    child: _editor(_input)),
                _pane(_pipeline ? 'Output messages' : 'Result', child: _output()),
              ];
              return wide
                  ? Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                      for (final p in panes) Expanded(child: Padding(padding: const EdgeInsets.only(right: 12), child: p)),
                    ])
                  : ListView(children: [for (final p in panes) SizedBox(height: 260, child: Padding(padding: const EdgeInsets.only(bottom: 12), child: p))]);
            }),
          ),
        ],
      ),
    );
  }

  Widget _pipelineEditor() => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Align(
          alignment: Alignment.centerLeft,
          child: SegmentedButton<bool>(
            showSelectedIcon: false,
            segments: const [
              ButtonSegment(value: true, label: Text('Builder'), icon: Icon(Icons.view_list_rounded, size: 16)),
              ButtonSegment(value: false, label: Text('JSON'), icon: Icon(Icons.code_rounded, size: 16)),
            ],
            selected: {_builder},
            onSelectionChanged: (v) => setState(() => _builder = v.first),
          ),
        ),
        const SizedBox(height: 8),
        Expanded(child: _builder ? PipelineBuilder(controller: _spec) : _editor(_spec)),
      ]);

  Widget _editor(TextEditingController c) => TextField(
        controller: c,
        expands: true,
        maxLines: null,
        minLines: null,
        textAlignVertical: TextAlignVertical.top,
        style: _mono,
        decoration: const InputDecoration(border: InputBorder.none, isDense: true),
      );

  Widget _output() {
    if (_error != null) {
      return SingleChildScrollView(child: SelectableText(_error!, style: _mono.copyWith(color: Theme.of(context).colorScheme.error)));
    }
    if (_result == null) {
      return Center(child: Text(_busy ? 'Running…' : 'Edit the spec or input to see the result.'));
    }
    return SingleChildScrollView(child: SelectableText(_result!, style: _mono));
  }

  Widget _pane(String label, {required Widget child}) => SurfacePanel(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          PanelSectionLabel(label),
          const SizedBox(height: 6),
          Expanded(child: child),
        ]),
      );
}
