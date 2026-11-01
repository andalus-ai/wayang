import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../providers/app_providers.dart';

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
              const ScreenHeader(title: 'Settings', subtitle: 'Connection to the Wayang API.', icon: Icons.settings_rounded),
              const SizedBox(height: 20),
              TextField(controller: _url, decoration: const InputDecoration(labelText: 'API base URL')),
              const SizedBox(height: 12),
              TextField(
                controller: _token,
                obscureText: true,
                decoration: const InputDecoration(labelText: 'Bearer token (kept in memory only)'),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _tenant,
                decoration: const InputDecoration(
                  labelText: 'Tenant (Numu mode, sent as X-Tenant)',
                  helperText: 'Leave empty in JWT mode: the tenant comes from the token.',
                ),
              ),
              const SizedBox(height: 16),
              ElevatedButton(onPressed: _apply, child: const Text('Apply and test')),
              if (_status != null) ...[
                const SizedBox(height: 12),
                Text(_status!, style: TextStyle(fontSize: 12.5, color: _ok ? Colors.green : Colors.redAccent)),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
