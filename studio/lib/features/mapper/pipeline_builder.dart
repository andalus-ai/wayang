import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show rootBundle;

import '../../data/pipeline_model.dart';

const _mono = TextStyle(fontFamily: 'monospace', fontSize: 12);

/// Icons by the names used in `assets/pipeline_steps.json` (a Java test checks every name there is listed here).
const stepIcons = <String, IconData>{
  'data_object': Icons.data_object,
  'text_snippet': Icons.text_snippet,
  'swap_horiz': Icons.swap_horiz,
  'auto_fix_high': Icons.auto_fix_high,
  'filter_alt': Icons.filter_alt,
  'mail_outline': Icons.mail_outline,
  'table_rows': Icons.table_rows,
  'segment': Icons.segment,
  'rule': Icons.rule,
  'sell': Icons.sell,
  'link': Icons.link,
  'filter_list': Icons.filter_list,
  'call_split': Icons.call_split,
  'alt_route': Icons.alt_route,
  'route': Icons.route,
  'groups': Icons.groups,
  'shuffle': Icons.shuffle,
  'playlist_play': Icons.playlist_play,
  'call_merge': Icons.call_merge,
  'merge': Icons.merge,
  'inventory': Icons.inventory,
  'sort': Icons.sort,
  'view_module': Icons.view_module,
  'shield': Icons.shield,
  'replay': Icons.replay,
  'electrical_services': Icons.electrical_services,
  'speed': Icons.speed,
  'hourglass_empty': Icons.hourglass_empty,
  'fingerprint': Icons.fingerprint,
  'loop': Icons.loop,
  'receipt_long': Icons.receipt_long,
  'cloud_upload': Icons.cloud_upload,
};

/// Edits the `steps` of the pipeline JSON held by [controller]: add from the catalog, reorder, edit one step as JSON, remove.
/// The controller stays the single source of truth, so the JSON view, this view and the live preview never disagree.
class PipelineBuilder extends StatefulWidget {
  const PipelineBuilder({super.key, required this.controller});

  final TextEditingController controller;

  @override
  State<PipelineBuilder> createState() => _PipelineBuilderState();
}

class _PipelineBuilderState extends State<PipelineBuilder> {
  late final Future<List<StepKind>> _kinds = rootBundle.loadString('assets/pipeline_steps.json').then(parseCatalog);

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_refresh);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  void _write(List<Map<String, dynamic>> steps) {
    widget.controller.text = encodeSteps(steps, previous: widget.controller.text);
  }

  Future<void> _edit(List<Map<String, dynamic>> steps, int i) async {
    final text = TextEditingController(text: const JsonEncoder.withIndent('  ').convert(steps[i]));
    String? error;
    final result = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setLocal) => AlertDialog(
          title: Text('Step ${i + 1}: ${steps[i]['type']}'),
          content: SizedBox(
            width: 560,
            height: 360,
            child: Column(children: [
              Expanded(
                child: TextField(
                  controller: text,
                  expands: true,
                  maxLines: null,
                  minLines: null,
                  textAlignVertical: TextAlignVertical.top,
                  style: _mono,
                  decoration: const InputDecoration(border: OutlineInputBorder(), isDense: true),
                ),
              ),
              if (error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
            FilledButton(
              onPressed: () {
                try {
                  Navigator.pop(context, parseStep(text.text));
                } on FormatException catch (e) {
                  setLocal(() => error = e.message);
                }
              },
              child: const Text('Apply'),
            ),
          ],
        ),
      ),
    );
    text.dispose();
    if (result != null) {
      final next = List<Map<String, dynamic>>.of(steps)..[i] = result;
      _write(next);
    }
  }

  @override
  Widget build(BuildContext context) {
    final steps = stepsOf(widget.controller.text);
    if (steps == null) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(16),
          child: Text('The pipeline is not a {"steps": [...]} object yet. Fix it in the JSON view; the builder shows it again when it is valid.',
              textAlign: TextAlign.center),
        ),
      );
    }
    return FutureBuilder<List<StepKind>>(
      future: _kinds,
      builder: (context, snap) {
        final kinds = snap.data ?? const <StepKind>[];
        final byType = {for (final k in kinds) k.type: k};
        return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Align(
            alignment: Alignment.centerLeft,
            child: PopupMenuButton<StepKind>(
              tooltip: 'Add a step',
              enabled: kinds.isNotEmpty,
              onSelected: (k) => _write([...steps, k.instantiate()]),
              itemBuilder: (_) {
                final items = <PopupMenuEntry<StepKind>>[];
                String? group;
                for (final k in kinds) {
                  if (k.group != group) {
                    group = k.group;
                    items.add(PopupMenuItem<StepKind>(enabled: false, height: 28, child: Text(k.group.toUpperCase(), style: const TextStyle(fontSize: 11))));
                  }
                  items.add(PopupMenuItem<StepKind>(
                    value: k,
                    child: ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(stepIcons[k.icon] ?? Icons.extension, size: 18),
                      title: Text(k.label),
                      subtitle: Text(k.description, maxLines: 1, overflow: TextOverflow.ellipsis),
                    ),
                  ));
                }
                return items;
              },
              child: const Chip(avatar: Icon(Icons.add, size: 16), label: Text('Add step')),
            ),
          ),
          const SizedBox(height: 8),
          Expanded(
            child: steps.isEmpty
                ? const Center(child: Text('No steps yet. Add one, or load an example.'))
                : ListView.separated(
                    itemCount: steps.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 6),
                    itemBuilder: (context, i) {
                      final s = steps[i];
                      final kind = byType[s['type']];
                      return Card(
                        margin: EdgeInsets.zero,
                        child: ListTile(
                          dense: true,
                          leading: CircleAvatar(radius: 14, child: Text('${i + 1}', style: const TextStyle(fontSize: 12))),
                          title: Row(children: [
                            Icon(stepIcons[kind?.icon] ?? Icons.extension, size: 16),
                            const SizedBox(width: 6),
                            Flexible(child: Text(kind?.label ?? '${s['type']}', overflow: TextOverflow.ellipsis)),
                          ]),
                          subtitle: Text(describeStep(s), maxLines: 2, overflow: TextOverflow.ellipsis),
                          onTap: () => _edit(steps, i),
                          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                            IconButton(
                              tooltip: 'Move up',
                              icon: const Icon(Icons.arrow_upward, size: 18),
                              onPressed: i == 0 ? null : () => _write(moved(steps, i, -1)),
                            ),
                            IconButton(
                              tooltip: 'Move down',
                              icon: const Icon(Icons.arrow_downward, size: 18),
                              onPressed: i == steps.length - 1 ? null : () => _write(moved(steps, i, 1)),
                            ),
                            IconButton(tooltip: 'Edit', icon: const Icon(Icons.edit_outlined, size: 18), onPressed: () => _edit(steps, i)),
                            IconButton(
                              tooltip: 'Remove',
                              icon: const Icon(Icons.delete_outline, size: 18),
                              onPressed: () => _write(List<Map<String, dynamic>>.of(steps)..removeAt(i)),
                            ),
                          ]),
                        ),
                      );
                    },
                  ),
          ),
        ]);
      },
    );
  }
}
