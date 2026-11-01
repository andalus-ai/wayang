import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../data/api_client.dart';
import '../../core/widgets.dart';
import '../../data/soap_model.dart';
import '../../providers/app_providers.dart';

/// Under a `soap` service node: read the operations from the node's WSDL and pick one. Picking sets `operation` and adds it to
/// `allowedOperations` (which the connector requires), so a node can be configured without typing operation names.
class SoapSection extends ConsumerStatefulWidget {
  const SoapSection({super.key, required this.props, required this.onChanged});

  final Map<String, dynamic> props;
  final ValueChanged<Map<String, dynamic>> onChanged;

  @override
  ConsumerState<SoapSection> createState() => _SoapSectionState();
}

class _SoapSectionState extends ConsumerState<SoapSection> {
  bool _busy = false;
  String? _error;
  List<SoapOperation>? _ops;
  String? _endpoint;

  Future<void> _load() async {
    final request = describeRequest(widget.props);
    if (request == null) {
      setState(() => _error = 'Set the WSDL first (a URL, or the WSDL text).');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final out = await ref.read(repositoryProvider).describeConnector(request);
      if (!mounted) return;
      setState(() {
        _busy = false;
        _ops = parseOperations(out);
        _endpoint = out['endpoint'] as String?;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _ops = null;
        _error = errText(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final current = widget.props['operation'];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 8),
        const PanelSectionLabel('SOAP operations'),
        const SizedBox(height: 4),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            onPressed: _busy ? null : _load,
            icon: _busy
                ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.travel_explore_rounded, size: 16),
            label: const Text('Load operations from WSDL'),
          ),
        ),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(_error!, style: const TextStyle(fontSize: 11, color: AppColors.danger)),
          ),
        if (_endpoint != null && _endpoint!.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text('Endpoint: $_endpoint', style: const TextStyle(fontSize: 11, color: AppColors.textMutedDark)),
          ),
        if (_ops != null)
          for (final op in _ops!)
            ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              leading: Icon(op.name == current ? Icons.radio_button_checked : Icons.radio_button_unchecked, size: 18),
              title: Text(op.name, style: const TextStyle(fontSize: 12.5)),
              subtitle: Text('${op.style} · input: ${op.input.join(', ')}', style: const TextStyle(fontSize: 11), maxLines: 2, overflow: TextOverflow.ellipsis),
              onTap: () => widget.onChanged(withOperation(widget.props, op.name)),
            ),
      ],
    );
  }
}
