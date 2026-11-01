import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../models/dmn.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';
import '../../providers/dmn_provider.dart';

class DmnScreen extends ConsumerWidget {
  const DmnScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tablesAsync = ref.watch(decisionTablesProvider);
    final activeId = ref.watch(activeDecisionTableIdProvider);

    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ScreenHeader(
            icon: Icons.table_chart_rounded,
            title: 'DMN Decision Tables',
            subtitle: 'Model business rules as decision tables. Publish a table, then pick it in a Business rule task of a process.',
            actions: [
              tablesAsync.maybeWhen(
                data: (tables) => Row(
                  children: [
                    DropdownButton<String>(
                      value: activeId ?? (tables.isNotEmpty ? tables.first.id : null),
                      underline: const SizedBox.shrink(),
                      dropdownColor: AppColors.darkSurfaceRaised,
                      items: tables.map((t) => DropdownMenuItem(value: t.id, child: Text(t.name))).toList(),
                      onChanged: (v) {
                        if (v != null) ref.read(activeDecisionTableIdProvider.notifier).state = v;
                      },
                    ),
                    PopupMenuButton<String>(
                      tooltip: 'Table actions',
                      icon: const Icon(Icons.more_vert_rounded, size: 18, color: AppColors.textMutedDark),
                      onSelected: (action) => _handleMenuAction(context, ref, action, tables, activeId ?? (tables.isNotEmpty ? tables.first.id : null)),
                      itemBuilder: (context) => const [
                        PopupMenuItem(value: 'new', child: Text('New decision table')),
                        PopupMenuItem(value: 'duplicate', child: Text('Duplicate')),
                        PopupMenuItem(value: 'rename', child: Text('Rename')),
                        PopupMenuItem(value: 'export', child: Text('Export as DMN XML')),
                        PopupMenuDivider(),
                        PopupMenuItem(value: 'delete', child: Text('Delete draft', style: TextStyle(color: AppColors.danger))),
                      ],
                    ),
                  ],
                ),
                orElse: () => const SizedBox.shrink(),
              ),
            ],
          ),
          const SizedBox(height: 18),
          Expanded(
            child: tablesAsync.when(
              loading: () => const LoadingView(),
              error: (e, _) => ErrorView(message: '$e'),
              data: (tables) {
                if (tables.isEmpty) {
                  return const EmptyState(icon: Icons.table_chart_outlined, title: 'No decision tables yet');
                }
                final resolvedId = activeId ?? tables.first.id;
                if (activeId == null) {
                  Future.microtask(() => ref.read(activeDecisionTableIdProvider.notifier).state = resolvedId);
                }
                return _DecisionTableEditor(tableId: resolvedId, allTables: tables);
              },
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _handleMenuAction(BuildContext context, WidgetRef ref, String action, List<DefinitionSummary> tables, String? activeId) async {
    final actions = ref.read(dmnTableActionsProvider);
    final currentSummary = activeId != null ? tables.firstWhere((t) => t.id == activeId, orElse: () => tables.first) : tables.first;
    final current = ref.read(dmnControllerProvider(currentSummary.id)).table;

    switch (action) {
      case 'new':
        final name = await _promptText(context, 'New decision table', 'Untitled decision');
        if (name != null && name.trim().isNotEmpty) await actions.createNew(name.trim());
        break;
      case 'duplicate':
        if (current != null) await actions.duplicate(current);
        break;
      case 'rename':
        if (current == null) break;
        final name = await _promptText(context, 'Rename decision table', current.name);
        if (name != null && name.trim().isNotEmpty) await actions.rename(current, name.trim());
        break;
      case 'delete':
        final confirmed = await showConfirmDialog(
          context,
          title: 'Delete the draft of "${currentSummary.name}"?',
          message: 'Published versions are immutable and stay available to the processes that use them.',
          confirmLabel: 'Delete draft',
          destructive: true,
        );
        if (confirmed) await actions.deleteDraft(currentSummary.id, tables);
        break;
      case 'export':
        if (current != null && context.mounted) _showXmlExportDialog(context, current.toDmnXml());
        break;
    }
  }

  Future<String?> _promptText(BuildContext context, String title, String initial) {
    final ctrl = TextEditingController(text: initial);
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurfaceRaised,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.lg)),
        title: Text(title),
        content: TextField(controller: ctrl, autofocus: true),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          ElevatedButton(onPressed: () => Navigator.pop(ctx, ctrl.text), child: const Text('Save')),
        ],
      ),
    );
  }

  void _showXmlExportDialog(BuildContext context, String xml) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurfaceRaised,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.lg)),
        title: const Text('DMN 1.3 XML'),
        content: SizedBox(
          width: 560,
          height: 420,
          child: SingleChildScrollView(
            child: SelectableText(xml, style: const TextStyle(fontSize: 11, fontFamily: 'monospace')),
          ),
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

class _DecisionTableEditor extends ConsumerWidget {
  const _DecisionTableEditor({required this.tableId, required this.allTables});
  final String tableId;
  final List<DefinitionSummary> allTables;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final editorState = ref.watch(dmnControllerProvider(tableId));
    final controller = ref.read(dmnControllerProvider(tableId).notifier);

    if (editorState.loading) return const LoadingView();
    if (editorState.table == null) {
      return ErrorView(message: editorState.error ?? 'This decision cannot be shown.');
    }
    final table = editorState.table!;
    final overlaps = ref.watch(decisionTableOverlapsProvider(table));

    return LayoutBuilder(builder: (context, constraints) {
      final narrow = constraints.maxWidth < 980;
      final grid = _DecisionGrid(
        table: table,
        controller: controller,
        dirty: editorState.dirty,
        saving: editorState.saving,
        overlaps: overlaps,
        report: editorState.report,
        publishedVersion: editorState.publishedVersion,
        error: editorState.error,
      );
      final test = _TestPanel(table: table);
      if (narrow) {
        return SingleChildScrollView(
          child: Column(children: [grid, const SizedBox(height: 16), test]),
        );
      }
      return Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(flex: 3, child: grid),
          const SizedBox(width: 16),
          SizedBox(width: 340, child: test),
        ],
      );
    });
  }
}

class _DecisionGrid extends StatelessWidget {
  const _DecisionGrid({
    required this.table,
    required this.controller,
    required this.dirty,
    required this.saving,
    required this.overlaps,
    required this.report,
    required this.publishedVersion,
    required this.error,
  });
  final DmnDecisionTable table;
  final DmnController controller;
  final bool dirty;
  final bool saving;
  final List<DmnOverlapWarning> overlaps;
  final ValidationReport? report;
  final int? publishedVersion;
  final String? error;

  @override
  Widget build(BuildContext context) {
    return SurfacePanel(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 30,
                height: 30,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: AppColors.primaryBright.withValues(alpha: 0.16),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: AppColors.primaryBright.withValues(alpha: 0.4)),
                ),
                child: Text(table.hitPolicy.glyph, style: const TextStyle(fontWeight: FontWeight.w800, color: AppColors.primaryBright)),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: DropdownButton<DmnHitPolicy>(
                  value: table.hitPolicy,
                  underline: const SizedBox.shrink(),
                  dropdownColor: AppColors.darkSurfaceRaised,
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.textPrimaryDark),
                  items: DmnHitPolicy.values
                      .map((h) => DropdownMenuItem(value: h, child: Text('${h.glyph} — ${h.label}')))
                      .toList(),
                  onChanged: (v) {
                    if (v != null) controller.setHitPolicy(v);
                  },
                ),
              ),
              if (table.hitPolicy == DmnHitPolicy.collect)
                Padding(
                  padding: const EdgeInsets.only(right: 10),
                  child: DropdownButton<String>(
                    value: table.collectAggregator,
                    hint: const Text('No aggregation', style: TextStyle(fontSize: 12)),
                    underline: const SizedBox.shrink(),
                    dropdownColor: AppColors.darkSurfaceRaised,
                    items: const [
                      DropdownMenuItem(value: 'SUM', child: Text('SUM')),
                      DropdownMenuItem(value: 'MIN', child: Text('MIN')),
                      DropdownMenuItem(value: 'MAX', child: Text('MAX')),
                      DropdownMenuItem(value: 'COUNT', child: Text('COUNT')),
                    ],
                    onChanged: controller.setCollectAggregator,
                  ),
                ),
              if (dirty)
                Padding(
                  padding: const EdgeInsets.only(right: 10),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: const [
                      Icon(Icons.circle, size: 6, color: AppColors.warning),
                      SizedBox(width: 5),
                      Text('Unsaved', style: TextStyle(fontSize: 11, color: AppColors.textMutedDark)),
                    ],
                  ),
                ),
              OutlinedButton.icon(
                onPressed: controller.validate,
                icon: const Icon(Icons.fact_check_outlined, size: 15),
                label: const Text('Validate'),
              ),
              const SizedBox(width: 8),
              OutlinedButton.icon(
                onPressed: saving ? null : () => controller.save(),
                icon: saving
                    ? const SizedBox(width: 13, height: 13, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.save_outlined, size: 15),
                label: const Text('Save draft'),
              ),
              const SizedBox(width: 8),
              ElevatedButton.icon(
                onPressed: saving ? null : () => controller.publish(),
                icon: const Icon(Icons.rocket_launch_outlined, size: 15),
                label: const Text('Publish'),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text(table.hitPolicy.description, style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 6),
          const Text(
            'Entries are expressions: quote text ("gold", output "high"); "-" matches anything; ranges like [100..500] and tests like > 5000 work.',
            style: TextStyle(fontSize: 11, color: AppColors.textMutedDark),
          ),
          if (error != null) ...[
            const SizedBox(height: 10),
            Text(error!, style: const TextStyle(fontSize: 12, color: AppColors.danger)),
          ],
          if (publishedVersion != null) ...[
            const SizedBox(height: 10),
            Text('Published as version $publishedVersion.', style: const TextStyle(fontSize: 12, color: AppColors.success)),
          ],
          if (report != null) ...[
            const SizedBox(height: 10),
            if (report!.items.isEmpty)
              const Text('Valid: no problems found.', style: TextStyle(fontSize: 12, color: AppColors.success))
            else
              ...report!.items.map((i) => Text('${i.isError ? 'Error' : 'Warning'}: ${i.message}',
                  style: TextStyle(fontSize: 12, color: i.isError ? AppColors.danger : AppColors.warning))),
          ],
          if (overlaps.isNotEmpty) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.warning.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(AppRadius.sm),
                border: Border.all(color: AppColors.warning.withValues(alpha: 0.3)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: const [
                      Icon(Icons.warning_amber_rounded, size: 14, color: AppColors.warning),
                      SizedBox(width: 6),
                      Text('Possible rule overlaps (UNIQUE policy)', style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w700, color: AppColors.warning)),
                    ],
                  ),
                  const SizedBox(height: 6),
                  ...overlaps.map((o) => Text(
                        'Rules "${o.ruleIdA}" and "${o.ruleIdB}" may both match on "${o.columnLabel}".',
                        style: const TextStyle(fontSize: 11, color: AppColors.textSecondaryDark),
                      )),
                ],
              ),
            ),
          ],
          const SizedBox(height: 16),
          Expanded(
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: SingleChildScrollView(
                child: DataTable(
                  headingRowColor: WidgetStateProperty.all(AppColors.darkSurfaceRaised),
                  columns: [
                    const DataColumn(label: Text('#')),
                    for (var i = 0; i < table.inputs.length; i++)
                      DataColumn(
                        label: _ColumnHeader(
                          label: table.inputs[i].label,
                          sublabel: table.inputs[i].expression,
                          color: AppColors.info,
                          onDelete: () => controller.removeInputColumn(table.inputs[i].id),
                        ),
                      ),
                    for (var i = 0; i < table.outputs.length; i++)
                      DataColumn(
                        label: _ColumnHeader(
                          label: table.outputs[i].label,
                          sublabel: table.outputs[i].type.label,
                          color: AppColors.success,
                          onDelete: () => controller.removeOutputColumn(table.outputs[i].id),
                        ),
                      ),
                    const DataColumn(label: Text('Annotation')),
                    const DataColumn(label: Text('')),
                  ],
                  rows: [
                    for (var r = 0; r < table.rules.length; r++)
                      DataRow(key: ValueKey(table.rules[r].id), cells: [
                        DataCell(Text('${r + 1}', style: const TextStyle(color: AppColors.textMutedDark))),
                        for (var i = 0; i < table.inputs.length; i++)
                          DataCell(_EntryField(
                            key: ValueKey('in-${table.rules[r].id}-${table.inputs[i].id}'),
                            value: i < table.rules[r].inputEntries.length ? table.rules[r].inputEntries[i] : '-',
                            color: AppColors.info,
                            onChanged: (v) => controller.setInputEntry(table.rules[r].id, i, v),
                          )),
                        for (var i = 0; i < table.outputs.length; i++)
                          DataCell(_EntryField(
                            key: ValueKey('out-${table.rules[r].id}-${table.outputs[i].id}'),
                            value: i < table.rules[r].outputEntries.length ? table.rules[r].outputEntries[i] : '',
                            color: AppColors.success,
                            onChanged: (v) => controller.setOutputEntry(table.rules[r].id, i, v),
                          )),
                        DataCell(_EntryField(
                          key: ValueKey('note-${table.rules[r].id}'),
                          value: table.rules[r].annotation,
                          color: AppColors.textMutedDark,
                          onChanged: (v) => controller.setRuleAnnotation(table.rules[r].id, v),
                        )),
                        DataCell(IconButton(
                          icon: const Icon(Icons.delete_outline_rounded, size: 16, color: AppColors.danger),
                          onPressed: () => controller.removeRule(table.rules[r].id),
                        )),
                      ]),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              OutlinedButton.icon(
                onPressed: controller.addRule,
                icon: const Icon(Icons.add_rounded, size: 15),
                label: const Text('Add rule'),
              ),
              const SizedBox(width: 8),
              OutlinedButton.icon(
                onPressed: () => _promptColumn(context, 'Add input column', controller.addInputColumn),
                icon: const Icon(Icons.view_column_outlined, size: 15),
                label: const Text('Add input'),
              ),
              const SizedBox(width: 8),
              OutlinedButton.icon(
                onPressed: () => _promptColumn(context, 'Add output column', controller.addOutputColumn),
                icon: const Icon(Icons.view_column_outlined, size: 15),
                label: const Text('Add output'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _promptColumn(BuildContext context, String title, void Function(String) onSubmit) async {
    final ctrl = TextEditingController();
    final result = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurfaceRaised,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.lg)),
        title: Text(title),
        content: TextField(controller: ctrl, autofocus: true, decoration: const InputDecoration(hintText: 'Column label')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          ElevatedButton(onPressed: () => Navigator.pop(ctx, ctrl.text), child: const Text('Add')),
        ],
      ),
    );
    if (result != null && result.trim().isNotEmpty) onSubmit(result.trim());
  }
}

class _ColumnHeader extends StatelessWidget {
  const _ColumnHeader({required this.label, required this.sublabel, required this.color, required this.onDelete});
  final String label;
  final String sublabel;
  final Color color;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 150,
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: color)),
                if (sublabel.isNotEmpty)
                  Text(sublabel, style: const TextStyle(fontSize: 9.5, color: AppColors.textMutedDark, fontFamily: 'monospace')),
              ],
            ),
          ),
          InkWell(onTap: onDelete, child: const Icon(Icons.close_rounded, size: 13, color: AppColors.textMutedDark)),
        ],
      ),
    );
  }
}

class _EntryField extends StatelessWidget {
  const _EntryField({super.key, required this.value, required this.color, required this.onChanged});
  final String value;
  final Color color;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 140,
      child: TextFormField(
        initialValue: value,
        style: TextStyle(fontSize: 12, fontFamily: 'monospace', color: color),
        decoration: const InputDecoration(isDense: true, border: InputBorder.none),
        onFieldSubmitted: onChanged,
        onEditingComplete: () {},
        onChanged: onChanged,
      ),
    );
  }
}

class _TestPanel extends ConsumerStatefulWidget {
  const _TestPanel({required this.table});
  final DmnDecisionTable table;

  @override
  ConsumerState<_TestPanel> createState() => _TestPanelState();
}

class _TestPanelState extends ConsumerState<_TestPanel> {
  final Map<String, dynamic> _inputValues = {};
  Map<String, dynamic>? _result;
  String? _error;
  bool _evaluating = false;

  /// The variable path a column reads: its expression when that is a plain (dotted) name, else its label.
  static String _path(DmnColumn col) {
    final e = col.expression.trim();
    return RegExp(r'^[A-Za-z_][A-Za-z0-9_.]*$').hasMatch(e) ? e : col.label;
  }

  /// `applicant.age` -> {applicant: {age: v}} so the server's expression scope finds it.
  Map<String, dynamic> _nested() {
    final root = <String, dynamic>{};
    _inputValues.forEach((path, v) {
      final parts = path.split('.');
      var cur = root;
      for (var i = 0; i < parts.length - 1; i++) {
        final next = cur[parts[i]];
        cur = next is Map<String, dynamic> ? next : (cur[parts[i]] = <String, dynamic>{});
      }
      cur[parts.last] = v;
    });
    return root;
  }

  Future<void> _evaluate() async {
    setState(() {
      _evaluating = true;
      _error = null;
    });
    try {
      final result = await ref.read(repositoryProvider).testTable(widget.table, _nested());
      if (!mounted) return;
      setState(() {
        _result = result;
        _evaluating = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _result = null;
        _error = e is WayangApiException ? e.fullMessage : '$e';
        _evaluating = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final table = widget.table;
    return SurfacePanel(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Test this decision', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 4),
          Text('Runs the table on the server, with the same engine that executes published processes.', style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 16),
          ...table.inputs.map((col) => Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: _buildInputField(col),
              )),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              onPressed: _evaluating ? null : _evaluate,
              icon: _evaluating
                  ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                  : const Icon(Icons.play_arrow_rounded, size: 16),
              label: const Text('Evaluate'),
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(_error!, style: const TextStyle(fontSize: 11.5, color: AppColors.danger)),
          ],
          if (_result != null) ...[
            const SizedBox(height: 16),
            const Divider(height: 1),
            const SizedBox(height: 14),
            const PanelSectionLabel('Output'),
            if (_result!.isEmpty) const StatusPill(label: 'No result', color: AppColors.textMutedDark, dense: true),
            ..._result!.entries.map((e) => KeyValueRow(label: e.key, value: '${e.value}', mono: true)),
          ],
        ],
      ),
    );
  }

  Widget _buildInputField(DmnColumn col) {
    if (col.type == DmnType.boolean) {
      final current = _inputValues[_path(col)] as bool? ?? false;
      return Material(
        color: Colors.transparent,
        child: SwitchListTile(
          dense: true,
          contentPadding: EdgeInsets.zero,
          title: Text(col.label, style: const TextStyle(fontSize: 12.5)),
          value: current,
          onChanged: (v) => setState(() => _inputValues[_path(col)] = v),
        ),
      );
    }
    return TextFormField(
      decoration: InputDecoration(isDense: true, labelText: col.label, helperText: _path(col) == col.label ? null : _path(col)),
      keyboardType: col.type == DmnType.number ? TextInputType.number : TextInputType.text,
      style: const TextStyle(fontSize: 13),
      onChanged: (v) {
        if (col.type == DmnType.number) {
          _inputValues[_path(col)] = num.tryParse(v) ?? v;
        } else {
          _inputValues[_path(col)] = v;
        }
      },
    );
  }
}
