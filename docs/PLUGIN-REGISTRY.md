# Plugin Registry and Dependency Resolution

> Status: implemented in the runtime metadata layer. This is an in-memory catalog over discovered packages, not an artifact installer or dynamic class loader.

## Registry responsibilities

`PluginRegistry` accepts `PluginPackage` values pairing a validated `PluginManifest` with its contributed `ComponentDescriptor` inventory. Construction validates unique plugin IDs, exact manifest-to-descriptor inventory and versions, unique component ownership, required dependency presence, and installed dependency version constraints.

Optional dependencies may be absent. When present, they are included in the resolved closure and must satisfy the declared range.

## Resolution

`resolve(pluginIds)` returns deterministic dependency-first order and rejects unknown requested plugins and dependency cycles. `ComponentRegistry` remains responsible for profile-aware component visibility and component-level dependency closure.

The bounded version-range evaluator supports exact versions, `*`, caret (`^`), tilde (`~`), and comparator conjunctions (`>=`, `<=`, `>`, `<`, `=`). Unsupported syntax fails closed. It compares numeric major/minor/patch values; prerelease precedence and full npm/Maven range compatibility are not claimed.

## Deliberate boundaries

This increment does not install/remove artifacts, parse manifests from disk, verify publisher identity/signatures, evaluate runtime compatibility expressions, grant permissions, load classes, hot-upgrade plugins, or launch workers. Those require explicit trust, lifecycle, and deployment boundaries. The catalog is an input to a later release compiler, not proof that a plugin is safe to execute.
