# Wayang Design System (WDS)

WDS is the shared presentation foundation for Wayang Studio and its product
modes. It is UI-only: it must not depend on workflow engines, API clients,
persistence, plugin activation, or execution services.

## Principles

- One coherent visual language across Agent Builder, Integration / ESB,
  Business Automation, and Intelligent Business Automation.
- Domain-aware composition: modes contribute specialized designers without
  forcing their semantics into generic controls.
- Accessible by default: semantic labels, keyboard behavior, visible focus,
  sufficient contrast, and scalable text/layout.
- Responsive from compact/mobile through desktop and wide workspaces.
- Theme-aware: consume ThemeData and semantic color roles, not dark-only colors.
- Composable: expose slots and callbacks; keep navigation and business logic out.
- Stable APIs: prefer additive changes and deprecate before removing contracts.

## Structure

- studio/lib/design_system/tokens.dart: spacing, radius, control sizes, motion,
  breakpoints and layout dimensions.
- studio/lib/design_system/components.dart: reusable controls and primitives.
- studio/lib/design_system/navigation.dart: reusable navigation destinations, desktop rail and compact navigation bar.
- studio/lib/design_system/command_palette.dart: searchable command model, filtering and modal palette.
- studio/lib/design_system/data_table.dart: controlled sorting/selection data table with loading and empty states.
- studio/lib/design_system/inspector.dart: reusable inspector sections, field groups, property rows and field messages.
- studio/lib/design_system/editor.dart: responsive editor toolbar and panel composition.
- studio/lib/design_system/execution.dart: neutral execution statuses, step timeline, event list and display formatters.
- studio/lib/design_system/node.dart: generic node cards and typed input/output port presentation.
- studio/lib/design_system/wds.dart: public import surface.
- studio/lib/core/theme.dart remains the ThemeData and brand-color source during
  migration. Existing core/widgets.dart APIs remain compatible.

## Initial component inventory

Implemented: WdsSurface, WdsBadge, WdsButton, WdsIconButton,
WdsSectionHeader, WdsSearchField, WdsEmptyState, WdsNavigationRail,
WdsCompactNavigationBar, WdsBreadcrumbs, WdsPageHeader, WdsFilterToolbar,
WdsCommandPalette, WdsDataTable, WdsInspectorSection, WdsFieldGroup,
WdsPropertyRow, WdsFieldMessage, WdsEditorToolbar, WdsEditorPanel,
WdsNodeCard, WdsNodePort, WdsExecutionStatusIndicator, WdsExecutionTimeline and WdsExecutionEventList.

The navigation widgets accept destination data and selection callbacks. The
host application remains responsible for plugin discovery, filtering, routing,
permissions and choosing compact destinations.

Execution-view composition examples for Agent Builder, Integration / ESB, Business Automation and Intelligent BI are documented in [examples/execution-views.md](examples/execution-views.md). These examples intentionally compose generic WDS primitives inside feature-owned screens.
Planned components are not yet shipped.

## Contribution checklist

1. Extend an existing WDS component before adding a duplicate.
2. Add variants only for real semantic or interaction needs.
3. Verify light and dark theme behavior.
4. Verify compact and expanded layout behavior where relevant.
5. Add widget tests for interaction, disabled/loading states, semantics and theme.
6. Keep data fetching, business logic and feature navigation outside WDS.


## Page composition

Use `WdsPageHeader` for page identity, breadcrumbs and primary actions. Use
`WdsFilterToolbar` to arrange search, filter controls and contextual actions;
it wraps controls when the available width is compact. The toolbar intentionally
accepts widgets rather than owning filter state or query semantics.

Use `WdsCommandItem` and `WdsCommandPalette` for searchable actions or
destinations. The host supplies items and handles selection. Commands can be
grouped, described, keyword-indexed and disabled without WDS knowing what
execution means. The Studio "Go to" flow now uses this shared component.


## Data and inspector composition

`WdsDataTable<T>` renders typed columns and accepts controlled sort and selection callbacks. The host owns pagination, query state, sorting algorithms, selection persistence and row actions. Provide a stable `rowKey` together with `onSelectionChanged` to enable row selection; omit them for read-only tables. Loading and empty states are presentation options, not data-fetching behavior.

`WdsInspectorSection`, `WdsFieldGroup`, `WdsPropertyRow` and `WdsFieldMessage` provide consistent inspector composition. They accept ordinary Flutter widgets and validation messages; feature modules own field controllers, validation rules, persistence and domain-specific editors.


## Editor and node composition

`WdsEditorToolbar` composes editor identity, optional center content and action widgets; it adapts to compact widths. `WdsEditorPanel` provides a bounded-height panel layout with optional heading, actions and footer. Docking, resizing and panel persistence belong to the host editor.

`WdsNodeCard` and `WdsNodePort` are visual primitives only. A node receives its title, status, input/output metadata and selection state from the host. Port kinds provide consistent visual differentiation; the host owns graph topology, connection validation, pointer gestures, hit testing, selection and mutations. These components intentionally do not encode Agent Builder, BPMN, DMN or runtime-specific rules.


## Execution monitoring composition

Use `WdsExecutionStatusIndicator` to present a host-mapped run or step status. `WdsExecutionTimeline` renders immutable `WdsExecutionStep` values in the order supplied and reports selection through a callback. Duration, attempt number and supporting messages are optional display metadata. `WdsExecutionEventList` presents immutable `WdsExecutionEvent` rows with severity, source and optional details. The host owns event acquisition, ordering, filtering, pagination, retention, retry semantics and the mapping from its runtime lifecycle to WDS status values. Timestamp and duration helpers are intentionally locale-neutral; applications may format values differently when they need localized output.


## Mode-specific composition

WDS components are shared across modes, but the complete page composition belongs
to each feature module. See [execution view examples](examples/execution-views.md)
for Agent Builder run inspection, ESB connector traces, BPMN process-instance
history and BI pipeline execution. The examples define presentation patterns,
not shared domain models or runtime contracts.


## Component showcase

Mount `WdsComponentShowcase(examples: WdsShowcaseCatalog.examples)` from a
host-owned route to browse the starter catalog. The showcase supports text
search, category filters, responsive card columns, light/dark preview themes
and copyable Dart snippets. Hosts may supply their own `WdsShowcaseExample`
values and remain responsible for route registration and access control. See
[showcase guide](showcase.md) for the extension contract and examples.


## Studio-wide adoption roadmap

See [Studio adoption audit and migration roadmap](studio-adoption-roadmap.md) for the cross-mode adoption matrix, ownership boundaries, migration waves, architecture and sequence diagrams, quality gates, and the required source-wide call-site census.


## Legacy widget migration

The [legacy widget migration inventory](legacy-widget-migration.md) tracks the staged
adoption. `SurfacePanel`, `AppSearchField`, `EmptyState`, `ScreenHeader` and
`KeyValueRow` now delegate to WDS primitives through source-compatible facades.
The Component Catalog and Projects are the first verified P0 catalog migrations.
They compose WDS page, search, surface, badge, inspector, table, action and
empty-state primitives while keeping filtering, provider access, lifecycle
operations and application-level loading/error behavior in their features.
Feature-level call sites can migrate imports incrementally; provider-backed
navigation, global feedback policy and editor decisions remain host-owned.


## Studio adoption and inventory

- [Studio-wide adoption roadmap](studio-adoption-roadmap.md) — cross-mode ownership, migration waves and quality gates.
- [Studio screen inventory](studio-screen-inventory.md) — verified static screen registry, shell call sites and remaining source-wide census work.


## M16 — Integration catalog adoption

Channels and MCP are the first integration-focused screens to adopt WDS
composition. Both use `WdsPageHeader` and semantic `WdsBadge` states;
MCP additionally uses the shared page action buttons, and both use
`WdsEmptyState` where applicable. Their domain layouts intentionally remain
different: Channels retains trigger/account tabs, while MCP retains its
OpenAPI server expansion and lazy tool-detail loading. OAuth, credentials,
allow-lists, endpoint security, import/preview and lifecycle operations remain
owned by their feature modules. See the [screen inventory](studio-screen-inventory.md)
for the verified migration scope.


## M17 — Secrets and connection settings

The Secrets and Settings screens adopt WDS page, filter, field-group, surface,
button, badge and empty-state primitives. WDS renders only metadata and status:
secret values remain write-only and never enter WDS state; bearer tokens remain
obscured and are retained only by the existing in-memory API configuration.
Secret storage, key rotation, reveal policy, credential lifecycle, API
configuration and connection testing remain feature-owned. See the
[Studio screen inventory](studio-screen-inventory.md) for the verified scope.
## M18 — Workspace and configuration adoption

Workspace export/import, release planning, built-in and server-driven template
creation, result reports, and environment Configuration now compose WDS page,
surface, action, section, status and empty-state primitives. WDS remains a
presentation layer: dry-run/apply eligibility, destructive confirmation,
bundle validation, trigger safety, release-plan invalidation, credential-name
validation, configuration scope and persistence remain feature-owned. See the
[Studio screen inventory](studio-screen-inventory.md) for the verified scope
and regression coverage.
## M19 — Runs and Tasks adoption

Runs and Tasks now use shared WDS page, filter, search, surface, status, timeline, badge and action primitives. The Runs screen adapts server run and event states to WDS presentation types without changing API models; unknown event types remain neutral. The Tasks screen uses semantic kind/overdue badges while keeping due-time ordering and task/message operations in the feature. See the [Studio screen inventory](studio-screen-inventory.md) for ownership boundaries and regression coverage.


## M20 — Dashboard overview adoption

The Dashboard uses WDS for page identity and refresh action, compose-mode
context badge, shared surfaces, section heading, empty state and recent-run
execution status. The KPI card remains a dashboard-owned composition because
its metric semantics and layout are currently specific to the overview.

The dashboard feature continues to own API aggregation, provider refresh,
first-run progress, connection recovery, navigation, recent-run selection and
the mapping from API run states to neutral WDS execution states. WDS does not
fetch metrics, infer health, or own navigation.


## M21 — Declarative UI Surfaces adoption

The Surfaces workbench uses `WdsPageHeader`, `WdsSectionHeader`,
`WdsButton`, `WdsBadge`, `WdsSurface`, `WdsEmptyState` and
`WdsFieldMessage` for its editor/preview composition, validation state and
actions. The editor and preview switch from a side-by-side layout to a stacked
layout at compact widths.

The dynamic `SurfaceView` renderer uses semantic WDS badges, property rows
and cards. Its domain renderer remains feature-owned: JSON parsing and size
limits, server validation, allowed action mapping, task-action authorization,
navigation allow-listing and safe rendering rules do not move into WDS.
Pasted surfaces remain read-only; they cannot complete tasks, fetch images or
submit arbitrary actions.


## M22 — Integration resource surface consistency

Channels trigger/account resources and MCP server expansion cards now use
`WdsSurface`, completing the shared visual framing for these integration
catalogs. The trigger empty state also uses `WdsEmptyState`. Existing
`WdsPageHeader`, `WdsBadge` and MCP page-action composition is retained.

This is a presentation-only alignment. Channels owns trigger and OAuth
lifecycle, credential references and allow-lists. MCP owns OpenAPI
preview/import, stale-preview invalidation, security declarations, lazy tool
details and server lifecycle. Neither feature's state, authorization, API
access or validation moves into WDS.


## M23 — Feedback and Mapper

Feedback adopts shared page, filter/search, action, surface and empty-state
components while retaining its submission, tenant authorization, masking,
export and status behavior in the feature/repository.

Mapper adopts shared page and pane framing. Its field-mapping and integration
pipeline builders remain specialized feature components; WDS does not own
mapping expressions, transformation semantics, preview requests or result
interpretation. Plugin screens may optionally use WDS, while registration,
discovery, routing and permissions remain host-owned.
