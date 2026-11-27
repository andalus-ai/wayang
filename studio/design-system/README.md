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
- studio/lib/design_system/wds.dart: public import surface.
- studio/lib/core/theme.dart remains the ThemeData and brand-color source during
  migration. Existing core/widgets.dart APIs remain compatible.

## Initial component inventory

Implemented in the first increment: WdsSurface, WdsBadge, WdsButton,
WdsIconButton, WdsSectionHeader, WdsSearchField and WdsEmptyState.

Planned follow-up families: application shell and navigation, command palette,
data tables, editor chrome, node/port primitives, inspectors, execution
timelines and mode-specific templates. Planned components are not yet shipped.

## Contribution checklist

1. Extend an existing WDS component before adding a duplicate.
2. Add variants only for real semantic or interaction needs.
3. Verify light and dark theme behavior.
4. Verify compact and expanded layout behavior where relevant.
5. Add widget tests for interaction, disabled/loading states, semantics and theme.
6. Keep data fetching, business logic and feature navigation outside WDS.
