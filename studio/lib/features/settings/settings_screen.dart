import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../providers/app_providers.dart';
import '../../design_system/wds.dart';

/// Connection settings. The bearer token lives in memory only; the tenant comes from the token on the server.
class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  late final TextEditingController _url;
  late final TextEditingController _token;
  late final TextEditingController _tenant;
  String? _status;
  bool _ok = false;

  @override
  void initState() {
    super.initState();
    final cfg = ref.read(apiConfigProvider);
    _url = TextEditingController(text: cfg.baseUrl);
    _token = TextEditingController(text: cfg.token ?? '');
    _tenant = TextEditingController(text: cfg.tenant ?? '');
  }

  @override
  void dispose() {
    _url.dispose();
    _token.dispose();
    _tenant.dispose();
    super.dispose();
  }

  Future<void> _apply() async {
    final token = _token.text.trim();
    ref.read(apiConfigProvider.notifier).state = ApiConfig(baseUrl: _url.text.trim(), token: token.isEmpty ? null : token, tenant: _tenant.text.trim().isEmpty ? null : _tenant.text.trim());
    ref.invalidate(nodeCatalogProvider);
    try {
      final n = (await ref.read(repositoryProvider).nodeCatalog()).length;
      setState(() {
        _ok = true;
        _status = 'Connected. The server offers $n element types.';
      });
    } catch (e) {
      setState(() {
        _ok = false;
        _status = errText(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(20),
      child: SingleChildScrollView(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 560),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const WdsPageHeader(title: 'Settings', description: 'Connection to the Wayang API.'),
              const SizedBox(height: 20),
              WdsInspectorSection(
                title: 'API connection',
                description: 'Configure how this Studio instance connects to the Wayang API.',
                child: WdsFieldGroup(
                  children: [
                    TextField(controller: _url, decoration: InputDecoration(labelText: 'API base URL'), textInputAction: TextInputAction.next),
                    TextField(
                      controller: _token,
                      obscureText: true,
                      textInputAction: TextInputAction.done,
                      onSubmitted: (_) => _apply(),
                      decoration: InputDecoration(
                        labelText: 'API key or bearer token (kept in memory only)',
                        helperText: 'Local mode: the operator key. JWT or Numu mode: your bearer token.',
                      ),
                    ),
                    TextField(
                      controller: _tenant,
                      decoration: InputDecoration(
                        labelText: 'Tenant (Numu mode, sent as X-Tenant)',
                        helperText: 'Leave empty in JWT mode: the tenant comes from the token.',
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              WdsButton(label: 'Apply and test', icon: Icons.cloud_done_outlined, onPressed: _apply),
              if (_status != null) ...[
                const SizedBox(height: 12),
                WdsSurface(
                  padding: const EdgeInsets.all(12),
                  child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    WdsBadge(label: _ok ? 'Connected' : 'Connection failed', tone: _ok ? WdsBadgeTone.success : WdsBadgeTone.danger),
                    const SizedBox(width: 10),
                    Expanded(child: Text(_status!, style: Theme.of(context).textTheme.bodySmall)),
                  ]),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
