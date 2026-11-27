import 'package:flutter/material.dart';

import 'tokens.dart';

/// A command offered by a host application to the WDS command palette.
@immutable
class WdsCommandItem {
  const WdsCommandItem({
    required this.id,
    required this.label,
    required this.icon,
    this.description,
    this.keywords = const [],
    this.group,
    this.enabled = true,
  });

  final String id;
  final String label;
  final IconData icon;
  final String? description;
  final List<String> keywords;
  final String? group;
  final bool enabled;
}

/// Token-based filtering for command palette entries.
///
/// Every query token must match the label, description, group or one of the
/// supplied keywords. Results retain source order to avoid surprising jumps.
List<WdsCommandItem> filterWdsCommands(
  List<WdsCommandItem> items,
  String query,
) {
  final tokens = query.toLowerCase().trim().split(RegExp(r'\\s+'))
      .where((token) => token.isNotEmpty).toList();
  if (tokens.isEmpty) return items;
  return items.where((item) {
    final haystack = [
      item.label,
      item.description ?? '',
      item.group ?? '',
      ...item.keywords,
    ].join(' ').toLowerCase();
    return tokens.every(haystack.contains);
  }).toList();
}

/// Searchable, accessible command list. Execution remains owned by the host.
class WdsCommandPalette extends StatefulWidget {
  const WdsCommandPalette({
    super.key,
    required this.items,
    required this.onSelected,
    this.hintText = 'Search commands and destinations',
    this.emptyTitle = 'No matching commands',
    this.emptyDescription = 'Try another search term.',
    this.autofocus = true,
    this.maxResultsHeight = 360,
  });

  final List<WdsCommandItem> items;
  final ValueChanged<WdsCommandItem> onSelected;
  final String hintText;
  final String emptyTitle;
  final String emptyDescription;
  final bool autofocus;
  final double maxResultsHeight;

  @override
  State<WdsCommandPalette> createState() => _WdsCommandPaletteState();
}

class _WdsCommandPaletteState extends State<WdsCommandPalette> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final results = filterWdsCommands(widget.items, _query);
    final grouped = <String, List<WdsCommandItem>>{};
    for (final item in results) {
      grouped.putIfAbsent(item.group ?? '', () => []).add(item);
    }

    return SizedBox(
      width: 520,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            autofocus: widget.autofocus,
            onChanged: (value) => setState(() => _query = value),
            onSubmitted: (_) {
              final firstEnabled = results.where((item) => item.enabled);
              if (firstEnabled.isNotEmpty) widget.onSelected(firstEnabled.first);
            },
            decoration: InputDecoration(
              hintText: widget.hintText,
              prefixIcon: const Icon(Icons.search_rounded),
              suffixIcon: const Padding(
                padding: EdgeInsets.all(WdsSpacing.md),
                child: Text('ESC', style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700)),
              ),
              border: const OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: WdsSpacing.sm),
          if (results.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: WdsSpacing.xl),
              child: Column(
                children: [
                  Icon(Icons.search_off_rounded, size: 26, color: theme.colorScheme.onSurfaceVariant),
                  const SizedBox(height: WdsSpacing.sm),
                  Text(widget.emptyTitle, style: theme.textTheme.titleSmall),
                  const SizedBox(height: WdsSpacing.xs),
                  Text(widget.emptyDescription, style: theme.textTheme.bodySmall),
                ],
              ),
            )
          else
            ConstrainedBox(
              constraints: BoxConstraints(maxHeight: widget.maxResultsHeight),
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (final entry in grouped.entries) ...[
                    if (entry.key.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.fromLTRB(WdsSpacing.md, WdsSpacing.md, WdsSpacing.md, WdsSpacing.xs),
                        child: Text(entry.key.toUpperCase(), style: theme.textTheme.labelSmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant, letterSpacing: 1, fontWeight: FontWeight.w800,
                        )),
                      ),
                    for (final item in entry.value)
                      Semantics(
                        button: true,
                        enabled: item.enabled,
                        child: ListTile(
                          dense: true,
                          enabled: item.enabled,
                          leading: Icon(item.icon, size: 19),
                          title: Text(item.label),
                          subtitle: item.description == null ? null : Text(item.description!),
                          onTap: item.enabled ? () => widget.onSelected(item) : null,
                        ),
                      ),
                  ],
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/// Standard modal presentation for a command palette.
Future<T?> showWdsCommandPalette<T>({
  required BuildContext context,
  required List<WdsCommandItem> items,
  required ValueChanged<WdsCommandItem> onSelected,
  String title = 'Quick actions',
  String hintText = 'Search commands and destinations',
}) {
  return showDialog<T>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text(title),
      content: WdsCommandPalette(
        items: items,
        hintText: hintText,
        onSelected: (item) {
          onSelected(item);
          Navigator.of(dialogContext).pop();
        },
      ),
    ),
  );
}
