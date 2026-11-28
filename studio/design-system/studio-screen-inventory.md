# Studio Screen Inventory — Initial Source-Grounded Census

## Scope and confidence

This is the first source-grounded inventory for WDS adoption. It is based on
these files read from `main`:

- `studio/lib/features/shell/app_shell.dart`
- `studio/lib/core/widgets.dart`
- `studio/lib/design_system/wds.dart`
- `studio/lib/design_system/components.dart`
- `studio/lib/design_system/navigation.dart`

It records the static platform screen registry and directly verified shell
composition. It is **not yet an exhaustive repository-wide widget call-site
census**. GitHub code search returned no indexed matches for the legacy symbols
during this audit, so no total usage counts are asserted. The remaining source
scan must run against a local checkout or a functioning repository-wide index.

## Verified application entry and shell

`studio/lib/main.dart` starts `WayangStudioApp`; `studio/lib/app.dart`
installs the active light/dark themes and uses `AppShell` as the home widget.
`studio/lib/features/shell/app_shell.dart` is the central composition point.

The shell:
- Builds platform navigation and merges it with installed plugin navigation.
- Selects the active screen through host-owned Riverpod state.
- Uses an `IndexedStack` for registered screens.
- Uses `WdsNavigationRail` for desktop/tablet widths and
  `WdsCompactNavigationBar` for compact widths.
- Keeps plugin discovery, route selection, authorization/availability and
  navigation state outside WDS.

## Verified static platform screen registry

The following builders are explicitly registered in
`_platformScreenBuilders` or the dashboard-first navigation composition in
`app_shell.dart`.

| Navigation key / screen | Source widget | WDS adoption notes | Census status |
|---|---|---|---|
| dashboard | `DashboardScreen` | Inspect KPI/surface/page-header patterns; KPI meaning stays feature-owned | Registered; screen internals not yet scanned |
| components | `ComponentCatalogScreen` | Uses `WdsPageHeader`, `WdsFilterToolbar`, `WdsSearchField`, `WdsSurface`, `WdsBadge`, `WdsInspectorSection`, `WdsFieldGroup`, `WdsPropertyRow` and `WdsEmptyState`; loading/error recovery remains host-owned | Migrated in M14; filtering remains feature-owned |
| runs | `RunsScreen` | Uses `WdsPageHeader`, `WdsFilterToolbar`, `WdsSearchField`, `WdsSurface`, `WdsExecutionStatusIndicator`, `WdsExecutionTimeline`, `WdsBadge`, `WdsButton` and `WdsIconButton`; run lifecycle, polling, WebSocket subscriptions, cancel/start and variables remain feature-owned | Migrated in M19 |
| tasks | `TasksScreen` | Uses `WdsPageHeader`, `WdsFilterToolbar`, `WdsSearchField`, `WdsSurface`, `WdsBadge`, `WdsButton` and `WdsIconButton`; overdue ordering, task completion, message delivery and due-time logic remain feature-owned | Migrated in M19 |
| projects | `ProjectsScreen` | Uses `WdsPageHeader`, `WdsButton`, `WdsFilterToolbar`, `WdsSearchField`, `WdsDataTable`, `WdsIconButton`, `WdsBadge` and `WdsEmptyState`; project lifecycle and active-project state remain feature-owned | Migrated in M15; search/filter is feature-owned |
| channels | `ChannelsScreen` | Candidate for catalog/table/inspector patterns | Registered; screen internals not yet scanned |
| mapper | `MapperScreen` | Editor-specific; inspect editor toolbar/panel and property inspector | Registered; screen internals not yet scanned |
| mcp | `McpScreen` | Candidate for integration catalog, status and configuration inspector | Registered; screen internals not yet scanned |
| secrets | `SecretsScreen` | Candidate for table and field presentation; secret values and reveal policy stay feature-owned | Registered; screen internals not yet scanned |
| config | `ConfigScreen` | Candidate for page header, field groups and property rows | Registered; screen internals not yet scanned |
| workspace | `WorkspaceScreen` | Candidate for page header, inspector and resource-list patterns | Registered; screen internals not yet scanned |
| surfaces | `SurfacesScreen` | Candidate for catalog/table and detail patterns | Registered; screen internals not yet scanned |
| feedback | `FeedbackScreen` | Form and submission behavior stay feature-owned; WDS controls may be reused | Registered; screen internals not yet scanned |
| settings | `SettingsScreen` | Candidate for page header, field groups and property rows | Registered; screen internals not yet scanned |

Dashboard is inserted as the first navigation item. Plugin screens are supplied
dynamically through `allNavEntries(plugins)`; they cannot be enumerated from
this static registry and need a separate plugin inventory.

The compact navigation intentionally exposes a subset of keys
(`dashboard`, `bpmn`, `runs`, `tasks`, `settings`). The BPMN destination
is plugin-provided in this shell; it is not a static entry in
`_platformScreenBuilders`.

## Verified WDS use in the shell

The following call sites are directly visible in `app_shell.dart`:

| Location | Existing / updated component | Ownership |
|---|---|---|
| Main desktop navigation | `WdsNavigationRail` | WDS renders; shell owns item list and selected key |
| Compact bottom navigation | `WdsCompactNavigationBar` | WDS renders; shell owns visible destinations and selected key |
| Top-bar COMPOSE marker | `WdsBadge` (this PR replaces legacy `StatusPill`) | Semantic brand tone; shell owns label/context |
| Project context marker | `WdsBadge` (this PR replaces private `_EnvPill` styling) | Neutral visual; shell supplies project label |
| Top-bar divider | Theme `colorScheme.outlineVariant` | Shell layout uses active theme instead of fixed dark border |

The shell still owns shortcut registration, feedback dialog, theme toggle,
project context lookup, plugin composition and navigation updates. None of
these responsibilities moves into WDS.

## Verified legacy shared-widget API definitions

The following public widgets/helpers are defined in
`studio/lib/core/widgets.dart`:

| API | Current relationship to WDS | Verified status |
|---|---|---|
| `SurfacePanel` | Delegates to `WdsSurface` | Adapter present on main |
| `AppSearchField` | Delegates to `WdsSearchField` | Adapter present on main |
| `EmptyState` | Delegates to `WdsEmptyState` | Adapter present on main |
| `ScreenHeader` | Composes `WdsSectionHeader`, retaining optional branded icon | Adapter present on main |
| `KeyValueRow` | Delegates to `WdsPropertyRow`, preserving monospace formatting | Adapter present on main |
| `StatusPill` | Caller-supplied color implementation | Still legacy; classify remaining callers before replacement |
| `KpiCard` | KPI/metric-specific composition | Remains host/feature-owned pending cross-mode contract |
| `LoadingView` | Full-page loading composition | Remains host-owned |
| `ErrorView` | Includes Riverpod Settings navigation | Must remain host-owned or be split before visual extraction |
| `PanelSectionLabel` | Section label with trailing slot | Candidate only if repeated reuse proves a shared contract |
| `showConfirmDialog` | App confirmation policy | Host-owned |
| `showToast` | App-wide feedback/messenger policy | Host-owned |
| `confirmDiscardEdits` | Async editor unsaved-change decision | Feature/editor-owned |

This table inventories definitions, not all uses. A class definition does not
prove a screen uses it.

## Immediate P0 migration completed in this change

In `app_shell.dart`:

1. Replaced the verified COMPOSE `StatusPill` use with `WdsBadge` using the
   explicit `brand` tone.
2. Replaced the private `_EnvPill` hand-built decoration with a neutral
   `WdsBadge` and folder icon. The project label remains supplied by the shell.
3. Changed the top-bar bottom border from a fixed dark palette color to the
   active theme's `colorScheme.outlineVariant`.

This is deliberately limited to verified shell call sites. It does not
establish a global mapping for arbitrary `StatusPill` callers.

## Required next scan to complete Wave 0

Run a repository-wide search from the `studio/` directory for:

- Imports of `core/widgets.dart` and `design_system/wds.dart`.
- Definitions and invocations of each legacy API in the table above.
- Direct duplicated patterns for headers, search fields, empty states,
  surfaces, key/value rows and status badges.
- Existing widget tests for each screen family.

For every match, record the exact file path, symbol, screen family and whether
it is a definition, import or rendered call site. Exclude generated files,
build output and test fixtures from production call-site counts. Resolve
ambiguous references manually before assigning a WDS target.

Suggested local commands:

```bash
cd studio
rg -n --glob '*.dart' "core/widgets.dart|design_system/wds.dart" lib test
rg -n --glob '*.dart' "\b(SurfacePanel|StatusPill|ScreenHeader|KpiCard|EmptyState|LoadingView|ErrorView|AppSearchField|KeyValueRow|PanelSectionLabel)\b" lib
rg -n --glob '*.dart' "\b(showConfirmDialog|showToast|confirmDiscardEdits)\b" lib
```

## Migration acceptance criteria

- Each production match is assigned to a screen family and reviewed.
- Existing feature behavior, state ownership, keyboard behavior and route
  semantics are preserved.
- WDS replacements are verified in light/dark themes and supported widths.
- Widget tests cover representative rendering, callbacks and semantics.
- No provider, router, runtime, plugin, API or persistence dependency enters WDS.
- Legacy facades are removed only after a complete search proves no remaining
  supported consumers.


## M14 — Component Catalog adoption

The Component Catalog was inspected and migrated in
`studio/lib/features/components/component_catalog_screen.dart`.

Verified adoption:
- `WdsPageHeader` provides page identity and runtime context.
- `WdsFilterToolbar` composes the search control responsively;
  `WdsSearchField` reports text changes to the feature-owned query state.
- `WdsSurface` and `WdsBadge` provide theme-aware component cards and
  neutral version metadata.
- `WdsFieldGroup`, `WdsInspectorSection` and `WdsPropertyRow` present
  component metadata and configuration schema fields.
- `WdsEmptyState` handles both an empty runtime catalog and an empty search
  result.
- Existing loading and error/retry views remain host-owned because they include
  application-level behavior.

The existing multi-term search contract is preserved: every whitespace-
separated term must match somewhere in the component's display metadata.
A focused test file covers matching across names, categories, kinds, executor
types, descriptions and capabilities, plus empty/whitespace-only queries.

This is a verified migration of the Component Catalog only. It does not claim
that Projects, Channels, MCP, Secrets or other catalogs have been audited.


## M15 — Projects catalog adoption

The Projects screen now uses the shared WDS page, filter, table, action,
badge and empty-state primitives.

- Project data loading, create/rename/delete operations, duplicate-name
  validation, confirmation dialogs, error feedback and active-project state
  remain in the Projects feature.
- The table is a presentation component; it does not own pagination, sorting,
  persistence or project selection.
- Search matches every whitespace-separated term across project name, ID and
  description. It is local presentation filtering and does not mutate project
  data or the active-project selection.
- The explicit `All definitions` action clears the active project filter.
- Regression tests cover the WDS table adoption, row actions, search behavior,
  empty state and existing project creation validation.

Only the Projects screen is covered by this migration record; other resource
screens remain unaudited until their source is inspected.


## M16 — Channels and MCP integration catalog adoption

The Channels and MCP screens now consume the shared WDS page and status
primitives while retaining their distinct interaction models.

- Channels uses `WdsPageHeader` for page identity and preserves its two-tab
  organization for triggers and connected OAuth accounts.
- Trigger channel, disabled and failure indicators use semantic
  `WdsBadgeTone` values. OAuth connection states map connected to success,
  revoked to danger and pending/other states to warning.
- The connected-account empty state uses `WdsEmptyState`.
- MCP uses `WdsPageHeader`, `WdsButton` and `WdsIconButton` for page
  composition and actions. Its empty state uses `WdsEmptyState`.
- MCP tool permission labels use success for read-only, danger for destructive
  and warning for write-capable tools.

The migration deliberately does not combine the two screens into a generic
integration catalog: Channels is a trigger/account workspace with two tabs,
whereas MCP is an OpenAPI-imported server catalog with lazy tool details.
Credential references, OAuth consent, allow-lists, endpoint exposure,
OpenAPI preview/import, tool authorization, refresh/delete operations,
provider state and error recovery remain feature-owned.

Focused widget assertions verify WDS page headers and semantic badges in both
screens. Existing dialog validation and feature behavior remain covered by
the original screen tests.


## M19 — Runs and Tasks execution experience

Runs and Tasks were inspected as distinct operational screens and migrated without merging their interaction models.

### Runs
- Page heading, responsive filter/search composition, selected run surface, empty states and primary actions use WDS.
- Run status maps to shared execution vocabulary: running → running, waiting → queued, completed → succeeded, failed → failed, cancelled → cancelled. The API `RunStatus` is unchanged.
- The live event feed uses `WdsExecutionTimeline`. Items derive from server event type, node id and timestamp; unknown event types remain pending/neutral rather than being guessed successful.
- The existing 200-event retention and 50-event presentation window remain feature-owned.
- WebSocket ticket acquisition, event subscription, polling fallback, refresh, cancellation, start validation, canvas highlighting, feedback, variable preview/copy and surface validation remain feature-owned.

### Tasks
- Page heading, responsive search/filter toolbar, empty states and task rows use WDS.
- Task kind and overdue state use semantic badges. Overdue calculation and sort order remain feature-owned.
- Complete, send-message and refresh actions use shared WDS controls.
- JSON validation, duplicate-submit protection, message correlation, buffered/delivered feedback and repository calls remain feature-owned.

### Regression coverage
- Existing run/task filtering tests remain.
- Run tests cover API status and server-event status mapping, including unknown and incomplete event types.
- New Runs and Tasks widget tests assert WDS page/filter/action/surface/badge composition.
- No runtime, repository, WebSocket or Riverpod dependencies were added to WDS.
