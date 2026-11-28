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
WdsNodeCard and WdsNodePort.

The navigation widgets accept destination data and selection callbacks. The
host application remains responsible for plugin discovery, filtering, routing,
permissions and choosing compact destinations.

Planned follow-up families: execution timelines and mode-specific templates.
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
