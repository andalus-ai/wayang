import 'dart:js_interop';

@JS('wayangSetDirty')
external void _wayangSetDirty(JSBoolean unsaved);

/// Tells `web/index.html` whether to show the browser's "leave site?" prompt on close.
void setUnsavedEditsOnPage(bool unsaved) {
  try {
    _wayangSetDirty(unsaved.toJS);
  } catch (_) {
    // the page script is missing (a custom index.html): no guard, nothing breaks
  }
}
