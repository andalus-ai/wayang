import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../providers/app_providers.dart';

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

/// A small search dialog (Ctrl/Cmd+K) that jumps to a section: type, arrow keys are not needed, Enter opens the first match, Escape closes.
Future<void> showGoTo(BuildContext context, WidgetRef ref, List<GoToEntry> entries) {
  return showDialog<void>(
    context: context,
    builder: (ctx) {
      var query = '';
      return StatefulBuilder(builder: (ctx, setState) {
        final hits = filterGoTo(entries, query);
        void open(GoToEntry e) {
          ref.read(selectedNavKeyProvider.notifier).state = e.key;
          Navigator.pop(ctx);
        }

        return AlertDialog(
          title: const Text('Go to'),
          content: SizedBox(
            width: 380,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  autofocus: true,
                  decoration: const InputDecoration(hintText: 'Type a section name', prefixIcon: Icon(Icons.search_rounded)),
                  onChanged: (v) => setState(() => query = v),
                  onSubmitted: (_) {
                    if (hits.isNotEmpty) open(hits.first);
                  },
                ),
                const SizedBox(height: 8),
                if (hits.isEmpty)
                  const Padding(padding: EdgeInsets.all(12), child: Text('No section matches'))
                else
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxHeight: 320),
                    child: ListView(
                      shrinkWrap: true,
                      children: [
                        for (final e in hits)
                          ListTile(dense: true, leading: Icon(e.icon, size: 18), title: Text(e.label), onTap: () => open(e)),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        );
      });
    },
  );
}
