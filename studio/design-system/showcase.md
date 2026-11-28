# WDS Component Showcase

The showcase is a presentation and review surface for the shared Wayang Design
System. It is designed to be mounted by a host-owned Studio route; it does not
register routes or alter the application theme.

## Add a showcase route

Import the public WDS entry point and mount the component:

```dart
WdsComponentShowcase(
  examples: WdsShowcaseCatalog.examples,
)
```

A host may supply a curated subset, add product-neutral examples, or create
examples for a feature-owned component without modifying WDS itself.

## Define an example

Each `WdsShowcaseExample` contains:

- `title`, `category`, and `description` for catalog discovery.
- `preview`, a normal Flutter widget displayed in an isolated theme preview.
- `code`, a Dart usage snippet shown as selectable text and copied on demand.
- `tags`, optional search keywords.

The showcase provides text search, category filtering, responsive one/two/three
column layouts, light/dark preview switching, and a no-results state.

## Ownership and boundaries

- The host owns route registration, access control, navigation and any
  application-specific examples.
- Example previews own their own interaction state and callbacks.
- WDS owns only the catalog presentation, filtering and preview framing.
- Copying uses the platform clipboard; the showcase does not persist user data.

Do not put API clients, workflow state, plugin discovery, permissions,
persistence, or runtime execution behavior into the showcase or its example
model.

## Current starter catalog

The starter catalog demonstrates action buttons, semantic status badges and
empty states. Continue adding examples as components are audited and migrated.
Each example should show a meaningful state and include a short, accurate usage
snippet.
