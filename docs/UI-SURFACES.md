# UI surfaces (A2UI subset)

A **surface** is a small, declarative, read-only UI described as JSON. Agents or workflows can produce one; Studio draws it. Wayang
takes only this wire format from the Alhambra/A2UI work. Plugins stay on Wayang's own `StudioPlugin` registry.

## Contract

```json
{ "id": "order-42", "title": "Order 42", "rootNode": { "_type": "card", "title": "Summary", "children": [ ... ] } }
```

Node types: `text`, `heading` (level 1-6), `markdown` (drawn as plain text), `badge` (`neutral|info|success|warning|error`), `divider`,
`metadata_row`, `provenance`, `card`, `container`, `list`, `table`, `button`.

Actions on a button: `refresh`, or `navigate` to a Studio route starting with a single `/`. `submit`, `invoke`, `approve` and `reject`
are refused for now: they need a binding to a real, authorised Wayang operation first. `image`, `chart` and `artifact` nodes are
refused too (an image would make Studio fetch a URL chosen by the surface's author).

Limits: depth 8, 300 nodes, 10,000 characters per text, 200 table rows, 20 columns, 20 metadata entries.

## Where it is enforced

- Server: `POST /api/v1/ui/surfaces/validate` (signed-in users) returns `{ok, problems, surface}`. The surface is rebuilt from known
  fields only, so unknown keys never reach a client.
- Studio: `UiSurface.parse` is tolerant and applies the same limits again; unknown nodes show a "Not shown" note. The **UI surfaces**
  screen is a playground: paste, check, render.

## Not done

Binding actions to operations, images, charts, and persisting or pushing surfaces from runs. Each needs its own design and tests.
