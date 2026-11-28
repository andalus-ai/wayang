import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/project_edit.dart';
import '../../design_system/wds.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';

/// Filters projects by all whitespace-separated terms across name, id and description.
List<Project> filterProjects(List<Project> projects, String query) {
  final terms = query.toLowerCase().split(RegExp(r'\s+')).where((term) => term.isNotEmpty);
  if (terms.isEmpty) return projects;
  return projects.where((project) {
    final searchable = '${project.name} ${project.id} ${project.description ?? ''}'.toLowerCase();
    return terms.every(searchable.contains);
  }).toList();
}

class ProjectsScreen extends ConsumerStatefulWidget {
  const ProjectsScreen({super.key});

  @override
  ConsumerState<ProjectsScreen> createState() => _ProjectsScreenState();
}

class _ProjectsScreenState extends ConsumerState<ProjectsScreen> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final projects = ref.watch(projectsProvider);
    final active = ref.watch(activeProjectIdProvider);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          WdsPageHeader(
            eyebrow: 'Workspace',
            title: 'Projects',
            description: 'Group definitions. Selecting one filters the designers and new definitions join it.',
            actions: [
              WdsButton(
                label: 'New project',
                icon: Icons.add_rounded,
                onPressed: () => _edit(context, ref, null, null),
              ),
            ],
          ),
          const SizedBox(height: 16),
          WdsFilterToolbar(
            search: WdsSearchField(
              hint: 'Search projects by name, ID or description…',
              onChanged: (value) => setState(() => _query = value),
            ),
            actions: [
              WdsButton(
                label: 'All definitions',
                icon: Icons.layers_outlined,
                variant: WdsButtonVariant.secondary,
                onPressed: () => ref.read(activeProjectIdProvider.notifier).state = null,
              ),
            ],
          ),
          const SizedBox(height: 16),
          Expanded(
            child: projects.when(
              loading: () => const LoadingView(),
              error: (error, _) => ErrorView(
                message: errText(error),
                needsSettings: needsSettings(error),
                onRetry: () => ref.invalidate(projectsProvider),
              ),
              data: (items) {
                if (items.isEmpty) {
                  return WdsEmptyState(
                    icon: Icons.folder_open_rounded,
                    title: 'No projects yet',
                    description: 'Create one to group related agents, pipelines and processes.',
                    action: WdsButton(
                      label: 'New project',
                      icon: Icons.add_rounded,
                      onPressed: () => _edit(context, ref, null, null),
                    ),
                  );
                }
                final filtered = filterProjects(items, _query);
                if (filtered.isEmpty) {
                  return const WdsEmptyState(
                    icon: Icons.search_off_rounded,
                    title: 'No matching projects',
                    description: 'Try a different name, project ID or description.',
                  );
                }
                return WdsDataTable<Project>(
                  rows: filtered,
                  rowKey: (project) => project.id,
                  columns: [
                    WdsDataColumn<Project>(
                      label: 'Project',
                      cellBuilder: (context, project) => ConstrainedBox(
                        constraints: const BoxConstraints(minWidth: 140, maxWidth: 260),
                        child: Text(project.name, style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600)),
                      ),
                    ),
                    WdsDataColumn<Project>(
                      label: 'Description',
                      cellBuilder: (context, project) => ConstrainedBox(
                        constraints: const BoxConstraints(minWidth: 180, maxWidth: 360),
                        child: Text(
                          project.description?.isNotEmpty == true ? project.description! : project.id,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                    ),
                    WdsDataColumn<Project>(
                      label: 'Selection',
                      cellBuilder: (context, project) => Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Radio<String?>(
                            value: project.id,
                            groupValue: active,
                            onChanged: (value) => ref.read(activeProjectIdProvider.notifier).state = value,
                          ),
                          if (active == project.id)
                            const WdsBadge(label: 'Active', tone: WdsBadgeTone.success, dense: true),
                        ],
                      ),
                    ),
                  ],
                  rowActionsBuilder: (context, project) => Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      WdsIconButton(
                        tooltip: 'Rename project',
                        icon: Icons.edit_outlined,
                        onPressed: () => _edit(context, ref, project.id, project.name),
                      ),
                      WdsIconButton(
                        tooltip: 'Delete project',
                        icon: Icons.delete_outline_rounded,
                        onPressed: () => _delete(context, ref, project),
                      ),
                    ],
                  ),
                  emptyTitle: 'No projects found',
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _delete(BuildContext context, WidgetRef ref, Project project) async {
    final confirmed = await showConfirmDialog(
      context,
      title: 'Delete "${project.name}"?',
      message: 'Definitions in it are not deleted, only the grouping.',
      confirmLabel: 'Delete',
      destructive: true,
    );
    if (!confirmed) return;
    try {
      await ref.read(repositoryProvider).deleteProject(project.id);
      if (ref.read(activeProjectIdProvider) == project.id) {
        ref.read(activeProjectIdProvider.notifier).state = null;
      }
      ref.invalidate(projectsProvider);
    } catch (error) {
      if (context.mounted) showToast(context, errText(error), isError: true);
    }
  }

  Future<void> _edit(BuildContext context, WidgetRef ref, String? id, String? current) async {
    final others = (ref.read(projectsProvider).valueOrNull ?? const [])
        .where((project) => project.id != id)
        .map((project) => project.name)
        .toList();
    final repo = ref.read(repositoryProvider);

    Future<void> save(String name) async {
      if (id == null) {
        await repo.createProject(name);
      } else {
        await repo.updateProject(id, name);
      }
    }

    final saved = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _ProjectEditDialog(
        id: id,
        current: current,
        others: others,
        onSave: save,
      ),
    );
    if (saved == true) ref.invalidate(projectsProvider);
  }
}

class _ProjectEditDialog extends StatefulWidget {
  const _ProjectEditDialog({
    required this.id,
    required this.current,
    required this.others,
    required this.onSave,
  });

  final String? id;
  final String? current;
  final List<String> others;
  final Future<void> Function(String name) onSave;

  @override
  State<_ProjectEditDialog> createState() => _ProjectEditDialogState();
}

class _ProjectEditDialogState extends State<_ProjectEditDialog> {
  late final TextEditingController _controller;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.current ?? '');
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_busy) return;
    final problem = projectNameProblem(_controller.text, widget.others);
    if (problem != null) {
      setState(() => _error = problem);
      return;
    }

    final name = _controller.text.trim();
    if (widget.id != null && name == widget.current) {
      Navigator.pop(context, false);
      return;
    }

    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.onSave(name);
      if (mounted) Navigator.pop(context, true);
    } catch (exception) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = errText(exception);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.id == null ? 'New project' : 'Rename project'),
      content: TextField(
        controller: _controller,
        autofocus: true,
        maxLength: kMaxProjectName,
        enabled: !_busy,
        onSubmitted: (_) => _submit(),
        decoration: InputDecoration(labelText: 'Name', errorText: _error),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        ElevatedButton(
          onPressed: _busy ? null : _submit,
          child: _busy
              ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}
