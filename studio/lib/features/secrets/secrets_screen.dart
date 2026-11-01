import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/workspace_model.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';

final keyStatusProvider = FutureProvider.autoDispose<KeyStatus?>((ref) async {
  try {
    return await ref.watch(repositoryProvider).secretKeyStatus();
  } catch (_) {
    return null; // not permitted or not available: the screen just omits the banner
  }
});

final secretsProvider = FutureProvider.autoDispose<List<SecretMeta>>((ref) => ref.watch(repositoryProvider).listSecrets());

/// Secrets are write-only: the server never returns a value, so neither does this screen. Reference one from a
/// config as `${secret:NAME}`.
class SecretsScreen extends ConsumerWidget {
  const SecretsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final secrets = ref.watch(secretsProvider);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ScreenHeader(
            title: 'Secrets',
            subtitle: r'Write-only. Use in a node config as ${secret:NAME}.',
            icon: Icons.key_rounded,
            actions: [
              OutlinedButton.icon(
                onPressed: () async {
                  try {
                    final n = await ref.read(repositoryProvider).rotateSecrets();
                    ref.invalidate(secretsProvider);
                    ref.invalidate(keyStatusProvider);
                    if (context.mounted) showToast(context, 'Re-sealed $n secret(s) under the active key.');
                  } catch (e) {
                    if (context.mounted) showToast(context, errText(e), isError: true);
                  }
                },
                icon: const Icon(Icons.autorenew_rounded, size: 16),
                label: const Text('Rotate keys'),
              ),
              const SizedBox(width: 8),
              ElevatedButton.icon(
                onPressed: () => _put(context, ref),
                icon: const Icon(Icons.add_rounded, size: 18),
                label: const Text('Set secret'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          _KeyBanner(onReload: () => _reload(context, ref)),
          const SizedBox(height: 12),
          Expanded(
            child: secrets.when(
              loading: () => const LoadingView(),
              error: (e, _) => ErrorView(message: errText(e), onRetry: () => ref.invalidate(secretsProvider)),
              data: (list) => list.isEmpty
                  ? const EmptyState(icon: Icons.key_off_outlined, title: 'No secrets')
                  : ListView(children: [
                      for (final s in list)
                        Card(
                          child: ListTile(
                            title: Text(s.name, style: const TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.w600)),
                            subtitle: Text(
                              'v${s.version} · key ${s.keyId}${s.updatedAt == null ? '' : ' · ${formatDateShort(s.updatedAt!)}'}${s.updatedBy == null ? '' : ' by ${s.updatedBy}'}',
                              style: const TextStyle(fontSize: 11),
                            ),
                            trailing: IconButton(
                              icon: const Icon(Icons.delete_outline_rounded, size: 18),
                              onPressed: () async {
                                final ok = await showConfirmDialog(context,
                                    title: 'Delete ${s.name}?',
                                    message: 'Nodes referencing it will fail until it is set again.',
                                    confirmLabel: 'Delete',
                                    destructive: true);
                                if (!ok) return;
                                try {
                                  await ref.read(repositoryProvider).deleteSecret(s.name);
                                  ref.invalidate(secretsProvider);
                                } catch (e) {
                                  if (context.mounted) showToast(context, errText(e), isError: true);
                                }
                              },
                            ),
                          ),
                        ),
                    ]),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _reload(BuildContext context, WidgetRef ref) async {
    try {
      final st = await ref.read(repositoryProvider).reloadSecretKeys();
      ref.invalidate(keyStatusProvider);
      if (context.mounted) showToast(context, 'Keyring reloaded. ${st.summary}');
    } catch (e) {
      if (context.mounted) showToast(context, errText(e), isError: true);
    }
  }

  Future<void> _put(BuildContext context, WidgetRef ref) async {
    final name = TextEditingController();
    final value = TextEditingController();
    String? error;
    String? nameError;
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: const Text('Set secret'),
          content: SizedBox(
            width: 420,
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              TextField(
                controller: name,
                autofocus: true,
                onChanged: (_) => setState(() => nameError = name.text.isEmpty ? null : secretNameProblem(name.text.trim())),
                decoration: InputDecoration(labelText: 'Name (e.g. CRM_TOKEN)', errorText: nameError),
              ),
              const SizedBox(height: 10),
              TextField(controller: value, obscureText: true, decoration: InputDecoration(labelText: 'Value', errorText: error)),
              const SizedBox(height: 6),
              const Text('The value is encrypted on the server and cannot be read back.', style: TextStyle(fontSize: 11)),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
            ElevatedButton(
              onPressed: () async {
                final problem = secretNameProblem(name.text.trim());
                if (problem != null) {
                  setState(() => nameError = problem);
                  return;
                }
                try {
                  await ref.read(repositoryProvider).putSecret(name.text.trim(), value.text);
                  ref.invalidate(secretsProvider);
                  ref.invalidate(keyStatusProvider);
                  if (ctx.mounted) Navigator.pop(ctx);
                } catch (e) {
                  setState(() => error = errText(e));
                }
              },
              child: const Text('Save'),
            ),
          ],
        ),
      ),
    );
  }
}

/// Where the master keys stand, with a button to pick up a rotated keyring without restarting the server.
class _KeyBanner extends ConsumerWidget {
  const _KeyBanner({required this.onReload});

  final VoidCallback onReload;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final st = ref.watch(keyStatusProvider).valueOrNull;
    if (st == null) return const SizedBox.shrink();
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        child: Row(children: [
          Icon(st.needRotation == 0 ? Icons.verified_user_outlined : Icons.warning_amber_rounded, size: 18),
          const SizedBox(width: 10),
          Expanded(child: Text(st.summary, style: const TextStyle(fontSize: 12))),
          TextButton(onPressed: onReload, child: const Text('Reload keyring')),
        ]),
      ),
    );
  }
}
