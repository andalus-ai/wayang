# Studio BPMN canvas: selection and shortcuts

- **Select:** click a node; shift/ctrl/cmd-click adds or removes it from the selection. Clicking empty canvas clears it.
- **Box select:** the first button in the zoom controls turns box select on; dragging on empty canvas then draws a rectangle and selects
  every node it touches (panning is paused while it is on).
- **Group:** dragging a selected node moves the whole group as one undo step; Delete removes the group as one undo step. The inspector shows
  a node only when exactly one is selected. After a group move each node joins the lane its centre is now in, like a single drag.
- **Copy / paste:** Ctrl/Cmd+C, X, V or the context menu. A copy holds the selected nodes and the flows between them; it stays in memory while Studio
  is open, so it can be pasted into another process after switching. Paste without a position nudges the copy 40px down-right until it does
  not sit on an existing node; "Paste here" on empty canvas puts the top-left corner at the click. Pasted nodes get new ids and the lane they
  land in. Node settings are copied as they are (secret references stay references). Not written to the system clipboard.
- **Snap and nudge:** the grid button in the zoom controls makes dragging snap to a 20px grid (the dragged node lands on a grid line and a group
  keeps its shape). Arrow keys move the selection by one grid cell; with Shift, by one pixel. Each press is one undo step. Snap is off by default
  and is not remembered between sessions.
- **Alignment guides:** while dragging, a node (or group) whose left/centre/right or top/middle/bottom comes within 6px of another node's snaps
  to it and an amber guide line shows the match. Guides take priority over the grid.
- **Find:** Ctrl/Cmd+F or the magnifier in the zoom controls opens a search over node names, ids and element types (every word must match,
  names that start with the query first). Picking one selects it and brings it to the middle of the view.
- **Rename:** double-click a node to rename it, or a flow to edit its label (flow labels are saved in the document's `studio.meta.flowLabels`, never sent to the engine) (also "Rename" / "Edit label" in the context menu). One undo step; saving an
  unchanged name records nothing. Double-click makes a single click on a node or flow register a moment later.
- **Export as image:** right-click empty canvas, "Copy diagram as SVG", then paste into a file ending `.svg` (Studio has no file-save yet).
  The picture has lanes, flows with arrows and labels, and node names; it contains no scripts or external references, every label is escaped,
  and node settings are never included.
- **Help:** `?`, F1 or the keyboard icon in the zoom controls lists all of this in the app.
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
