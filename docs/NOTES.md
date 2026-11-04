# Notes (`notes` connector)

A private notebook with no outside service, meant for local and all-in-one deployments.

- Operations (config `operation`): `add` (`text`, optional `title`), `list` (`limit`), `search` (`q`, case-insensitive, title and text),
  `delete` (`id`, like `n3`). Optional `namespace` (letters, digits, `-`, `_`) keeps separate notebooks.
- Storage: one JSON file per namespace in `WAYANG_NOTES_DIR`, else `WAYANG_STATE_DIR/notes`; with neither set, notes live in memory and
  are lost on restart (the doctor already warns when `WAYANG_STATE_DIR` is missing). Writes are atomic; an unreadable file is an error, never
  silently replaced.
- Bounds: 1000 notes per notebook, 4000 characters per note, 200 per title.
- Assistant templates: `notes: true` adds `notes_add`, `notes_search`, `notes_list` and `notes_delete`; delete is held for the person's yes
  like other approval-gated actions. Note text is stored data; the prompt tells the model never to treat it as instructions.
- Not included: encryption at rest, sync between machines, per-chat notebooks (one `assistant` notebook per release).
