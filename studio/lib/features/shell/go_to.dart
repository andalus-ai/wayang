import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../providers/app_providers.dart';
import '../../design_system/command_palette.dart';

/// One place the "Go to" palette can jump to.
class GoToEntry {
  const GoToEntry(this.key, this.label, this.icon);
  final String key;
  final String label;
  final IconData icon;
}

/// Entries whose label contains every word of [query] (case-insensitive); the best matches (label starts with the first word) come first.
/// An empty query keeps the given order.
List<GoToEntry> filterGoTo(List<GoToEntry> entries, String query) {
  final words = query.toLowerCase().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).toList();
  if (words.isEmpty) return entries;
  final hits = entries.where((e) {
    final l = e.label.toLowerCase();
    return words.every(l.contains);
  }).toList();
  hits.sort((a, b) => (a.label.toLowerCase().startsWith(words.first) ? 0 : 1).compareTo(b.label.toLowerCase().startsWith(words.first) ? 0 : 1));
  return hits;
}

/// Opens the shared WDS command palette for application navigation.
Future<void> showGoTo(BuildContext context, WidgetRef ref, List<GoToEntry> entries) async {
  await showWdsCommandPalette<void>(
    context: context,
    title: 'Go to',
    hintText: 'Type a section name',
    items: [
      for (final entry in entries)
        WdsCommandItem(
          id: entry.key,
          label: entry.label,
          icon: entry.icon,
          group: 'Navigate',
        ),
    ],
    onSelected: (item) {
      ref.read(selectedNavKeyProvider.notifier).state = item.id;
    },
  );
}
