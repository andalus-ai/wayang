# Studio decision table editor

- **Undo / redo:** the arrows beside Validate, or Ctrl/Cmd+Z and Ctrl+Y / Ctrl/Cmd+Shift+Z while the table has focus. Each edit is one step; typing in the
  same cell within a second counts as one step. The last 100 steps are kept; opening another table or reloading starts fresh. Undo marks the table unsaved,
  and a save keeps the history.
- **Rules:** each row has move up, move down, duplicate (a copy right below, new id) and delete. Order matters for hit policies such as FIRST and RULE ORDER.
- Inside a text field Ctrl+Z undoes the table, not just the field, because the editor handles the shortcut first.
- **Filter:** the box above the grid shows only the rules where every word you type appears (case-insensitive) in the id, a condition, a result or the note. Reordering is
  off while a filter is on, so a move never swaps with a hidden rule. Rule numbers shown are the real positions.
- **Keyboard:** in a cell, Up and Down (and Enter) move to the cell above or below; Tab moves along the row. Left and Right still move the caret.
- **CSV:** "Copy CSV" puts the rules on the clipboard (a header of column labels, then one row per rule, then Note). "Paste CSV" adds rules from text whose first
  row names columns of this table: labels match case-insensitively in any order, `Note` fills the note, other headers are ignored and reported, columns the CSV
  does not mention get `-` (conditions) or empty (results). Rows are added after the existing ones as one undo step; at most 500 rows and 200,000 characters. Entries are copied
  exactly, so a spreadsheet may read one that starts with `=`, `+`, `-` or `@` as a formula.
