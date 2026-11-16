# Studio decision table editor

- **Undo / redo:** the arrows beside Validate, or Ctrl/Cmd+Z and Ctrl+Y / Ctrl/Cmd+Shift+Z while the table has focus. Each edit is one step; typing in the
  same cell within a second counts as one step. The last 100 steps are kept; opening another table or reloading starts fresh. Undo marks the table unsaved,
  and a save keeps the history.
- **Rules:** each row has move up, move down, duplicate (a copy right below, new id) and delete. Order matters for hit policies such as FIRST and RULE ORDER.
- Inside a text field Ctrl+Z undoes the table, not just the field, because the editor handles the shortcut first.
