# WDS accessibility, theme and responsive audit

## M8 scope

This audit is a targeted baseline for the shared Wayang Design System. It
focuses on reusable component contracts rather than attempting to certify
every feature screen.

## Findings addressed

### Navigation theme leakage

The desktop rail and compact navigation bar previously used fixed dark-surface
colors. That made them inconsistent when embedded in the light theme. Both now
consume the active `ThemeData.colorScheme` for surface, foreground, outline,
selected state and section-label colors. Brand gradient remains an intentional
brand token.

The compact navigation component now returns an empty widget when the host
supplies no destinations, avoiding an invalid selected-index state.

### Semantic navigation state

Rail destinations expose their visible label, button role and selected state
through semantics. Tooltips remain available for compact/icon-only navigation.
Navigation continues to use Flutter's standard focus, keyboard activation and
ink response behavior.

## Verification checklist

For each WDS component, review:

- **Semantics:** meaningful labels for icon-only actions; selected/disabled
  state exposed to assistive technology; decorative icons excluded where they
  add no information.
- **Keyboard and focus:** all actionable controls reachable by keyboard;
  Enter/Space activation; visible focus indication; no pointer-only behavior.
- **Contrast:** text and control boundaries checked against both light and dark
  semantic color schemes, including disabled and selected states.
- **Text scaling:** layouts tested with large system text; avoid fixed-height
  text containers and preserve readable truncation/overflow behavior.
- **Responsive layout:** test narrow phone, tablet, standard desktop and wide
  canvas constraints; controls wrap or move into host-owned overflow patterns.
- **Touch targets:** interactive targets should generally meet the platform
  comfortable target size; dense exceptions must be deliberate and documented.

## Component ownership boundary

WDS provides visual semantics and accessible interaction primitives. Host
features remain responsible for route changes, focus restoration after route
transitions, responsive navigation item selection, authorization, data loading
and domain-specific keyboard shortcuts.

## Remaining audit work

This is not a complete WCAG 2.2 AA certification. Follow-up work should add
automated contrast checks for the complete palette, golden tests at supported
breakpoints, large-text test coverage, and a component showcase that exposes
light/dark, enabled/disabled, hover/focus/pressed and loading states.
