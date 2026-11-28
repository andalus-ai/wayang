import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/utils.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/workspace_model.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';
import '../../design_system/wds.dart';

final keyStatusProvider = FutureProvider.autoDispose<KeyStatus?>((ref) async {
  try {
    return await ref.watch(repositoryProvider).secretKeyStatus();
  } catch (_) {
    return null; // not permitted or not available: the screen just omits the banner
  }
});

final secretsProvider = FutureProvider.autoDispose<List<SecretMeta>>((ref) => ref.watch(repositoryProvider).listSecrets());

/// The text typed in the secrets search box.
final secretSearchProvider = StateProvider.autoDispose<String>((ref) => '');

/// Secrets whose name contains every word of [query] (case-insensitive), in the given order.
List<SecretMeta> filterSecrets(List<SecretMeta> all, String query) {
  final words = query.toLowerCase().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).toList();
  if (words.isEmpty) return all;
  return all.where((s) => words.every(s.name.toLowerCase().contains)).toList();
}

/// How a node config refers to a secret: the reference, never the value.
String secretReference(String name) => '\${secret:$name}';

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
          WdsPageHeader(
            title: 'Secrets',
            description: r'Write-only. Use in a node config as ${secret:NAME}.',
            actions: [
              WdsButton(
                label: 'Rotate keys',
                icon: Icons.autorenew_rounded,
                variant: WdsButtonVariant.secondary,
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
                ),
              WdsButton(label: 'Set secret', icon: Icons.add_rounded, onPressed: () => _put(context, ref)),
            ],
          ),
          const SizedBox(height: 12),
          _KeyBanner(onReload: () => _reload(context, ref)),
          const SizedBox(height: 12),
          WdsFilterToolbar(
            search: WdsSearchField(hint: 'Search secrets', onChanged: (value) => ref.read(secretSearchProvider.notifier).state = value),
          ),
          const SizedBox(height: 12),
          Expanded(
            child: secrets.when(
              loading: () => const LoadingView(),
              error: (e, _) => ErrorView(message: errText(e), needsSettings: needsSettings(e), onRetry: () => ref.invalidate(secretsProvider)),
              data: (all) {
                final list = filterSecrets(all, ref.watch(secretSearchProvider));
                if (all.isEmpty) return const EmptyState(icon: Icons.key_off_outlined, title: 'No secrets', message: 'Use Set secret, then refer to it as \${secret:NAME} in a node config.');
                if (list.isEmpty) return const WdsEmptyState(icon: Icons.search_off_rounded, title: 'No secret matches');
                return ListView(children: [
                      for (final s in list)
                        WdsSurface(
                          padding: EdgeInsets.zero,
                          child: ListTile(
                            title: Text(s.name, style: const TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.w600)),
                            subtitle: Text(
                              'v${s.version} · key ${s.keyId}${s.updatedAt == null ? '' : ' · ${formatDateShort(s.updatedAt!)}'}${s.updatedBy == null ? '' : ' by ${s.updatedBy}'}',
                              style: const TextStyle(fontSize: 11),
                            ),
                            trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                              WdsIconButton(
                                tooltip: 'Copy reference \${secret:${s.name}}',
                                icon: Icons.copy_rounded,
                                onPressed: () async {
                                  await Clipboard.setData(ClipboardData(text: secretReference(s.name)));
                                  if (context.mounted) showToast(context, 'Copied ${secretReference(s.name)}');
                                },
                              ),
                              WdsIconButton(
                                tooltip: 'Replace the value',
                                icon: Icons.edit_outlined,
                                onPressed: () => _put(context, ref, fixedName: s.name),
                              ),
                              WdsIconButton(
                              tooltip: 'Delete secret',
                              icon: Icons.delete_outline_rounded,
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
                            ]),
                          ),
                        ),
                    ]);
              },
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

  Future<void> _put(BuildContext context, WidgetRef ref, {String? fixedName}) async {
    final name = TextEditingController(text: fixedName ?? '');
    var saving = false; // a double click must not save twice
    final value = TextEditingController();
    String? error;
    String? nameError;
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: Text(fixedName == null ? 'Set secret' : 'Replace value of $fixedName'),
          content: SizedBox(
            width: 420,
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              TextField(
                controller: name,
                readOnly: fixedName != null,
                autofocus: fixedName == null,
                onChanged: (_) => setState(() => nameError = name.text.isEmpty ? null : secretNameProblem(name.text.trim())),
                decoration: InputDecoration(labelText: 'Name (e.g. CRM_TOKEN)', errorText: nameError),
              ),
              const SizedBox(height: 10),
              TextField(controller: value, obscureText: true, autofocus: fixedName != null, decoration: InputDecoration(labelText: 'Value', errorText: error)),
              const SizedBox(height: 6),
              const Text('The value is encrypted on the server and cannot be read back.', style: TextStyle(fontSize: 11)),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
            ElevatedButton(
              onPressed: saving
                  ? null
                  : () async {
                      final problem = secretNameProblem(name.text.trim());
                      if (problem != null) {
                        setState(() => nameError = problem);
                        return;
                      }
                      setState(() {
                        saving = true;
                        error = null;
                      });
                      try {
                        await ref.read(repositoryProvider).putSecret(name.text.trim(), value.text);
                        ref.invalidate(secretsProvider);
                        ref.invalidate(keyStatusProvider);
                        if (ctx.mounted) Navigator.pop(ctx);
                      } catch (e) {
                        setState(() {
                          saving = false;
                          error = errText(e);
                        });
                      }
                    },
              child: saving
                  ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Text('Save'),
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
    return WdsSurface(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
      child: Row(children: [
        WdsBadge(label: st.needRotation == 0 ? 'Keyring healthy' : 'Rotation needed', tone: st.needRotation == 0 ? WdsBadgeTone.success : WdsBadgeTone.warning, icon: st.needRotation == 0 ? Icons.verified_user_outlined : Icons.warning_amber_rounded),
        const SizedBox(width: 12),
        Expanded(child: Text(st.summary, style: Theme.of(context).textTheme.bodySmall)),
        WdsButton(label: 'Reload keyring', variant: WdsButtonVariant.tertiary, onPressed: onReload),
      ]),
    );
  }
}
