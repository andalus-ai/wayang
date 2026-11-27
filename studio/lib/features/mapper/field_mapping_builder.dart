import 'dart:convert';

import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show Clipboard, ClipboardData, LengthLimitingTextInputFormatter;

import '../../core/theme.dart';
import '../../data/field_mapping_model.dart';

const _mono = TextStyle(fontFamily: 'monospace', fontSize: 11.5);

String _kindLabel(SourceKind k) => switch (k) {
      SourceKind.field => 'Field',
      SourceKind.constant => 'Fixed value',
      SourceKind.template => 'Text template',
      SourceKind.expression => 'Expression',
      SourceKind.list => 'Each item of a list',
      SourceKind.conditional => 'If / else',
    };

const _valueKinds = [SourceKind.field, SourceKind.constant, SourceKind.template, SourceKind.expression];

/// A ready-made set of rows a host can offer ("fill from the connector's inputs"): [build] gets the sample and the rows already there.
class MappingSuggestion {
  const MappingSuggestion({required this.label, required this.tooltip, required this.build});
  final String label;
  final String tooltip;
  final List<MappingRow> Function(Object? sample, List<MappingRow> existing) build;
}

/// Visual field mapping: one row per output field, each fed by a source field (picked from the sample input), a fixed value, a text template
/// or an expression, with a chain of transformations. The JSON in [controller] stays the source of truth: the builder writes it whenever the
/// rows are valid, and re-reads it when it is changed elsewhere (an example, the JSON tab). A spec with features the builder cannot show
/// (nested objects, `$each`, `$if`) is left untouched and edited as JSON.
class FieldMappingBuilder extends StatefulWidget {
  const FieldMappingBuilder({super.key, required this.controller, required this.sample, this.result, this.onProblems, this.suggestions = const []});

  final TextEditingController controller;

  /// The sample variables JSON; its paths feed the source picker and "Auto-map".
  final TextEditingController sample;

  /// The last preview result (null when there is none or it failed); each top-level row shows the value it produced.
  final ValueListenable<Object?>? result;

  /// Told the current problems after every change (empty when the rows are fine), so a host can refuse to save a spec that is out of date.
  final ValueChanged<List<String>>? onProblems;

  /// Extra "fill from ..." buttons.
  final List<MappingSuggestion> suggestions;

  @override
  State<FieldMappingBuilder> createState() => _FieldMappingBuilderState();
}

class _FieldMappingBuilderState extends State<FieldMappingBuilder> {
  List<MappingRow>? _rows; // null: the spec is not something the builder can show
  bool _writing = false;
  String _sampleFor = '\u0000';
  Object? _sampleValue;
  List<String> _paths = const [];

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_external);
    widget.sample.addListener(_samplePathsChanged);
    widget.result?.addListener(_samplePathsChanged);
    _load();
  }

  @override
  void dispose() {
    widget.controller.removeListener(_external);
    widget.sample.removeListener(_samplePathsChanged);
    widget.result?.removeListener(_samplePathsChanged);
    super.dispose();
  }

  void _external() {
    if (_writing || !mounted) return;
    setState(_load);
  }

  void _samplePathsChanged() {
    if (mounted) setState(() {});
  }

  void _load() {
    try {
      _rows = rowsFromSpec(jsonDecode(widget.controller.text));
    } catch (_) {
      _rows = null;
    }
  }

  Object? get _sample {
    _samplePaths; // refreshes the parsed sample
    return _sampleValue;
  }

  List<String> get _samplePaths {
    final t = widget.sample.text;
    if (t != _sampleFor) {
      _sampleFor = t;
      try {
        _sampleValue = t.trim().isEmpty ? null : jsonDecode(t);
        _paths = samplePaths(_sampleValue);
      } catch (_) {
        _sampleValue = null;
        _paths = const [];
      }
    }
    return _paths;
  }

  void _changed() {
    final rows = _rows!;
    final spec = specFromRows(rows);
    widget.onProblems?.call(rowProblems(rows));
    setState(() {});
    if (spec == null) return; // the problems are shown; the last valid spec stays in place
    _writing = true;
    widget.controller.text = const JsonEncoder.withIndent('  ').convert(spec);
    _writing = false;
  }

  void _autoMap() {
    final added = autoMapRows(_samplePaths, _rows!.where((r) => r.target.trim().isNotEmpty || r.value.trim().isNotEmpty).toList());
    if (added.isEmpty) return;
    _rows!.removeWhere((r) => r.target.trim().isEmpty && r.value.trim().isEmpty);
    _rows!.addAll(added.take(kMaxMappingRows - _rows!.length));
    _changed();
  }

  Future<void> _fromSchema() async {
    final c = TextEditingController();
    String? err;
    final created = await showDialog<List<MappingRow>>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, set) => AlertDialog(
          title: const Text('Rows from a JSON Schema'),
          content: SizedBox(
            width: 560,
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text(
                'Paste the schema of the object you want to produce. Each property becomes a row (nested objects become dotted names, arrays of objects '
                'become list rows). Fields whose name matches exactly one path in your sample variables are connected for you.',
                style: TextStyle(fontSize: 12),
              ),
              const SizedBox(height: 8),
              SizedBox(
                height: 260,
                child: TextField(
                  controller: c,
                  expands: true,
                  maxLines: null,
                  minLines: null,
                  textAlignVertical: TextAlignVertical.top,
                  style: _mono,
                  decoration: InputDecoration(border: const OutlineInputBorder(), isDense: true, errorText: err, hintText: '{"type": "object", "properties": {...}}'),
                ),
              ),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
            FilledButton(
              onPressed: () {
                if (c.text.length > 512 * 1024) {
                  set(() => err = 'This schema is larger than 512 KB; paste the part you need.');
                  return;
                }
                try {
                  final r = rowsFromSchema(jsonDecode(c.text), sample: _sample);
                  if (r == null) {
                    set(() => err = 'No properties to map in this schema.');
                    return;
                  }
                  Navigator.pop(ctx, r);
                } on FormatException catch (e) {
                  set(() => err = 'Not valid JSON: ${e.message}');
                }
              },
              child: const Text('Create rows'),
            ),
          ],
        ),
      ),
    );
    // c is not disposed here: the dialog is still animating out and its field may still read it; it has no listeners to leak.
    if (created == null || !mounted) return;
    _addCreated(created);
  }

  /// Adds [created] rows whose target is not there yet (blank placeholder rows make room), within the row limit.
  void _addCreated(List<MappingRow> created) {
    final rows = _rows;
    if (rows == null) return;
    final have = {for (final r in rows) r.target.trim()};
    rows.removeWhere((r) => r.target.trim().isEmpty && r.value.trim().isEmpty);
    rows.addAll(created.where((r) => !have.contains(r.target)).take(kMaxMappingRows - rows.length));
    _changed();
  }

  Future<void> _clear(List<MappingRow> rows) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Remove every field?'),
        content: Text('This removes all ${rows.length} rows from the mapping.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Remove')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    rows.clear();
    _changed();
  }

  @override
  Widget build(BuildContext context) {
    final rows = _rows;
    if (rows == null) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(16),
          child: Text(
            'This mapping uses features the builder does not show (nested objects, lists, \$each, \$if, or the JSON is not valid yet). '
            'Edit it on the JSON tab; the builder works again once it is a flat list of fields.',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 12),
          ),
        ),
      );
    }
    final problems = rowProblems(rows);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 4,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Tooltip(
              message: rows.length >= kMaxMappingRows ? 'At most $kMaxMappingRows fields' : '',
              child: OutlinedButton.icon(
                onPressed: rows.length >= kMaxMappingRows
                    ? null
                    : () {
                        rows.add(MappingRow());
                        _changed();
                      },
                icon: const Icon(Icons.add, size: 16),
                label: const Text('Add field'),
              ),
            ),
            for (final sg in widget.suggestions)
              Tooltip(
                message: sg.tooltip,
                child: OutlinedButton.icon(
                  onPressed: () => _addCreated(sg.build(_sample, _rows!)),
                  icon: const Icon(Icons.input_rounded, size: 16),
                  label: Text(sg.label),
                ),
              ),
            OutlinedButton.icon(
              onPressed: _fromSchema,
              icon: const Icon(Icons.schema_outlined, size: 16),
              label: const Text('From schema'),
            ),
            OutlinedButton.icon(
              onPressed: _samplePaths.isEmpty ? null : _autoMap,
              icon: const Icon(Icons.auto_fix_high, size: 16),
              label: const Text('Map all from sample'),
            ),
            IconButton(
              tooltip: 'Copy the mapping JSON',
              visualDensity: VisualDensity.compact,
              icon: const Icon(Icons.copy_rounded, size: 16),
              onPressed: () {
                Clipboard.setData(ClipboardData(text: widget.controller.text));
                ScaffoldMessenger.maybeOf(context)?.showSnackBar(const SnackBar(content: Text('Mapping copied'), duration: Duration(seconds: 2)));
              },
            ),
            IconButton(
              tooltip: 'Remove every field',
              visualDensity: VisualDensity.compact,
              icon: const Icon(Icons.delete_sweep_outlined, size: 18),
              onPressed: rows.isEmpty ? null : () => _clear(rows),
            ),
            Text(
                rows.isEmpty
                    ? (_samplePaths.isEmpty ? 'Paste sample variables to pick source fields.' : '${_samplePaths.length} source fields in the sample')
                    : '${rows.where((r) => r.value.trim().isNotEmpty).length} of ${rows.length} mapped'
                        '${_samplePaths.isEmpty ? '' : ' · ${_samplePaths.length} source fields in the sample'}',
                style: const TextStyle(fontSize: 11, color: AppColors.textMutedDark)),
          ],
        ),
        const SizedBox(height: 8),
        Expanded(
          child: rows.isEmpty
              ? const Center(child: Text('No fields yet. Add one, or map everything from the sample.', style: TextStyle(fontSize: 12)))
              : ListView.separated(
                  itemCount: rows.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 8),
                  itemBuilder: (context, i) => _RowCard(
                    key: ObjectKey(rows[i]),
                    row: rows[i],
                    scope: _sample,
                    result: widget.result?.value,
                    onChanged: _changed,
                    onRemove: () {
                      rows.removeAt(i);
                      _changed();
                    },
                    onDuplicate: rows.length >= kMaxMappingRows
                        ? null
                        : () {
                            final c = rows[i].copy()..target = copyTarget(rows[i].target, rows);
                            rows.insert(i + 1, c);
                            _changed();
                          },
                    onMove: i == 0 && rows.length == 1
                        ? null
                        : (delta) {
                            final j = i + delta;
                            if (j < 0 || j >= rows.length) return;
                            rows.insert(j, rows.removeAt(i));
                            _changed();
                          },
                  ),
                ),
        ),
        if (unmappedRequired(rows).isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text('Required by the schema but not mapped yet: ${unmappedRequired(rows).join(', ')}',
                style: const TextStyle(fontSize: 11.5, color: AppColors.warning)),
          ),
        if (problems.isNotEmpty) ...[
          const SizedBox(height: 6),
          for (final p in problems.take(3))
            Text(p, style: const TextStyle(fontSize: 11.5, color: AppColors.warning)),
          const Text('The spec is not updated until these are fixed.', style: TextStyle(fontSize: 11, color: AppColors.textMutedDark)),
        ],
      ],
    );
  }
}

class _RowCard extends StatelessWidget {
  const _RowCard({
    super.key,
    required this.row,
    required this.scope,
    required this.onChanged,
    required this.onRemove,
    required this.onMove,
    this.onDuplicate,
    this.result,
    this.depth = 0,
    this.showTarget = true,
    this.title,
    this.kinds = SourceKind.values,
  });

  final MappingRow row;

  /// The variables the source picker draws from: the sample, plus the current item inside a list.
  final Object? scope;

  /// The preview result this row's value is looked up in: the whole result for a top-level row, the first output item for a field inside a list.
  final Object? result;
  final int depth;
  final bool showTarget;
  final String? title;
  final List<SourceKind> kinds;
  final VoidCallback onChanged;
  final VoidCallback onRemove;
  final VoidCallback? onDuplicate;
  final void Function(int delta)? onMove;

  @override
  Widget build(BuildContext context) {
    final canTransform = row.kind == SourceKind.field || row.kind == SourceKind.expression;
    String? expr;
    try {
      expr = canTransform && row.transforms.isNotEmpty ? expressionFor(row) : null;
    } on FormatException {
      expr = null;
    }
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: depth.isEven ? AppColors.darkSurface : AppColors.darkSurfaceRaised,
        borderRadius: BorderRadius.circular(AppRadius.md),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              if (title != null) SizedBox(width: 44, child: Text(title!, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700))),
              if (showTarget) ...[
                SizedBox(
                  width: 150,
                  child: TextFormField(
                    initialValue: row.target,
                    style: _mono,
                    inputFormatters: [LengthLimitingTextInputFormatter(255)],
                    decoration: InputDecoration(
                      isDense: true,
                      labelText: row.required ? 'Output field *' : 'Output field',
                      hintText: 'customer.name',
                      helperText: row.hint.isEmpty ? null : row.hint,
                      helperMaxLines: 2,
                    ),
                    onChanged: (v) {
                      row.target = v;
                      onChanged();
                    },
                  ),
                ),
                const Icon(Icons.arrow_back, size: 16, color: AppColors.textMutedDark),
              ],
              SizedBox(
                width: 150,
                child: DropdownButtonFormField<SourceKind>(
                  value: row.kind,
                  isDense: true,
                  isExpanded: true,
                  decoration: const InputDecoration(isDense: true, labelText: 'From'),
                  items: [for (final k in kinds) DropdownMenuItem(value: k, child: Text(_kindLabel(k), style: const TextStyle(fontSize: 12)))],
                  onChanged: (k) {
                    if (k == null) return;
                    row.kind = k;
                    if (k != SourceKind.field && k != SourceKind.expression) row.transforms.clear();
                    onChanged();
                  },
                ),
              ),
              SizedBox(key: ValueKey(row.kind), width: 230, child: _source()),
              if (onMove != null) ...[
              IconButton(tooltip: 'Move up', visualDensity: VisualDensity.compact, icon: const Icon(Icons.arrow_upward, size: 16), onPressed: () => onMove!(-1)),
              IconButton(tooltip: 'Move down', visualDensity: VisualDensity.compact, icon: const Icon(Icons.arrow_downward, size: 16), onPressed: () => onMove!(1)),
              ],
              if (showTarget) IconButton(tooltip: 'Duplicate this field', visualDensity: VisualDensity.compact, icon: const Icon(Icons.copy_all_outlined, size: 17), onPressed: onDuplicate),
              if (showTarget) IconButton(tooltip: 'Remove', visualDensity: VisualDensity.compact, icon: const Icon(Icons.delete_outline, size: 18), onPressed: onRemove),
            ],
          ),
          if (canTransform) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                for (var i = 0; i < row.transforms.length; i++) _TransformChip(key: ObjectKey(row.transforms[i]), step: row.transforms[i], onChanged: onChanged, onRemove: () {
                      row.transforms.removeAt(i);
                      onChanged();
                    }),
                _addTransform(),
              ],
            ),
            if (expr != null) Padding(padding: const EdgeInsets.only(top: 6), child: SelectableText(expr, style: _mono.copyWith(color: AppColors.textMutedDark))),
          ],
          if (showTarget && result != null) _produced(),
          if (row.kind == SourceKind.list) _listBody(),
          if (row.kind == SourceKind.conditional) _conditionalBody(),
        ],
      ),
    );
  }

  Widget _source() {
    final paths = samplePaths(scope);
    switch (row.kind) {
      case SourceKind.field:
        return _pathField('Source field', 'customer.name', paths);
      case SourceKind.constant:
        return _plain('Value', 'fast, 3, true or null');
      case SourceKind.template:
        return _plain('Text with \${field}', r'Hi ${customer.name}');
      case SourceKind.expression:
        return _plain('Expression', 'result.status = 200');
      case SourceKind.list:
        return _pathField('List', 'body.lines', paths);
      case SourceKind.conditional:
        return _plain('Condition', 'body.vip or amount > 100');
    }
  }

  Widget _produced() {
    final (found, value) = resultAt(result, row.target);
    if (row.target.trim().isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 6),
      child: Row(children: [
        Icon(found ? Icons.subdirectory_arrow_right : Icons.remove_circle_outline, size: 14, color: AppColors.textMutedDark),
        const SizedBox(width: 6),
        Expanded(
          child: Text(found ? '${depth > 0 ? 'first item: ' : ''}${previewValue(value)}' : 'not in the first item (omitted or the mapping is being edited)',
              maxLines: 2, overflow: TextOverflow.ellipsis, style: _mono.copyWith(color: found ? AppColors.success : AppColors.textMutedDark)),
        ),
      ]),
    );
  }

  Widget _listBody() {
    final item = row.itemName.trim().isEmpty ? 'item' : row.itemName.trim();
    final listSample = sampleAt(scope, row.value);
    final first = listSample is List && listSample.isNotEmpty ? listSample.first : null;
    final inner = <String, Object?>{if (scope is Map) ...(scope as Map).cast<String, Object?>(), item: first};
    return Padding(
      padding: const EdgeInsets.only(left: 18, top: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(spacing: 8, runSpacing: 8, children: [
            SizedBox(
              width: 120,
              child: TextFormField(
                initialValue: row.itemName,
                style: _mono,
                decoration: const InputDecoration(isDense: true, labelText: 'Each item is called'),
                onChanged: (v) {
                  row.itemName = v;
                  onChanged();
                },
              ),
            ),
            SizedBox(
              width: 240,
              child: TextFormField(
                initialValue: row.where,
                style: _mono,
                decoration: InputDecoration(isDense: true, labelText: 'Only items where (optional)', hintText: '$item.qty > 0'),
                onChanged: (v) {
                  row.where = v;
                  onChanged();
                },
              ),
            ),
          ]),
          const SizedBox(height: 6),
          Text(row.children.isEmpty ? 'No fields: each item is copied as it is.' : 'Fields of each output item',
              style: const TextStyle(fontSize: 11, color: AppColors.textMutedDark)),
          for (var i = 0; i < row.children.length; i++)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: _RowCard(
                key: ObjectKey(row.children[i]),
                row: row.children[i],
                scope: inner,
                result: result == null ? null : firstItemResult(result, row.target),
                depth: depth + 1,
                kinds: depth >= 2 ? _valueKinds : SourceKind.values,
                onChanged: onChanged,
                onRemove: () {
                  row.children.removeAt(i);
                  onChanged();
                },
                onDuplicate: row.children.length >= kMaxMappingRows
                    ? null
                    : () {
                        row.children.insert(i + 1, row.children[i].copy()..target = copyTarget(row.children[i].target, row.children));
                        onChanged();
                      },
                onMove: (d) {
                  final j = i + d;
                  if (j < 0 || j >= row.children.length) return;
                  row.children.insert(j, row.children.removeAt(i));
                  onChanged();
                },
              ),
            ),
          const SizedBox(height: 6),
          Wrap(spacing: 8, children: [
            OutlinedButton.icon(
              onPressed: row.children.length >= kMaxMappingRows
                  ? null
                  : () {
                      row.children.add(MappingRow());
                      onChanged();
                    },
              icon: const Icon(Icons.add, size: 14),
              label: const Text('Add item field'),
            ),
            OutlinedButton.icon(
              onPressed: first == null
                  ? null
                  : () {
                      final added = autoMapRows(itemPaths(scope, row.value, item), row.children);
                      if (added.isEmpty) return;
                      row.children.addAll(added.take(kMaxMappingRows - row.children.length));
                      onChanged();
                    },
              icon: const Icon(Icons.auto_fix_high, size: 14),
              label: const Text('Map all item fields'),
            ),
          ]),
        ],
      ),
    );
  }

  Widget _conditionalBody() => Padding(
        padding: const EdgeInsets.only(left: 18, top: 8),
        child: Column(
          children: [
            _RowCard(key: ObjectKey(row.thenRow), row: row.thenRow, scope: scope, depth: depth + 1, showTarget: false, title: 'Then', kinds: _valueKinds, onChanged: onChanged, onRemove: () {}, onMove: null),
            const SizedBox(height: 6),
            _RowCard(key: ObjectKey(row.elseRow), row: row.elseRow, scope: scope, depth: depth + 1, showTarget: false, title: 'Else', kinds: _valueKinds, onChanged: onChanged, onRemove: () {}, onMove: null),
            const Align(
              alignment: Alignment.centerLeft,
              child: Padding(
                padding: EdgeInsets.only(top: 4),
                child: Text('Leave a branch empty for no value (null).', style: TextStyle(fontSize: 11, color: AppColors.textMutedDark)),
              ),
            ),
          ],
        ),
      );

  Widget _pathField(String label, String hint, List<String> paths) => Autocomplete<String>(
        initialValue: TextEditingValue(text: row.value),
        optionsBuilder: (v) {
          final q = v.text.toLowerCase();
          return paths.where((p) => q.isEmpty || p.toLowerCase().contains(q)).take(30);
        },
        onSelected: (v) {
          row.value = v;
          onChanged();
        },
        fieldViewBuilder: (context, controller, focus, _) => TextField(
          controller: controller,
          focusNode: focus,
          style: _mono,
          inputFormatters: [LengthLimitingTextInputFormatter(2000)],
          decoration: InputDecoration(isDense: true, labelText: label, hintText: paths.isEmpty ? hint : 'pick or type'),
          onChanged: (v) {
            row.value = v;
            onChanged();
          },
        ),
      );

  Widget _plain(String label, String hint) => TextFormField(
        initialValue: row.value,
        style: _mono,
        inputFormatters: [LengthLimitingTextInputFormatter(2000)],
        decoration: InputDecoration(isDense: true, labelText: label, hintText: hint),
        onChanged: (v) {
          row.value = v;
          onChanged();
        },
      );

  Widget _addTransform() {
    final groups = <String, List<TransformDef>>{};
    for (final t in transformCatalog) {
      groups.putIfAbsent(t.group, () => []).add(t);
    }
    return PopupMenuButton<TransformDef>(
      tooltip: 'Transform the value',
      onSelected: (t) {
        row.transforms.add(TransformStep(t.id));
        onChanged();
      },
      itemBuilder: (_) => [
        for (final g in groups.entries) ...[
          PopupMenuItem<TransformDef>(enabled: false, height: 26, child: Text(g.key, style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700))),
          for (final t in g.value)
            PopupMenuItem<TransformDef>(value: t, height: 32, child: Tooltip(message: t.help, child: Text(t.label, style: const TextStyle(fontSize: 12.5)))),
        ],
      ],
      child: const Chip(avatar: Icon(Icons.add, size: 14), label: Text('Transform', style: TextStyle(fontSize: 11.5)), visualDensity: VisualDensity.compact),
    );
  }
}

class _TransformChip extends StatelessWidget {
  const _TransformChip({super.key, required this.step, required this.onChanged, required this.onRemove});

  final TransformStep step;
  final VoidCallback onChanged;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final def = transformById(step.fn);
    return Container(
      padding: const EdgeInsets.fromLTRB(10, 2, 2, 2),
      decoration: BoxDecoration(
        color: AppColors.darkSurfaceRaised,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.darkBorder),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Tooltip(message: def?.help ?? '', child: Text(def?.label ?? step.fn, style: const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w600))),
          if (def != null)
            for (var i = 0; i < def.args.length; i++)
              Padding(
                padding: const EdgeInsets.only(left: 6),
                child: SizedBox(
                  width: 96,
                  child: TextFormField(
                    initialValue: step.args[i],
                    style: _mono,
                    decoration: InputDecoration(isDense: true, hintText: def.args[i].optional ? '${def.args[i].label} (optional)' : def.args[i].label, border: const UnderlineInputBorder()),
                    onChanged: (v) {
                      step.args[i] = v;
                      onChanged();
                    },
                  ),
                ),
              ),
          IconButton(tooltip: 'Remove', visualDensity: VisualDensity.compact, iconSize: 14, icon: const Icon(Icons.close), onPressed: onRemove),
        ],
      ),
    );
  }
}
