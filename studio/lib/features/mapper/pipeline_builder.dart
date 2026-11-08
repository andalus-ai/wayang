import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show rootBundle;

import '../../data/pipeline_model.dart';
import '../../data/step_address.dart';

const _mono = TextStyle(fontFamily: 'monospace', fontSize: 12);

class _GraphLevel {
  const _GraphLevel(this.address, this.label);
  final StepAddress address;
  final String label;
}

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
  StepAddress? _selectedNestedPath;
  final List<_GraphLevel> _graphStack = [];
  StepAddress get _activeSequenceAddress => _graphStack.isEmpty ? StepAddress([const StepAddressSegment.key('steps')]) : _graphStack.last.address;
  String get _activeSequenceLabel => _graphStack.isEmpty ? 'Pipeline' : _graphStack.last.label;
  List<Map<String, dynamic>> _activeSteps(Map<String, dynamic> document) => stepSequenceAtAddress(document, _activeSequenceAddress) ?? const <Map<String, dynamic>>[];
  void _openGraph(StepAddress address, String label) => setState(() { _graphStack.add(_GraphLevel(address, label)); _selectedNestedPath = null; _selectedIndex = null; });
  void _navigateGraph(int depth) => setState(() { if (depth < 0) { _graphStack.clear(); } else if (depth < _graphStack.length) { _graphStack.removeRange(depth + 1, _graphStack.length); } _selectedNestedPath = null; _selectedIndex = null; });

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
      _selectedNestedPath = null;
      _graphStack.clear();
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (!mounted) return;
    final document = _document();
    final steps = document == null ? null : _activeSteps(document);
    if (steps == null || (_selectedIndex != null && _selectedIndex! >= steps.length)) {
      _selectedIndex = null;
      _selectedNestedPath = null;
    } else if (_selectedNestedPath != null) {
      final document = _document();
      if (document == null || stepAtAddress(document, _selectedNestedPath!) == null) {
        _selectedNestedPath = null;
      }
    }
    while (_graphStack.isNotEmpty && (document == null || stepSequenceAtAddress(document, _activeSequenceAddress) == null)) {
      _graphStack.removeLast();
    }
    }
    setState(() {});
  }

  void _write(List<Map<String, dynamic>> steps, {int? select}) {
    widget.controller.text = encodeSteps(steps, previous: widget.controller.text);
    setState(() { _selectedIndex = select; _selectedNestedPath = null; });
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

  Map<String, dynamic>? _document() {
    try { final value = jsonDecode(widget.controller.text); return value is Map ? Map<String, dynamic>.from(value) : null; }
    on FormatException { return null; }
  }

  void _writeNested(Map<String, dynamic> document, StepAddress path) {
    widget.controller.text = const JsonEncoder.withIndent('  ').convert(document);
    setState(() { _selectedNestedPath = path; _selectedIndex = path.segments.isNotEmpty && path.segments.last.isIndex ? path.segments.last.index : null; });
  }

  Future<void> _editNested(StepAddress path, Map<String, dynamic> step) async {
    final editor = TextEditingController(text: const JsonEncoder.withIndent('  ').convert(step));
    String? error;
    final result = await showDialog<Map<String, dynamic>>(context: context, builder: (dialogContext) => StatefulBuilder(
      builder: (context, setLocal) => AlertDialog(title: const Text('Edit nested step'),
        content: SizedBox(width: 560, height: 380, child: Column(children: [
          Expanded(child: TextField(controller: editor, expands: true, maxLines: null, minLines: null,
            textAlignVertical: TextAlignVertical.top, style: _mono,
            decoration: const InputDecoration(border: OutlineInputBorder(), helperText: 'Edit nested step JSON.'))),
          if (error != null) Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ])),
        actions: [TextButton(onPressed: () => Navigator.pop(dialogContext), child: const Text('Cancel')),
          FilledButton(onPressed: () { try { Navigator.pop(dialogContext, parseStep(editor.text)); }
            on FormatException catch (e) { setLocal(() => error = e.message); }
          }, child: const Text('Apply'))],
      ),
    ));
    editor.dispose();
    if (!mounted || result == null) return;
    final document = _document();
    if (document == null) return;
    final updated = replaceStepAtAddress(document, path, result);
    if (updated != null) _writeNested(updated, path);
  }

  Widget _nestedInspector(List<StepKind> kinds) {
    final path = _selectedNestedPath;
    if (path == null || path.segments.isEmpty || !path.segments.last.isIndex) return const SizedBox.shrink();
    final document = _document();
    final step = document == null ? null : stepAtAddress(document, path);
    if (document == null || step == null) return const SizedBox.shrink();
    final childIndex = path.segments.last.index!;
    final sequenceAddress = StepAddress(path.segments.take(path.segments.length - 1));
    final siblings = stepSequenceAtAddress(document, sequenceAddress) ?? const <Map<String, dynamic>>[];
    final colors = Theme.of(context).colorScheme;
    return Card(margin: EdgeInsets.zero, child: Padding(padding: const EdgeInsets.all(16), child: Column(
      crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [Icon(Icons.subdirectory_arrow_right, color: colors.primary), const SizedBox(width: 8),
          Expanded(child: Text('Nested step · ${path.toString()}',
            style: const TextStyle(fontWeight: FontWeight.w700))),
          TextButton.icon(onPressed: () => _navigateGraph(_graphStack.length - 2),
            icon: const Icon(Icons.arrow_upward, size: 15), label: const Text('Parent graph')),
          IconButton(tooltip: 'Move nested step up', onPressed: childIndex == 0 ? null : () {
            final updated = moveStepAtAddress(document, path, -1);
            if (updated != null) _writeNested(updated, StepAddress(path.segments.take(path.segments.length - 1).followedBy([StepAddressSegment.index(childIndex - 1)])));
          }, icon: const Icon(Icons.arrow_upward)),
          IconButton(tooltip: 'Move nested step down', onPressed: childIndex >= siblings.length - 1 ? null : () {
            final updated = moveStepAtAddress(document, path, 1);
            if (updated != null) _writeNested(updated, StepAddress(path.segments.take(path.segments.length - 1).followedBy([StepAddressSegment.index(childIndex + 1)])));
          }, icon: const Icon(Icons.arrow_downward)),
          IconButton(tooltip: 'Edit nested step JSON', onPressed: () => _editNested(path, step), icon: const Icon(Icons.edit_outlined)),
          IconButton(tooltip: 'Remove nested step', onPressed: () {
            final updated = removeStepAtAddress(document, path);
            if (updated == null) return;
            widget.controller.text = const JsonEncoder.withIndent('  ').convert(updated);
            final remaining = stepSequenceAtAddress(updated, sequenceAddress) ?? const <Map<String, dynamic>>[];
            setState(() => _selectedNestedPath = remaining.isEmpty ? null : StepAddress(sequenceAddress.segments.followedBy([StepAddressSegment.index(childIndex >= remaining.length ? remaining.length - 1 : childIndex)])));
          }, icon: Icon(Icons.delete_outline, color: colors.error)),
        ]),
        const SizedBox(height: 8),
          PopupMenuButton<StepKind>(
            tooltip: 'Add a nested step',
            onSelected: (kind) {
              final updated = insertStepAtAddress(document, sequenceAddress, siblings.length, kind.instantiate());
              if (updated != null) _writeNested(updated, sequenceAddress.at(siblings.length));
            },
            itemBuilder: (_) => [
              for (final kind in kinds) PopupMenuItem<StepKind>(
                value: kind, child: Text(kind.label)),
            ],
            child: const Chip(avatar: Icon(Icons.add, size: 16), label: Text('Add nested step')),
          ),
        const SizedBox(height: 8),
        Container(width: double.infinity, padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(color: colors.surfaceContainerHighest.withOpacity(0.45), borderRadius: BorderRadius.circular(8)),
          child: SelectableText(const JsonEncoder.withIndent('  ').convert(step), style: _mono)),
      ],
    )));
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

  Widget _node(int index, Map<String, dynamic> step, Map<String, StepKind> byType, List<StepKind> kinds, StepAddress sequenceAddress) {
    final colors = Theme.of(context).colorScheme;
    final kind = byType[step['type']];
    final selected = _selectedIndex == index;
    final summary = describeStep(step);
    final stepAddress = sequenceAddress.at(index);
    final nestedGroups = nestedStepGroups(step, parentAddress: stepAddress);
    final routeInfo = routeSummaries(step);
    return SizedBox(width: nestedGroups.isEmpty && routeInfo.isEmpty ? 224 : 300, child: Material(
      color: selected ? colors.secondaryContainer : colors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(color: selected ? colors.primary : colors.outlineVariant,
          width: selected ? 2 : 1),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => setState(() { _selectedIndex = index; _selectedNestedPath = _graphStack.isEmpty ? null : stepAddress; }),
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
            if (nestedGroups.isNotEmpty) ...[
              const SizedBox(height: 10),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(9),
                decoration: BoxDecoration(
                  color: colors.surfaceContainerHighest.withOpacity(0.38),
                  borderRadius: BorderRadius.circular(9),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(children: [
                      Icon(Icons.account_tree_outlined, size: 13,
                          color: colors.primary),
                      const SizedBox(width: 5),
                      Text('NESTED EXECUTION',
                        style: TextStyle(fontSize: 9, letterSpacing: 0.8,
                          fontWeight: FontWeight.w700,
                          color: colors.onSurfaceVariant)),
                    ]),
                    const SizedBox(height: 7),
                    for (final group in nestedGroups) ...[
                      Row(children: [
                        Expanded(child: Padding(
                          padding: const EdgeInsets.only(top: 4, bottom: 3),
                          child: Text(group.label,
                            style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700)),
                        )),
                        if (group.address != null) ...[
                          IconButton(tooltip: 'Open nested graph', visualDensity: VisualDensity.compact, padding: EdgeInsets.zero, constraints: const BoxConstraints(minWidth: 30, minHeight: 30), onPressed: () => _openGraph(group.address!, group.label), icon: const Icon(Icons.open_in_full, size: 16)),
                          PopupMenuButton<StepKind>(
                            tooltip: 'Add nested step',
                            padding: EdgeInsets.zero, iconSize: 17,
                            onSelected: (kind) {
                              final document = _document();
                              if (document == null) return;
                              final current = stepSequenceAtAddress(document, group.address!) ?? const <Map<String, dynamic>>[];
                              final updated = insertStepAtAddress(document, group.address!, current.length, kind.instantiate());
                              if (updated != null) _writeNested(updated, group.address!.at(current.length));
                            },
                            itemBuilder: (_) => [for (final kind in kinds) PopupMenuItem<StepKind>(value: kind, child: Text(kind.label))],
                            icon: const Icon(Icons.add_circle_outline),
                          ),
                        ],
                      ]),
                      if (group.condition != null)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 4),
                          child: Text('when ${group.condition}',
                            maxLines: 2, overflow: TextOverflow.ellipsis,
                            style: TextStyle(fontSize: 10,
                              color: colors.onSurfaceVariant)),
                        ),
                      Wrap(
                        spacing: 4,
                        runSpacing: 4,
                        children: [
                          for (var childIndex = 0;
                              childIndex < group.steps.length;
                              childIndex++)
                            InputChip(
                              visualDensity: VisualDensity.compact,
                              padding: EdgeInsets.zero,
                              label: Text(
                                byType[group.steps[childIndex]['type']]?.label ??
                                  (group.steps[childIndex]['type']?.toString() ?? 'Custom'),
                                style: const TextStyle(fontSize: 10)),
                              avatar: Icon(
                                stepIcons[byType[group.steps[childIndex]['type']]?.icon] ??
                                  Icons.extension,
                                size: 13),
                              onPressed: group.address == null ? null : () => setState(() { _selectedIndex = childIndex; _selectedNestedPath = group.address!.at(childIndex); }),
                            ),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
            ],
            if (routeInfo.isNotEmpty) ...[
              const SizedBox(height: 8),
              Wrap(
                spacing: 4,
                runSpacing: 4,
                children: [
                  for (final route in routeInfo)
                    Chip(
                      visualDensity: VisualDensity.compact,
                      padding: EdgeInsets.zero,
                      avatar: const Icon(Icons.alt_route, size: 13),
                      label: Text(route,
                        style: const TextStyle(fontSize: 10)),
                    ),
                ],
              ),
            ],
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
    final document = _document();
    if (document == null || stepsOf(widget.controller.text) == null) return const Center(child: Padding(
      padding: EdgeInsets.all(16),
      child: Text('This pipeline is not a valid JSON object with a "steps" array. '
        'Switch to JSON mode to correct it; the graph will return when the document is valid.',
        textAlign: TextAlign.center),
    ));
    final steps = _activeSteps(document);

    return FutureBuilder<List<StepKind>>(
      future: _kinds,
      builder: (context, snapshot) {
        if (snapshot.hasError) return Center(child: Text('Could not load the step catalog: ' + snapshot.error.toString()));
        if (!snapshot.hasData) return const Center(child: CircularProgressIndicator());
        final kinds = snapshot.data!;
        final byType = {for (final kind in kinds) kind.type: kind};
        return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          if (_graphStack.isNotEmpty) Padding(padding: const EdgeInsets.only(bottom: 8), child: Wrap(spacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: [
            TextButton.icon(onPressed: () => _navigateGraph(-1), icon: const Icon(Icons.account_tree_outlined, size: 16), label: const Text('Pipeline')),
            for (var depth = 0; depth < _graphStack.length; depth++) ...[
              const Icon(Icons.chevron_right, size: 16),
              TextButton(onPressed: () => _navigateGraph(depth), child: Text(_graphStack[depth].label)),
            ],
          ])),
          Row(children: [
            Text(_activeSequenceLabel + ' · ' + steps.length.toString() + (steps.length == 1 ? ' step' : ' steps'),
              style: Theme.of(context).textTheme.labelLarge),
            const Spacer(),
            if (_graphStack.isEmpty) _addButton(kinds, steps) else PopupMenuButton<StepKind>(tooltip: 'Add step to current lane', onSelected: (kind) { final updated = insertStepAtAddress(document, _activeSequenceAddress, steps.length, kind.instantiate()); if (updated != null) _writeNested(updated, _activeSequenceAddress.at(steps.length)); }, itemBuilder: (_) => [for (final kind in kinds) PopupMenuItem(value: kind, child: Text(kind.label))], child: const Chip(avatar: Icon(Icons.add_rounded, size: 17), label: Text('Add step'))),
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
                  nodes.add(_node(i, steps[i], byType, kinds, _activeSequenceAddress));
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
          if (_graphStack.isEmpty) _inspector(steps, byType),
          const SizedBox(height: 8),
          _nestedInspector(kinds),
        ]);
      },
    );
  }
}
