import 'dart:convert';

import 'package:flutter/material.dart';

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

/// Visual field mapping: one row per output field, each fed by a source field (picked from the sample input), a fixed value, a text template
/// or an expression, with a chain of transformations. The JSON in [controller] stays the source of truth: the builder writes it whenever the
/// rows are valid, and re-reads it when it is changed elsewhere (an example, the JSON tab). A spec with features the builder cannot show
/// (nested objects, `$each`, `$if`) is left untouched and edited as JSON.
class FieldMappingBuilder extends StatefulWidget {
  const FieldMappingBuilder({super.key, required this.controller, required this.sample});

  final TextEditingController controller;

  /// The sample variables JSON; its paths feed the source picker and "Auto-map".
  final TextEditingController sample;

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
    _load();
  }

  @override
  void dispose() {
    widget.controller.removeListener(_external);
    widget.sample.removeListener(_samplePathsChanged);
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
    _rows!.addAll(added);
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
            OutlinedButton.icon(
              onPressed: () {
                rows.add(MappingRow());
                _changed();
              },
              icon: const Icon(Icons.add, size: 16),
              label: const Text('Add field'),
            ),
            OutlinedButton.icon(
              onPressed: _samplePaths.isEmpty ? null : _autoMap,
              icon: const Icon(Icons.auto_fix_high, size: 16),
              label: const Text('Map all from sample'),
            ),
            Text(_samplePaths.isEmpty ? 'Paste sample variables to pick source fields.' : '${_samplePaths.length} source fields in the sample',
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
                    onChanged: _changed,
                    onRemove: () {
                      rows.removeAt(i);
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
    this.depth = 0,
    this.showTarget = true,
    this.title,
    this.kinds = SourceKind.values,
  });

  final MappingRow row;

  /// The variables the source picker draws from: the sample, plus the current item inside a list.
  final Object? scope;
  final int depth;
  final bool showTarget;
  final String? title;
  final List<SourceKind> kinds;
  final VoidCallback onChanged;
  final VoidCallback onRemove;
  final void Function(int delta)? onMove;

  @override
  Widget build(BuildContext context) {
    final paths = samplePaths(scope);
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
                    decoration: const InputDecoration(isDense: true, labelText: 'Output field', hintText: 'customer.name'),
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
          if (row.kind == SourceKind.list) _listBody(),
          if (row.kind == SourceKind.conditional) _conditionalBody(),
        ],
      ),
    );
  }

  Widget _source() {
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
                depth: depth + 1,
                kinds: depth >= 2 ? _valueKinds : SourceKind.values,
                onChanged: onChanged,
                onRemove: () {
                  row.children.removeAt(i);
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
              onPressed: () {
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
                      row.children.addAll(added);
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
