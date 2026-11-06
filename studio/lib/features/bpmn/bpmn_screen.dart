import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../models/wayang.dart';
import '../../providers/bpmn_provider.dart';
import 'bpmn_canvas.dart';
import 'bpmn_panels.dart';

String _msg(Object e) => e is WayangApiException ? e.fullMessage : '$e';

class BpmnScreen extends ConsumerWidget {
  const BpmnScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final activeId = ref.watch(activeBpmnDiagramIdProvider);
    final list = ref.watch(bpmnDiagramListProvider);
    final state = ref.watch(bpmnControllerProvider);

    // Open the first process when none is picked yet.
    ref.listen<AsyncValue<List<DefinitionSummary>>>(bpmnDiagramListProvider, (_, next) {
      final items = next.valueOrNull;
      if (items != null && items.isNotEmpty && ref.read(activeBpmnDiagramIdProvider) == null) {
        ref.read(activeBpmnDiagramIdProvider.notifier).state = items.first.id;
      }
    });

    // Apply a run's path once the process it belongs to has loaded, and whenever a new path is chosen.
    ref.listen<BpmnEditorState>(bpmnControllerProvider, (prev, next) {
      if ((prev?.loading ?? true) && !next.loading) {
        ref.read(bpmnControllerProvider.notifier).setHighlight(ref.read(runHighlightProvider));
      }
    });
    ref.listen<Set<String>>(runHighlightProvider, (_, next) {
      if (!ref.read(bpmnControllerProvider).loading) ref.read(bpmnControllerProvider.notifier).setHighlight(next);
    });

    return Column(
      children: [
        const _BpmnToolbar(),
        if (state.errorMessage != null) _Banner(text: state.errorMessage!, isError: true),
        if (state.publishedVersion != null && !state.dirty) _Banner(text: 'Published as version ${state.publishedVersion}.', isError: false),
        Expanded(
          child: list.when(
            loading: () => const LoadingView(label: 'Loading processes…'),
            error: (e, _) => ErrorView(message: _msg(e), onRetry: () => ref.invalidate(bpmnDiagramListProvider)),
            data: (_) {
              if (activeId == null) {
                return EmptyState(
                  icon: Icons.account_tree_rounded,
                  title: 'No process yet',
                  message: 'Create a process or import BPMN 2.0 XML to start designing.',
                );
              }
              if (state.loading) return const LoadingView(label: 'Loading process…');
              return LayoutBuilder(
                builder: (context, constraints) {
                  // Keep the three-pane editor on desktop. On compact screens,
                  // expose the same palette, canvas and inspector as tabs so
                  // the canvas never gets squeezed between fixed-width panels.
                  if (constraints.maxWidth < 920) {
                    return DefaultTabController(
                      length: 3,
                      child: Column(
                        children: [
                          Material(
                            color: AppColors.darkSurface,
                            child: TabBar(
                              isScrollable: false,
                              indicatorColor: AppColors.primaryBright,
                              labelColor: AppColors.primaryBright,
                              unselectedLabelColor: AppColors.textMutedDark,
                              tabs: const [
                                Tab(icon: Icon(Icons.widgets_outlined, size: 16), text: 'Palette'),
                                Tab(icon: Icon(Icons.account_tree_outlined, size: 16), text: 'Canvas'),
                                Tab(icon: Icon(Icons.tune_rounded, size: 16), text: 'Properties'),
                              ],
                            ),
                          ),
                          const Expanded(
                            child: TabBarView(
                              children: [
                                BpmnPalette(),
                                BpmnCanvas(),
                                BpmnInspector(),
                              ],
                            ),
                          ),
                        ],
                      ),
                    );
                  }

                  return const Row(
                    children: [
                      BpmnPalette(),
                      Expanded(child: BpmnCanvas()),
                      BpmnInspector(),
                    ],
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }
}

class _Banner extends StatelessWidget {
  const _Banner({required this.text, required this.isError});
  final String text;
  final bool isError;

  @override
  Widget build(BuildContext context) {
    final color = isError ? AppColors.danger : AppColors.success;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      color: color.withValues(alpha: 0.12),
      child: Text(text, style: TextStyle(fontSize: 12, color: color)),
    );
  }
}

class _BpmnToolbar extends ConsumerWidget {
  const _BpmnToolbar();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(bpmnControllerProvider);
    final controller = ref.read(bpmnControllerProvider.notifier);
    final listAsync = ref.watch(bpmnDiagramListProvider);
    final activeId = ref.watch(activeBpmnDiagramIdProvider);
    final hasOpen = activeId != null && !state.loading;

    return Container(
      height: 56,
      padding: const EdgeInsets.symmetric(horizontal: 16),
      decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: AppColors.darkBorder))),
      child: Row(
        children: [
          Icon(Icons.account_tree_rounded, size: 18, color: AppColors.primaryBright),
          const SizedBox(width: 10),
          listAsync.maybeWhen(
            data: (items) {
              final ids = items.map((d) => d.id).toSet();
              return items.isEmpty
                  ? const Text('Processes', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13.5))
                  : DropdownButton<String>(
                      value: ids.contains(activeId) ? activeId : null,
                      hint: const Text('Pick a process'),
                      underline: const SizedBox.shrink(),
                      style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13.5, color: AppColors.textPrimaryDark),
                      dropdownColor: AppColors.darkSurfaceRaised,
                      items: items.map((d) => DropdownMenuItem(value: d.id, child: Text(d.name))).toList(),
                      onChanged: (v) {
                        if (v != null) ref.read(activeBpmnDiagramIdProvider.notifier).state = v;
                      },
                    );
            },
            orElse: () => const SizedBox.shrink(),
          ),
          PopupMenuButton<String>(
            tooltip: 'Process actions',
            icon: const Icon(Icons.more_vert_rounded, size: 18, color: AppColors.textMutedDark),
            onSelected: (action) => _handleMenuAction(context, ref, action, listAsync.valueOrNull ?? const []),
            itemBuilder: (context) => [
              const PopupMenuItem(value: 'new', child: Text('New process')),
              const PopupMenuItem(value: 'import', child: Text('Import BPMN XML…')),
              if (hasOpen) ...const [
                PopupMenuItem(value: 'duplicate', child: Text('Duplicate')),
                PopupMenuItem(value: 'rename', child: Text('Rename')),
                PopupMenuItem(value: 'export', child: Text('Export saved version as BPMN XML')),
                PopupMenuDivider(),
                PopupMenuItem(value: 'delete', child: Text('Delete draft', style: TextStyle(color: AppColors.danger))),
              ],
            ],
          ),
          if (state.dirty) ...[
            const SizedBox(width: 10),
            Container(width: 6, height: 6, decoration: const BoxDecoration(color: AppColors.warning, shape: BoxShape.circle)),
            const SizedBox(width: 5),
            const Text('Unsaved changes', style: TextStyle(fontSize: 11.5, color: AppColors.textMutedDark)),
          ],
          const SizedBox(width: 18),
          IconButton(
            tooltip: 'Undo',
            icon: Icon(Icons.undo_rounded, size: 18, color: state.canUndo ? AppColors.textSecondaryDark : AppColors.textMutedDark.withValues(alpha: 0.4)),
            onPressed: state.canUndo ? controller.undo : null,
          ),
          IconButton(
            tooltip: 'Redo',
            icon: Icon(Icons.redo_rounded, size: 18, color: state.canRedo ? AppColors.textSecondaryDark : AppColors.textMutedDark.withValues(alpha: 0.4)),
            onPressed: state.canRedo ? controller.redo : null,
          ),
          const Spacer(),
          OutlinedButton.icon(
            onPressed: !hasOpen || state.validating ? null : controller.validate,
            icon: state.validating
                ? const SizedBox(width: 13, height: 13, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.fact_check_outlined, size: 15),
            label: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text('Validate', style: TextStyle(fontSize: 12.5)),
                if (state.errorCount + state.warningCount > 0) ...[
                  const SizedBox(width: 6),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                    decoration: BoxDecoration(color: state.errorCount > 0 ? AppColors.danger : AppColors.warning, borderRadius: BorderRadius.circular(999)),
                    child: Text('${state.errorCount + state.warningCount}', style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: Colors.black)),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 10),
          OutlinedButton.icon(
            onPressed: !hasOpen || state.saving ? null : () => controller.save(),
            icon: const Icon(Icons.save_outlined, size: 16),
            label: const Text('Save draft'),
          ),
          const SizedBox(width: 10),
          ElevatedButton.icon(
            onPressed: !hasOpen || state.saving || state.errorCount > 0 ? null : controller.publish,
            icon: const Icon(Icons.rocket_launch_outlined, size: 16),
            label: const Text('Publish'),
          ),
        ],
      ),
    );
  }

  Future<void> _handleMenuAction(BuildContext context, WidgetRef ref, String action, List<DefinitionSummary> items) async {
    final actions = ref.read(bpmnDiagramActionsProvider);
    final current = ref.read(bpmnControllerProvider).diagram;
    try {
      switch (action) {
        case 'new':
          final name = await _promptText(context, 'New process', 'Untitled process');
          if (name != null && name.trim().isNotEmpty) await actions.createNew(name.trim());
          break;
        case 'import':
          final xml = await _promptXml(context);
          if (xml != null && xml.trim().isNotEmpty) await actions.importXml(xml);
          break;
        case 'duplicate':
          await actions.duplicate(current);
          break;
        case 'rename':
          final name = await _promptText(context, 'Rename process', current.name);
          if (name != null && name.trim().isNotEmpty) await actions.rename(current, name.trim());
          break;
        case 'delete':
          final confirmed = await showConfirmDialog(
            context,
            title: 'Delete the draft of "${current.name}"?',
            message: 'Published versions are immutable and stay available to running instances.',
            confirmLabel: 'Delete draft',
            destructive: true,
          );
          if (confirmed) await actions.deleteDraft(current.id, items);
          break;
        case 'export':
          final xml = await ref.read(bpmnControllerProvider.notifier).exportXml();
          if (context.mounted) _showXmlExportDialog(context, xml);
          break;
      }
    } catch (e) {
      if (context.mounted) showToast(context, _msg(e), isError: true);
    }
  }

  Future<String?> _promptText(BuildContext context, String title, String initial) {
    final ctrl = TextEditingController(text: initial);
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurfaceRaised,
        title: Text(title),
        content: TextField(controller: ctrl, autofocus: true),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          ElevatedButton(onPressed: () => Navigator.pop(ctx, ctrl.text), child: const Text('Save')),
        ],
      ),
    );
  }

  Future<String?> _promptXml(BuildContext context) {
    final ctrl = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurfaceRaised,
        title: const Text('Import BPMN 2.0 XML'),
        content: SizedBox(
          width: 560,
          child: TextField(
            controller: ctrl,
            autofocus: true,
            minLines: 12,
            maxLines: 18,
            style: const TextStyle(fontSize: 11, fontFamily: 'monospace'),
            decoration: const InputDecoration(hintText: 'Paste <definitions …> XML here'),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          ElevatedButton(onPressed: () => Navigator.pop(ctx, ctrl.text), child: const Text('Import as new draft')),
        ],
      ),
    );
  }

  void _showXmlExportDialog(BuildContext context, String xml) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurfaceRaised,
        title: const Text('BPMN 2.0 XML'),
        content: SizedBox(
          width: 560,
          height: 420,
          child: SingleChildScrollView(child: SelectableText(xml, style: const TextStyle(fontSize: 11, fontFamily: 'monospace'))),
        ),
        actions: [
          TextButton.icon(
            onPressed: () {
              Clipboard.setData(ClipboardData(text: xml));
              Navigator.pop(ctx);
            },
            icon: const Icon(Icons.copy_rounded, size: 15),
            label: const Text('Copy to clipboard'),
          ),
          ElevatedButton(onPressed: () => Navigator.pop(ctx), child: const Text('Close')),
        ],
      ),
    );
  }
}
