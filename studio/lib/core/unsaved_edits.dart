import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'unsaved_edits_stub.dart' if (dart.library.js_interop) 'unsaved_edits_web.dart';

/// The editors that currently hold unsaved edits (e.g. `bpmn`, `dmn:<id>`). While it is not empty the browser asks before the tab is closed.
final unsavedEditorsProvider = StateProvider<Set<String>>((ref) => const {});

/// Records whether [editor] has unsaved edits. Safe to call while a widget builds (the update is deferred).
void reportUnsaved(WidgetRef ref, String editor, bool dirty) {
  Future.microtask(() {
    final now = ref.read(unsavedEditorsProvider);
    if (now.contains(editor) == dirty) return;
    ref.read(unsavedEditorsProvider.notifier).state = dirty ? {...now, editor} : ({...now}..remove(editor));
  });
}

/// Pure helper used by the tab-close guard: true when anything is unsaved.
bool hasUnsaved(Set<String> editors) => editors.isNotEmpty;

/// Tells the page whether to warn before it is closed. A no-op outside the browser.
void setBrowserCloseGuard(bool unsaved) => setUnsavedEditsOnPage(unsaved);
