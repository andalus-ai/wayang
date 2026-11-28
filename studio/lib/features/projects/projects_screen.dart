import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/project_edit.dart';
import '../../providers/app_providers.dart';

class ProjectsScreen extends ConsumerWidget {
  const ProjectsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final projects = ref.watch(projectsProvider);
    final active = ref.watch(activeProjectIdProvider);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ScreenHeader(
            title: 'Projects',
            subtitle: 'Group definitions. Selecting one filters the designers and new definitions join it.',
            icon: Icons.folder_copy_rounded,
            actions: [
              ElevatedButton.icon(
                onPressed: () => _edit(context, ref, null, null),
                icon: const Icon(Icons.add_rounded, size: 18),
                label: const Text('New project'),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Expanded(
            child: projects.when(
              loading: () => const LoadingView(),
              error: (e, _) => ErrorView(message: errText(e), needsSettings: needsSettings(e), onRetry: () => ref.invalidate(projectsProvider)),
              data: (list) => list.isEmpty
                  ? EmptyState(
                      icon: Icons.folder_open_rounded,
                      title: 'No projects yet',
                      message: 'Create one to group related agents, pipelines and processes.',
                      action: ElevatedButton(onPressed: () => _edit(context, ref, null, null), child: const Text('New project')),
                    )
                  : ListView(children: [
                Card(
                  child: RadioListTile<String?>(
                    value: null,
                    groupValue: active,
                    title: const Text('All definitions'),
                    onChanged: (v) => ref.read(activeProjectIdProvider.notifier).state = v,
                  ),
                ),
                for (final p in list)
                  Card(
                    child: RadioListTile<String?>(
                      value: p.id,
                      groupValue: active,
                      title: Text(p.name),
                      subtitle: Text(p.description ?? p.id, style: const TextStyle(fontSize: 11)),
                      secondary: Row(mainAxisSize: MainAxisSize.min, children: [
                        IconButton(icon: const Icon(Icons.edit_outlined, size: 18), onPressed: () => _edit(context, ref, p.id, p.name)),
                        IconButton(
                          icon: const Icon(Icons.delete_outline_rounded, size: 18),
                          onPressed: () async {
                            final ok = await showConfirmDialog(context,
                                title: 'Delete "${p.name}"?',
                                message: 'Definitions in it are not deleted, only the grouping.',
                                confirmLabel: 'Delete',
                                destructive: true);
                            if (!ok) return;
                            try {
                              await ref.read(repositoryProvider).deleteProject(p.id);
                              if (ref.read(activeProjectIdProvider) == p.id) ref.read(activeProjectIdProvider.notifier).state = null;
                              ref.invalidate(projectsProvider);
                            } catch (e) {
                              if (context.mounted) showToast(context, errText(e), isError: true);
                            }
                          },
                        ),
                      ]),
                      onChanged: (v) => ref.read(activeProjectIdProvider.notifier).state = v,
                    ),
                  ),
              ]),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _edit(BuildContext context, WidgetRef ref, String? id, String? current) async {
    final others = (ref.read(projectsProvider).valueOrNull ?? const []).where((p) => p.id != id).map((p) => p.name).toList();
    final repo = ref.read(repositoryProvider);
    final ctrl = TextEditingController(text: current ?? '');
    String? error;
    var busy = false;
    final saved = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setState) {
          Future<void> submit() async {
            if (busy) return;
            final problem = projectNameProblem(ctrl.text, others);
            if (problem != null) return setState(() => error = problem);
            final name = ctrl.text.trim();
            if (id != null && name == current) return Navigator.pop(ctx, false);
            setState(() {
              busy = true;
              error = null;
            });
            try {
              if (id == null) {
                await repo.createProject(name);
              } else {
                await repo.updateProject(id, name);
              }
              if (ctx.mounted) Navigator.pop(ctx, true);
            } catch (e) {
              if (ctx.mounted) {
                setState(() {
                  busy = false;
                  error = errText(e);
                });
              }
            }
          }

          return AlertDialog(
            title: Text(id == null ? 'New project' : 'Rename project'),
            content: TextField(
              controller: ctrl,
              autofocus: true,
              maxLength: kMaxProjectName,
              enabled: !busy,
              onSubmitted: (_) => submit(),
              decoration: InputDecoration(labelText: 'Name', errorText: error),
            ),
            actions: [
              TextButton(onPressed: busy ? null : () => Navigator.pop(ctx, false), child: const Text('Cancel')),
              ElevatedButton(
                onPressed: busy ? null : submit,
                child: busy
                    ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Text('Save'),
              ),
            ],
          );
        },
      ),
    );
    if (saved == true) ref.invalidate(projectsProvider);
  }
}
