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
| components | `ComponentCatalogScreen` | Candidate for page header, filter toolbar, table, badges and empty state | Registered; screen internals not yet scanned |
| runs | `RunsScreen` | Candidate for execution status/timeline/event and inspector patterns | Registered; screen internals not yet scanned |
| tasks | `TasksScreen` | Candidate for catalog/table/inspector and execution status patterns | Registered; screen internals not yet scanned |
| projects | `ProjectsScreen` | Candidate for page header, table, filters and empty state | Registered; screen internals not yet scanned |
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
