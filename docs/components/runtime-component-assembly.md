# Runtime component assembly

Wayang activates component executors from extensions already installed on the runtime class path.

## Startup sequence

1. Load `WayangExtension` providers with Java `ServiceLoader`.
2. Filter extensions using the selected runtime profile.
3. Build the profile-scoped metadata registry from built-in descriptors and enabled extensions.
4. Resolve the requested component IDs to their exact dependency-first closure.
5. Collect executor bindings from enabled extensions.
6. Validate that every resolved component has exactly one binding, with the exact descriptor version and executor family.
7. Publish an immutable `ComponentExecutorCatalog` for runtime dispatch.

Use `ComponentRuntimeAssembly.activate(profile, classLoader, componentIds)` for classpath discovery, or the overload accepting an existing `Extensions` object for embedded/test deployments.

## Extension contract

An extension contributes metadata and executable bindings together:

- `components()` describes IDs, versions, executor families, dependencies, configuration schemas and isolation metadata.
- `componentExecutors()` supplies the implementation binding for each component it contributes.

A binding is accepted only when its component is in the selected dependency closure, its version exactly matches the descriptor, and its executor family matches `executorType`.

## Failure behavior

Activation fails before workflow execution when a selected component is unavailable in the profile, a dependency cannot be resolved, an executor is missing, a binding is duplicated, or the version/family does not match. Extra bindings outside the selected closure are rejected; merely placing an extension on the class path does not make all of its components executable.

## Trust and isolation boundary

This first assembly implementation is for trusted, installed Java extensions. It does not download artifacts, mutate the class path, hot-load arbitrary code, or sandbox extension code. An `IsolationLevel` declaration is metadata, not an isolation mechanism. Separate-process, container and remote executors require dedicated transport, identity, timeout, cancellation, health and payload contracts before they can be considered isolated.

Standalone runtimes may use the same assembly API without connecting to Wayang Console. A missing or unavailable management plane must not be a prerequisite for local component activation.
