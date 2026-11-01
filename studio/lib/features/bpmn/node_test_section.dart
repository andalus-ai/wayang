import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/node_test_model.dart';
import '../../providers/app_providers.dart';

const _mono = TextStyle(fontFamily: 'monospace', fontSize: 11.5);

/// "Test node" under a service node whose connector operation only reads. It makes one real call with the tenant's secrets after an
/// explicit confirmation; the server refuses anything that could send or change something, whatever this widget offers.
class NodeTestSection extends ConsumerStatefulWidget {
  const NodeTestSection({super.key, required this.props});

  final Map<String, dynamic> props;

  @override
  ConsumerState<NodeTestSection> createState() => _NodeTestSectionState();
}

class _NodeTestSectionState extends ConsumerState<NodeTestSection> {
  final _inputs = TextEditingController(text: '{}');
  bool _busy = false;
  String? _error;
  String? _output;

  @override
  void dispose() {
    _inputs.dispose();
    super.dispose();
  }

  Future<void> _run() async {
    final Map<String, dynamic> inputs;
    try {
      final v = jsonDecode(_inputs.text.trim().isEmpty ? '{}' : _inputs.text);
      if (v is! Map) throw const FormatException('must be a JSON object');
      inputs = Map<String, dynamic>.from(v);
    } on FormatException catch (e) {
      setState(() => _error = 'Inputs are not valid JSON: ${e.message}');
      return;
    }
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Make a real call?'),
        content: Text('This calls ${widget.props['connector']} now, using your stored secrets. Only reading operations are allowed; '
            'nothing is sent or changed.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Call')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() {
      _busy = true;
      _error = null;
      _output = null;
    });
    try {
      final out = await ref.read(repositoryProvider).testConnector(testRequest(widget.props, inputs, confirmed: true));
      if (!mounted) return;
      setState(() {
        _busy = false;
        _output = const JsonEncoder.withIndent('  ').convert(out['output']);
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = errText(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!canTestNode(widget.props)) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 12),
        const PanelSectionLabel('Test node (read only)'),
        const SizedBox(height: 4),
        TextField(
          controller: _inputs,
          minLines: 2,
          maxLines: 6,
          style: _mono,
          decoration: const InputDecoration(isDense: true, labelText: 'Sample inputs (JSON)', border: OutlineInputBorder()),
        ),
        const SizedBox(height: 6),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            onPressed: _busy ? null : _run,
            icon: _busy
                ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.science_outlined, size: 16),
            label: const Text('Test'),
          ),
        ),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: SelectableText(_error!, style: const TextStyle(fontSize: 11, color: AppColors.danger)),
          ),
        if (_output != null)
          Container(
            margin: const EdgeInsets.only(top: 6),
            padding: const EdgeInsets.all(8),
            constraints: const BoxConstraints(maxHeight: 260),
            decoration: BoxDecoration(border: Border.all(color: AppColors.textMutedDark.withOpacity(0.4)), borderRadius: BorderRadius.circular(6)),
            child: SingleChildScrollView(child: SelectableText(_output!, style: _mono)),
          ),
      ],
    );
  }
}
