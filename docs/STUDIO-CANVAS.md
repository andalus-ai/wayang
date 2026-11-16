# Studio BPMN canvas: selection and shortcuts

- **Select:** click a node; shift/ctrl/cmd-click adds or removes it from the selection. Clicking empty canvas clears it.
- **Box select:** the first button in the zoom controls turns box select on; dragging on empty canvas then draws a rectangle and selects
  every node it touches (panning is paused while it is on).
- **Group:** dragging a selected node moves the whole group as one undo step; Delete removes the group as one undo step. The inspector shows
  a node only when exactly one is selected. After a group move each node joins the lane its centre is now in, like a single drag.
- **Keys** (canvas focused, after a click on it): Delete/Backspace remove, Esc clears, Ctrl/Cmd+A selects all, Ctrl/Cmd+Z undo,
  Ctrl+Y or Ctrl/Cmd+Shift+Z redo.

- **Context menu (right-click):** on a node or group: Duplicate (copies the nodes and the flows between them, 40px down-right, one undo step),
  Tidy layout and Align left / top (two or more nodes), Delete. On a flow: make or clear the default flow, delete. On empty canvas: add any element type at
  that point (in the lane under it), select all, tidy layout (all).
- **Tidy layout:** lays the selection (two or more nodes; it keeps its top-left corner) or the whole diagram out left to right by longest path from a
  source, like the server's auto layout; nodes keep their lane. One undo step.

- **Minimap:** an overview at the bottom left shows the lanes, every node, the selection and the visible area; click or drag on it to move the view.

- **Debug (bug icon in the toolbar):** a dock under the canvas. Pick a recent run of the open process; the canvas marks the path it took and
  you can step through the executed nodes (the canvas selects each). It also shows the run's status, error, what it waits on, variables and
  live events (click an event to select its node). Read-only: it changes nothing on the server or in the diagram. Closing it clears the marks.

Not done: breakpoints, pausing a run, or stepping a live run forward; the engine exposes no such control.
