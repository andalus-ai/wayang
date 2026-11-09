import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/openapi_model.dart';
import '../../providers/app_providers.dart';

/// Under an `openapi` service node: read the operations from the node's spec (a link or the text) and pick one. Only the spec and
/// the filters are sent to the server; headers and credentials never are. Picking a mutating operation also allows its method.
class OpenApiSection extends ConsumerStatefulWidget {
  const OpenApiSection({super.key, required this.props, required this.onChanged});

  final Map<String, dynamic> props;
  final ValueChanged<Map<String, dynamic>> onChanged;

  @override
  ConsumerState<OpenApiSection> createState() => _OpenApiSectionState();
}

class _OpenApiSectionState extends ConsumerState<OpenApiSection> {
  bool _busy = false;
  String? _error;
  List<OpenApiOperation>? _ops;
  String? _title;
  List<String> _warnings = const [];

  Future<void> _load() async {
    final request = openApiDescribeRequest(widget.props);
    if (request == null) {
      setState(() => _error = 'Set the specification first (a link, or the document text).');
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
        _ops = parseOpenApiOperations(out);
        _title = out['title'] as String?;
        _warnings = [for (final w in (out['warnings'] as List? ?? const [])) '$w'];
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
        const PanelSectionLabel('API operations'),
        const SizedBox(height: 4),
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            onPressed: _busy ? null : _load,
            icon: _busy
                ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.travel_explore_rounded, size: 16),
            label: const Text('Load operations from spec'),
          ),
        ),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(_error!, style: const TextStyle(fontSize: 11, color: AppColors.danger)),
          ),
        if (_title != null && _title!.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(_title!, style: const TextStyle(fontSize: 11, color: AppColors.textMutedDark)),
          ),
        for (final w in _warnings)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(w, style: const TextStyle(fontSize: 11, color: AppColors.textMutedDark)),
          ),
        if (_ops != null)
          for (final op in _ops!)
            ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              leading: Icon(op.name == current ? Icons.radio_button_checked : Icons.radio_button_unchecked, size: 18),
              title: Text(op.name, style: const TextStyle(fontSize: 12.5)),
              subtitle: Text(
                '${op.method} ${op.path}${op.changesData ? ' · changes data' : ''}'
                '${op.required.isEmpty ? '' : ' · needs: ${op.required.join(', ')}'}',
                style: TextStyle(fontSize: 11, color: op.changesData ? AppColors.danger : null),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              onTap: () => widget.onChanged(withOpenApiOperation(widget.props, op)),
            ),
      ],
    );
  }
}
