# WDS Legacy Widget Migration Inventory

This inventory records the compatibility layer in `studio/lib/core/widgets.dart`
and defines a safe migration sequence. It is intentionally conservative: a
legacy widget is not a duplicate merely because it renders a similar shape.
Some legacy APIs carry feature behavior, theme assumptions or call-site
contracts that must be preserved until their consumers are migrated.

## Current compatibility inventory

| Legacy API | Current implementation | WDS relationship | Recommended action |
|---|---|---|---|
| `SurfacePanel` | Delegates to `WdsSurface` | Already uses WDS | Keep as a compatibility facade; migrate imports gradually |
| `AppSearchField` | Delegates to `WdsSearchField` | Already uses WDS | Keep as a compatibility facade; migrate imports gradually |
| `StatusPill` | Own color-driven badge implementation | Similar to `WdsBadge`, but accepts arbitrary caller-provided color | Do not replace mechanically; add a semantic adapter only after callers are classified |
| `ScreenHeader` | Feature header with optional icon and action list | Overlaps with `WdsSectionHeader` / `WdsPageHeader` | Migrate simple screens; retain feature-owned icon/branding composition where needed |
| `EmptyState` | Legacy empty-state layout and brand styling | Overlaps with `WdsEmptyState` | Migrate call sites after comparing scroll, spacing and message behavior |
| `KpiCard` | KPI-specific metric card | No direct generic WDS equivalent | Keep feature-level; consider a neutral metric-card primitive only if multiple modes need the same contract |
| `LoadingView` | Full-page loading state | No direct generic WDS equivalent | Keep host-level until a reusable loading composition is justified |
| `ErrorView` | Error presentation plus Settings navigation via Riverpod | WDS must not own provider or navigation behavior | Split presentation from host action before any migration |
| `KeyValueRow` | String key/value row with optional monospace value | Overlaps with `WdsPropertyRow` | Migrate compatible inspector call sites; preserve monospace and fixed-width needs |
| `PanelSectionLabel` | Uppercase section label with trailing slot | No exact current WDS equivalent | Candidate for a small neutral section-label primitive if reuse is demonstrated |
| `showConfirmDialog` | Branded confirmation dialog with destructive option | Interaction and app theme policy, not a simple visual alias | Keep host-level; later extract a dialog presentation API only with explicit ownership |
| `showToast` | SnackBar with app-specific success/error icon/color | Feedback and app theme policy | Keep host-level; avoid moving global messenger policy into WDS |
| `confirmDiscardEdits` | Unsaved-change flow with async save callback | Editor workflow behavior | Keep feature/editor-owned; never move the decision flow into generic WDS |

## Migration rules

1. **Preserve compatibility first.** Do not delete or rename legacy public
   classes as part of a visual migration. Keep facades until call sites and
   downstream imports are proven clear.
2. **Migrate by call-site family.** Replace one coherent screen or feature at
   a time, rather than doing a repository-wide textual replacement.
3. **Preserve behavior.** Record callbacks, state ownership, keyboard behavior,
   accessibility labels, loading/error behavior, sizing and theme assumptions
   before changing a widget.
4. **No semantic guessing.** A free-form color in `StatusPill` cannot safely
   be mapped to success/warning/danger without classifying its callers.
5. **Keep feature behavior outside WDS.** Navigation, Riverpod providers,
   confirmation decisions, persistence, editor state and application-wide
   notifications remain with the host or feature module.
6. **Test before removal.** Add widget tests for the WDS replacement and
   representative migrated screens. Remove a compatibility facade only in a
   separate change after a repository search confirms there are no consumers.

## Suggested migration sequence

### Wave A — compatibility facades delegated (implemented)

The legacy `SurfacePanel` and `AppSearchField` wrappers already delegate to WDS.
They remain available to avoid breaking existing feature imports.



- `SurfacePanel` → `WdsSurface`
- `AppSearchField` → `WdsSearchField`

These wrappers should remain source-compatible during gradual import cleanup.

### Wave B — low-risk presentation (adapter implementation added)

The legacy `EmptyState`, `ScreenHeader` and `KeyValueRow` implementations now
compose `WdsEmptyState`, `WdsSectionHeader` and `WdsPropertyRow` respectively.
Their public constructors remain unchanged. `ScreenHeader` retains its optional
brand icon treatment; `KeyValueRow` retains its monospace option and start-aligned
value presentation. Regression widget tests cover these compatibility adapters.

This is an adapter migration, not a verified mass call-site rewrite. Existing
screens can continue importing `core/widgets.dart` while adopting WDS behavior
without immediate source changes.



- `EmptyState` → `WdsEmptyState`, preserving icon, title, message and action.
- `ScreenHeader` → `WdsSectionHeader` or `WdsPageHeader` where the
  existing screen needs no special icon treatment.
- `KeyValueRow` → `WdsPropertyRow` where the value is already a widget or
  can be wrapped without losing monospace formatting.

### Wave C — semantic and feature-specific decisions

- Classify every `StatusPill` call site before designing a status-to-tone map.
- Decide whether `PanelSectionLabel` is genuinely shared or remains local.
- Keep `KpiCard`, `LoadingView`, `ErrorView`, dialogs, toast policy and
  unsaved-edit flows in host/feature code unless repeated cross-mode use proves
  a generic presentation contract.

## Definition of done for each migrated family

- [ ] Existing call sites and imports have been inventoried.
- [ ] Replacement API preserves required visual and interaction behavior.
- [ ] Light and dark themes are checked.
- [ ] Compact width and text scaling are checked where applicable.
- [ ] Semantics and keyboard/focus behavior are checked.
- [x] Widget tests cover the migrated compatibility adapters.\n- [ ] Feature-level regression tests cover representative migrated screens.
- [ ] No feature state, navigation, provider, network or persistence behavior
      has leaked into WDS.
- [ ] Compatibility wrapper remains until all consumers are migrated.

## Non-goals

This inventory does not authorize a mass deletion of `core/widgets.dart`,
does not propose moving app-wide dialogs or notification policy into WDS, and
does not treat every legacy widget as a design-system primitive. The goal is a
measured migration with stable APIs and clear ownership boundaries.
