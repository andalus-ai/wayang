import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show rootBundle;

import '../../data/pipeline_model.dart';

const _mono = TextStyle(fontFamily: 'monospace', fontSize: 12);

/// Icon names used by assets/pipeline_steps.json.
const stepIcons = <String, IconData>{
  'data_object': Icons.data_object, 'text_snippet': Icons.text_snippet,
  'swap_horiz': Icons.swap_horiz, 'auto_fix_high': Icons.auto_fix_high,
  'filter_alt': Icons.filter_alt, 'mail_outline': Icons.mail_outline,
  'table_rows': Icons.table_rows, 'segment': Icons.segment, 'rule': Icons.rule,
  'sell': Icons.sell, 'link': Icons.link, 'filter_list': Icons.filter_list,
  'call_split': Icons.call_split, 'alt_route': Icons.alt_route,
  'route': Icons.route, 'groups': Icons.groups, 'shuffle': Icons.shuffle,
  'playlist_play': Icons.playlist_play, 'call_merge': Icons.call_merge,
  'merge': Icons.merge, 'inventory': Icons.inventory, 'sort': Icons.sort,
  'view_module': Icons.view_module, 'shield': Icons.shield, 'replay': Icons.replay,
  'electrical_services': Icons.electrical_services, 'speed': Icons.speed,
  'hourglass_empty': Icons.hourglass_empty, 'fingerprint': Icons.fingerprint,
  'loop': Icons.loop, 'receipt_long': Icons.receipt_long,
  'cloud_upload': Icons.cloud_upload,
};

/// Visual projection of the ordered pipeline. The JSON document remains the
/// source of truth; connectors mean sequence, not a new persisted edge schema.
class PipelineBuilder extends StatefulWidget {
  const PipelineBuilder({super.key, required this.controller});
  final TextEditingController controller;

  @override
  State<PipelineBuilder> createState() => _PipelineBuilderState();
}

class _PipelineBuilderState extends State<PipelineBuilder> {
  late final Future<List<StepKind>> _kinds =
      rootBundle.loadString('assets/pipeline_steps.json').then(parseCatalog);
  int? _selectedIndex;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_refresh);
  }

  @override
  void didUpdateWidget(covariant PipelineBuilder oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_refresh);
      widget.controller.addListener(_refresh);
      _selectedIndex = null;
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (!mounted) return;
    final steps = stepsOf(widget.controller.text);
    if (steps == null || (_selectedIndex != null && _selectedIndex! >= steps.length)) {
      _selectedIndex = null;
    }
    setState(() {});
  }

  void _write(List<Map<String, dynamic>> steps, {int? select}) {
    widget.controller.text = encodeSteps(steps, previous: widget.controller.text);
    setState(() => _selectedIndex = select);
  }

  Future<void> _edit(List<Map<String, dynamic>> steps, int index) async {
    final editor = TextEditingController(
      text: const JsonEncoder.withIndent('  ').convert(steps[index]),
    );
    String? error;
    final result = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setLocal) => AlertDialog(
          title: Text('Edit step ' + (index + 1).toString()),
          content: SizedBox(
            width: 560,
            height: 380,
            child: Column(children: [
              Expanded(child: TextField(
                controller: editor, expands: true, maxLines: null, minLines: null,
                textAlignVertical: TextAlignVertical.top, style: _mono,
                decoration: const InputDecoration(
                  border: OutlineInputBorder(), isDense: true,
                  helperText: 'Edit the step properties as JSON.',
                ),
              )),
              if (error != null) Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Align(alignment: Alignment.centerLeft,
                  child: Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
              ),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(dialogContext), child: const Text('Cancel')),
            FilledButton(onPressed: () {
              try {
                Navigator.pop(dialogContext, parseStep(editor.text));
              } on FormatException catch (e) {
                setLocal(() => error = e.message);
              }
            }, child: const Text('Apply')),
          ],
        ),
      ),
    );
    editor.dispose();
    if (!mounted || result == null) return;
    final next = List<Map<String, dynamic>>.of(steps)..[index] = result;
    _write(next, select: index);
  }

  Widget _addButton(List<StepKind> kinds, List<Map<String, dynamic>> steps) {
    return PopupMenuButton<StepKind>(
      tooltip: 'Add a step from the catalog',
      enabled: kinds.isNotEmpty,
      onSelected: (kind) => _write([...steps, kind.instantiate()], select: steps.length),
      itemBuilder: (_) {
        final entries = <PopupMenuEntry<StepKind>>[];
        String? group;
        for (final kind in kinds) {
          if (kind.group != group) {
            group = kind.group;
            entries.add(PopupMenuItem<StepKind>(
              enabled: false, height: 30,
              child: Text(kind.group.toUpperCase(),
                style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700)),
            ));
          }
          entries.add(PopupMenuItem<StepKind>(value: kind, child: ListTile(
            dense: true, contentPadding: EdgeInsets.zero,
            leading: Icon(stepIcons[kind.icon] ?? Icons.extension, size: 19),
            title: Text(kind.label),
            subtitle: Text(kind.description, maxLines: 1, overflow: TextOverflow.ellipsis),
          )));
        }
        return entries;
      },
      child: const Chip(avatar: Icon(Icons.add_rounded, size: 17), label: Text('Add step')),
    );
  }

  Widget _node(int index, Map<String, dynamic> step, StepKind? kind) {
    final colors = Theme.of(context).colorScheme;
    final selected = _selectedIndex == index;
    final summary = describeStep(step);
    return SizedBox(width: 224, child: Material(
      color: selected ? colors.secondaryContainer : colors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(color: selected ? colors.primary : colors.outlineVariant,
          width: selected ? 2 : 1),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => setState(() => _selectedIndex = index),
        child: Padding(padding: const EdgeInsets.all(14), child: Column(
          crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min,
          children: [
            Row(children: [
              CircleAvatar(radius: 13,
                backgroundColor: selected ? colors.primary : colors.surfaceContainerHighest,
                foregroundColor: selected ? colors.onPrimary : colors.onSurface,
                child: Text((index + 1).toString(), style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700))),
              const SizedBox(width: 8),
              Icon(stepIcons[kind?.icon] ?? Icons.extension, size: 18, color: colors.primary),
              const Spacer(),
              if (summary.isNotEmpty) Icon(Icons.account_tree_outlined, size: 16, color: colors.onSurfaceVariant),
            ]),
            const SizedBox(height: 12),
            Text(kind?.label ?? (step['type']?.toString() ?? 'Unknown step'),
              maxLines: 2, overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: 5),
            Text(summary.isEmpty ? 'No summary properties' : summary,
              maxLines: 2, overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 11, color: colors.onSurfaceVariant)),
            const SizedBox(height: 10),
            Row(children: [
              Icon(Icons.circle, size: 7, color: colors.tertiary),
              const SizedBox(width: 5),
              Text(kind?.group ?? 'Custom step',
                style: TextStyle(fontSize: 10, color: colors.onSurfaceVariant)),
              const Spacer(),
              Text('STEP', style: TextStyle(fontSize: 9, letterSpacing: 1,
                color: colors.onSurfaceVariant, fontWeight: FontWeight.w700)),
            ]),
          ],
        )),
      ),
    ));
  }

  Widget _connector(bool vertical) {
    final color = Theme.of(context).colorScheme.outlineVariant;
    if (vertical) return SizedBox(height: 34, child: Center(child: Column(children: [
      Container(width: 2, height: 19, color: color),
      Icon(Icons.arrow_downward, size: 14, color: color),
    ])));
    return SizedBox(width: 42, child: Row(children: [
      Expanded(child: Container(height: 2, color: color)),
      Icon(Icons.arrow_forward, size: 15, color: color),
    ]));
  }

  Widget _inspector(List<Map<String, dynamic>> steps, Map<String, StepKind> byType) {
    final index = _selectedIndex;
    if (index == null || index < 0 || index >= steps.length) {
      return Card(margin: EdgeInsets.zero, child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(children: [
          Icon(Icons.touch_app_outlined, color: Theme.of(context).colorScheme.onSurfaceVariant),
          const SizedBox(width: 10),
          const Expanded(child: Text('Select a node to inspect its properties and actions.',
            style: TextStyle(fontSize: 12))),
        ]),
      ));
    }
    final step = steps[index];
    final kind = byType[step['type']];
    final colors = Theme.of(context).colorScheme;
    return Card(margin: EdgeInsets.zero, child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(stepIcons[kind?.icon] ?? Icons.extension, color: colors.primary),
          const SizedBox(width: 8),
          Expanded(child: Text('Step ' + (index + 1).toString() + ' · ' +
            (kind?.label ?? step['type'].toString()),
            style: const TextStyle(fontWeight: FontWeight.w700))),
          IconButton(tooltip: 'Move up',
            onPressed: index == 0 ? null : () => _write(moved(steps, index, -1), select: index - 1),
            icon: const Icon(Icons.arrow_upward)),
          IconButton(tooltip: 'Move down',
            onPressed: index == steps.length - 1 ? null : () => _write(moved(steps, index, 1), select: index + 1),
            icon: const Icon(Icons.arrow_downward)),
          IconButton(tooltip: 'Edit step JSON', onPressed: () => _edit(steps, index),
            icon: const Icon(Icons.edit_outlined)),
          IconButton(tooltip: 'Remove step',
            onPressed: () => _write(List<Map<String, dynamic>>.of(steps)..removeAt(index),
              select: steps.length <= 1 ? null : (index >= steps.length - 1 ? index - 1 : index)),
            icon: Icon(Icons.delete_outline, color: colors.error)),
        ]),
        const SizedBox(height: 6),
        Text(kind?.description ?? 'Custom pipeline step type.',
          style: TextStyle(color: colors.onSurfaceVariant, fontSize: 12)),
        const SizedBox(height: 12),
        Container(width: double.infinity, padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: colors.surfaceContainerHighest.withOpacity(0.45),
            borderRadius: BorderRadius.circular(8)),
          child: SelectableText(const JsonEncoder.withIndent('  ').convert(step), style: _mono)),
      ]),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final steps = stepsOf(widget.controller.text);
    if (steps == null) return const Center(child: Padding(
      padding: EdgeInsets.all(16),
      child: Text('This pipeline is not a valid JSON object with a "steps" array. '
        'Switch to JSON mode to correct it; the graph will return when the document is valid.',
        textAlign: TextAlign.center),
    ));

    return FutureBuilder<List<StepKind>>(
      future: _kinds,
      builder: (context, snapshot) {
        if (snapshot.hasError) return Center(child: Text('Could not load the step catalog: ' + snapshot.error.toString()));
        if (!snapshot.hasData) return const Center(child: CircularProgressIndicator());
        final kinds = snapshot.data!;
        final byType = {for (final kind in kinds) kind.type: kind};
        return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Text(steps.length.toString() + (steps.length == 1 ? ' step' : ' steps'),
              style: Theme.of(context).textTheme.labelLarge),
            const Spacer(),
            _addButton(kinds, steps),
          ]),
          const SizedBox(height: 10),
          Expanded(child: steps.isEmpty
            ? Center(child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 420),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Icon(Icons.account_tree_outlined, size: 42,
                    color: Theme.of(context).colorScheme.onSurfaceVariant),
                  const SizedBox(height: 12),
                  const Text('Your pipeline canvas is empty',
                    style: TextStyle(fontWeight: FontWeight.w700)),
                  const SizedBox(height: 5),
                  const Text('Add the first integration step to begin composing the pipeline.',
                    textAlign: TextAlign.center),
                  const SizedBox(height: 12),
                  _addButton(kinds, steps),
                ]),
              ))
            : LayoutBuilder(builder: (context, constraints) {
                final vertical = constraints.maxWidth < 620;
                final nodes = <Widget>[];
                for (var i = 0; i < steps.length; i++) {
                  nodes.add(_node(i, steps[i], byType[steps[i]['type']]));
                  if (i < steps.length - 1) nodes.add(_connector(vertical));
                }
                if (vertical) return ListView(
                  padding: const EdgeInsets.fromLTRB(2, 2, 2, 12),
                  children: nodes.map((node) => Center(child: node)).toList());
                return SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.fromLTRB(2, 2, 2, 12),
                  child: Center(child: Row(mainAxisSize: MainAxisSize.min, children: nodes)));
              })),
          const SizedBox(height: 10),
          _inspector(steps, byType),
        ]);
      },
    );
  }
}
