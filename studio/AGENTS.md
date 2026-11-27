# Wayang Studio Contributor Instructions

These instructions apply to the Flutter Studio under this directory. Follow
the repository-root AGENTS.md as well.

## Design system

- Read [../design-system/README.md](../design-system/README.md) before adding
  shared UI or changing global visual behavior.
- Use the public WDS entry point at `lib/design_system/wds.dart`.
- WDS owns reusable presentation primitives, semantic tokens and visual
  patterns. It must not depend on API clients, Riverpod providers, workflow
  engines, persistence, plugin activation or feature navigation.
- Prefer existing WDS components over screen-local duplicates. Add a new
  shared component only when it has a clear reusable contract.
- Use semantic badge tones and button variants; do not use arbitrary colors
  when a semantic role exists.
- Keep `core/theme.dart` as the ThemeData and brand color source during the
  incremental migration. Keep legacy `core/widgets.dart` APIs compatible
  while migrating their implementations to WDS.
- Components must work in both light and dark themes and must not assume a
  fixed viewport width. Use WDS breakpoints and layout tokens.
- Interactive controls require accessible labels/tooltips, keyboard support,
  visible focus, and disabled/loading behavior.
- Keep domain-specific UI in its owning feature or capability mode. BPMN,
  DMN, EIP and agent editors may share primitives but must preserve their
  domain semantics.

## Verification

- Run `flutter analyze` from `studio/`.
- Run `flutter test` from `studio/`.
- Add widget tests for new shared controls, including interaction, disabled
  states, semantics, and light/dark theme behavior where relevant.
- Report exact commands and results. Do not claim verification if Flutter
  tooling or required dependencies are unavailable.
