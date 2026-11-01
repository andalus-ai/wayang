# Wayang Studio

One Flutter app over the Wayang REST API: Process Designer (BPMN), Decision Tables (DMN), Agents/Pipelines
(validated JSON editor), Runs, Task inbox, Projects, Channels (triggers and connected accounts), MCP servers (import OpenAPI), Secrets, Settings. Riverpod, no code generation.

```
flutter create .          # once: generates platform folders (not included)
flutter pub get
flutter run -d chrome --dart-define=WAYANG_API=http://localhost:8080
```

Enter the bearer token under Settings (kept in memory). The tenant is taken from the token by the server.

**Status:** never compiled or run here (no Flutter SDK); see "Verification status" in `docs/ARCHITECTURE.md`.
Run `flutter analyze` and `flutter test` first and expect to fix small things.
