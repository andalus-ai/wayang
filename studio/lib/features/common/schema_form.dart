import 'dart:convert';

import 'package:flutter/material.dart';

import '../../core/theme.dart';
import '../../models/wayang.dart';

/// Builds a custom editor for one property (e.g. a picker of published decisions).
typedef FieldBuilder = Widget Function(BuildContext context, Object? value, ValueChanged<Object?> onChanged);

/// A property form generated from a JSON-schema object (the node catalog's `configSchema`), so the editor and
/// the server validate against the same description. Supported: string, number/integer (with bounds), boolean,
/// `enum`; anything else (objects, arrays, mixed types) gets a JSON text field.
///
/// The form edits a copy of [value] and reports the whole new map; blank fields are removed rather than stored
/// as empty strings, so documents stay minimal.
class SchemaForm extends StatelessWidget {
  const SchemaForm({
    super.key,
    required this.identity,
    required this.schema,
    required this.value,
    required this.onChanged,
    this.overrides = const {},
    this.advancedKeys = const {},
  });

  /// Distinguishes forms of different elements, so text fields reset when the selection changes.
  final String identity;
  final Map<String, dynamic> schema;
  final Map<String, dynamic> value;
  final ValueChanged<Map<String, dynamic>> onChanged;

  /// Property name -> custom editor.
  final Map<String, FieldBuilder> overrides;

  /// Property names shown in a collapsed "Retries & error handling" section.
  final Set<String> advancedKeys;

  void _set(String key, Object? v) {
    final next = Map<String, dynamic>.from(value);
    if (v == null || (v is String && v.isEmpty)) {
      next.remove(key);
    } else {
      next[key] = v;
    }
    onChanged(next);
  }

  @override
  Widget build(BuildContext context) {
    final props = asMap(schema['properties']);
    final required = {for (final r in asList(schema['required'])) '$r'};
    final basic = <Widget>[];
    final advanced = <Widget>[];
    for (final e in props.entries) {
      final field = _field(context, e.key, asMap(e.value), required.contains(e.key));
      (advancedKeys.contains(e.key) ? advanced : basic).add(Padding(padding: const EdgeInsets.only(bottom: 14), child: field));
    }
    final unknown = value.keys.where((k) => !props.containsKey(k)).toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ...basic,
        if (advanced.isNotEmpty)
          Theme(
            data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
            child: ExpansionTile(
              tilePadding: EdgeInsets.zero,
              title: const Text('Retries & error handling', style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600)),
              initiallyExpanded: advancedKeys.any(value.containsKey),
              children: advanced,
            ),
          ),
        if (unknown.isNotEmpty)
          Text('Not in the schema (the server will reject it): ${unknown.join(', ')}',
              style: const TextStyle(fontSize: 11, color: AppColors.warning)),
      ],
    );
  }

  Widget _field(BuildContext context, String key, Map<String, dynamic> spec, bool isRequired) {
    final label = isRequired ? '$key *' : key;
    final help = spec['description'] as String?;
    final current = value[key];
    final override = overrides[key];
    if (override != null) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: const TextStyle(fontSize: 11.5, color: AppColors.textSecondaryDark)),
          const SizedBox(height: 4),
          override(context, current, (v) => _set(key, v)),
          if (help != null) Padding(padding: const EdgeInsets.only(top: 3), child: Text(help, style: const TextStyle(fontSize: 10.5, color: AppColors.textMutedDark))),
        ],
      );
    }
    final enumValues = spec['enum'];
    if (enumValues is List) {
      return DropdownButtonFormField<String>(
        key: ValueKey('$identity/$key'),
        value: current == null ? null : '$current',
        isExpanded: true,
        decoration: InputDecoration(isDense: true, labelText: label, helperText: help),
        items: [for (final o in enumValues) DropdownMenuItem(value: '$o', child: Text('$o'))],
        onChanged: (v) => _set(key, v),
      );
    }
    final type = spec['type'];
    if (type == 'boolean') {
      return SwitchListTile(
        key: ValueKey('$identity/$key'),
        dense: true,
        contentPadding: EdgeInsets.zero,
        title: Text(label, style: const TextStyle(fontSize: 12.5)),
        subtitle: help == null ? null : Text(help, style: const TextStyle(fontSize: 10.5)),
        value: current == true,
        onChanged: (v) => _set(key, v),
      );
    }
    if (type == 'integer' || type == 'number') {
      final bounds = [if (spec['minimum'] != null) 'min ${spec['minimum']}', if (spec['maximum'] != null) 'max ${spec['maximum']}'];
      return TextFormField(
        key: ValueKey('$identity/$key'),
        initialValue: current == null ? '' : '$current',
        keyboardType: const TextInputType.numberWithOptions(decimal: true),
        style: const TextStyle(fontSize: 12.5),
        decoration: InputDecoration(
          isDense: true,
          labelText: label,
          helperText: [if (help != null) help, if (bounds.isNotEmpty) bounds.join(', ')].join(' — '),
          helperMaxLines: 3,
        ),
        onChanged: (t) {
          final trimmed = t.trim();
          if (trimmed.isEmpty) return _set(key, null);
          final n = type == 'integer' ? int.tryParse(trimmed) : num.tryParse(trimmed);
          if (n != null) _set(key, n);
        },
      );
    }
    if (type == 'string') {
      return TextFormField(
        key: ValueKey('$identity/$key'),
        initialValue: current is String ? current : '',
        style: const TextStyle(fontSize: 12.5),
        decoration: InputDecoration(isDense: true, labelText: label, helperText: help, helperMaxLines: 3),
        onChanged: (t) => _set(key, t),
      );
    }
    final allowString = type is List && type.contains('string');
    return _JsonField(
      key: ValueKey('$identity/$key'),
      label: label,
      help: help,
      value: current,
      allowString: allowString,
      onChanged: (v) => _set(key, v),
    );
  }
}

/// Text field for structured values: shows JSON, reports a value only when the text parses.
class _JsonField extends StatefulWidget {
  const _JsonField({super.key, required this.label, required this.value, required this.onChanged, this.help, this.allowString = false});
  final String label;
  final String? help;
  final Object? value;
  final bool allowString;
  final ValueChanged<Object?> onChanged;

  @override
  State<_JsonField> createState() => _JsonFieldState();
}

class _JsonFieldState extends State<_JsonField> {
  String? _error;

  String _initial() {
    final v = widget.value;
    if (v == null) return '';
    if (v is String) return v;
    return const JsonEncoder.withIndent('  ').convert(v);
  }

  void _changed(String text) {
    final t = text.trim();
    if (t.isEmpty) {
      setState(() => _error = null);
      return widget.onChanged(null);
    }
    try {
      final decoded = jsonDecode(t);
      if (widget.allowString && decoded is! Map && decoded is! List) {
        setState(() => _error = null);
        return widget.onChanged(t);
      }
      setState(() => _error = null);
      widget.onChanged(decoded);
    } catch (_) {
      if (widget.allowString) {
        setState(() => _error = null);
        return widget.onChanged(t);
      }
      setState(() => _error = 'Not valid JSON yet');
    }
  }

  @override
  Widget build(BuildContext context) {
    return TextFormField(
      initialValue: _initial(),
      minLines: 1,
      maxLines: 8,
      style: const TextStyle(fontSize: 12, fontFamily: 'monospace'),
      decoration: InputDecoration(
        isDense: true,
        labelText: widget.label,
        helperText: widget.help ?? (widget.allowString ? 'Text, or a JSON object' : 'JSON'),
        helperMaxLines: 3,
        errorText: _error,
      ),
      onChanged: _changed,
    );
  }
}
