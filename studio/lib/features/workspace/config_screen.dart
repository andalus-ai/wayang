import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/workspace_model.dart';
import '../../providers/app_providers.dart';

/// The scope being edited: null is the whole tenant, otherwise a project id.
final configScopeProvider = StateProvider.autoDispose<String?>((ref) => null);

final configProvider = FutureProvider.autoDispose<List<ConfigItem>>((ref) {
  final scope = ref.watch(configScopeProvider);
  return ref.watch(repositoryProvider).listConfig(project: scope);
});

/// Plain settings that differ per environment, used in node configs as `${config:NAME}`. Values are shown (they are not
/// secret); credentials belong in Secrets, and the server refuses names that look like one.
class ConfigScreen extends ConsumerWidget {
  const ConfigScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scope = ref.watch(configScopeProvider);
    final items = ref.watch(configProvider);
    final projects = ref.watch(projectsProvider);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ScreenHeader(
            title: 'Configuration',
            subtitle: r'Settings per environment. Use in a node config as ${config:NAME}; a project value wins for that project.',
            icon: Icons.tune_rounded,
            actions: [
              ElevatedButton.icon(
                onPressed: () => _edit(context, ref, scope, null),
                icon: const Icon(Icons.add_rounded, size: 18),
                label: const Text('Add setting'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Row(children: [
            const Text('Scope  '),
            projects.maybeWhen(
              data: (list) => DropdownButton<String?>(
                value: list.any((p) => p.id == scope) ? scope : null,
                items: [
                  const DropdownMenuItem<String?>(value: null, child: Text('Whole tenant')),
                  for (final p in list) DropdownMenuItem<String?>(value: p.id, child: Text('Project: ${p.name}')),
                ],
                onChanged: (v) => ref.read(configScopeProvider.notifier).state = v,
              ),
              orElse: () => const SizedBox.shrink(),
            ),
          ]),
          const SizedBox(height: 8),
          Expanded(
            child: items.when(
              loading: () => const LoadingView(),
              error: (e, _) => ErrorView(message: errText(e), onRetry: () => ref.invalidate(configProvider)),
              data: (list) => list.isEmpty
                  ? const EmptyState(icon: Icons.tune_rounded, title: 'No settings in this scope')
                  : ListView(children: [
                      for (final c in list)
                        Card(
                          child: ListTile(
                            title: Text(c.key, style: const TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.w600)),
                            subtitle: Text(c.value, maxLines: 3, overflow: TextOverflow.ellipsis, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
                            onTap: () => _edit(context, ref, scope, c),
                            trailing: IconButton(
                              icon: const Icon(Icons.delete_outline_rounded, size: 18),
                              onPressed: () async {
                                final ok = await showConfirmDialog(context,
                                    title: 'Delete ${c.key}?',
                                    message: 'Nodes using it fail until it is set again.',
                                    confirmLabel: 'Delete',
                                    destructive: true);
                                if (!ok) return;
                                try {
                                  await ref.read(repositoryProvider).deleteConfig(c.key, project: scope);
                                  ref.invalidate(configProvider);
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

  Future<void> _edit(BuildContext context, WidgetRef ref, String? scope, ConfigItem? existing) async {
    final key = TextEditingController(text: existing?.key ?? '');
    final value = TextEditingController(text: existing?.value ?? '');
    String? error;
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          title: Text(existing == null ? 'Add setting' : 'Edit ${existing.key}'),
          content: SizedBox(
            width: 460,
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              TextField(controller: key, enabled: existing == null, autofocus: existing == null, decoration: const InputDecoration(labelText: 'Name (e.g. BASE_URL)')),
              const SizedBox(height: 10),
              TextField(controller: value, minLines: 1, maxLines: 6, decoration: InputDecoration(labelText: 'Value', errorText: error)),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
            ElevatedButton(
              onPressed: () async {
                final problem = configProblem(key.text.trim(), value.text);
                if (problem != null) {
                  setState(() => error = problem);
                  return;
                }
                try {
                  await ref.read(repositoryProvider).putConfig(key.text.trim(), value.text, project: scope);
                  ref.invalidate(configProvider);
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
