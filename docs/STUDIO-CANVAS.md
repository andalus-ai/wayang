# Studio BPMN canvas: selection and shortcuts

- **Select:** click a node; shift/ctrl/cmd-click adds or removes it from the selection. Clicking empty canvas clears it.
- **Box select:** the first button in the zoom controls turns box select on; dragging on empty canvas then draws a rectangle and selects
  every node it touches (panning is paused while it is on).
- **Group:** dragging a selected node moves the whole group as one undo step; Delete removes the group as one undo step. The inspector shows
  a node only when exactly one is selected. A group move keeps each node's lane as it was.
- **Keys** (canvas focused, after a click on it): Delete/Backspace remove, Esc clears, Ctrl/Cmd+A selects all, Ctrl/Cmd+Z undo,
  Ctrl+Y or Ctrl/Cmd+Shift+Z redo.

Planned next, each in its own change: context menu, minimap, debug window.
