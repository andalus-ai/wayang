# Component contracts and authoring metadata

Wayang exposes two related but distinct catalogs:

- `GET /api/v1/catalog/nodes` describes BPMN element types and process-document configuration.
- `GET /api/v1/catalog/components` describes reusable runtime capabilities and deployable component contracts.

The component endpoint is profile-aware. It combines built-in descriptors with metadata contributed by extensions accepted by the active `WAYANG_PROFILE`. It is read-only and metadata-only: it never loads or executes classes supplied by a caller.

## Descriptor contract

Each descriptor carries a stable namespaced ID, distribution version, kind, executor type, required feature families, exact dependencies, isolation level, capabilities, display name, category, icon token, `configSchema`, and renderer-neutral `uiHints`.

The root configuration schema is an object schema. The built-in validator supports a deliberately bounded subset:

- types: string, integer, number, boolean, object, array, and unions of those types;
- required properties and `additionalProperties=false`;
- enum, minLength, maxLength, pattern, minimum and maximum;
- array items.

This is not full JSON Schema Draft compliance. Unsupported keywords are not interpreted. Components needing advanced constraints should add a dedicated validator rather than weakening validation.

## Authoring contract

Studio clients should treat display name, category, description, and icon as presentation metadata, and `configSchema` as the source of truth for configuration. `uiHints` may suggest generic layout/grouping but must not encode Flutter widget classes or web component names. Unknown properties are rejected when `additionalProperties=false`.

The existing BPMN node catalog remains authoritative for BPMN element configuration. A component descriptor does not replace that catalog or imply that every BPMN element is independently deployable.

## Validation

`ComponentConfigValidator` is a pure runtime utility suitable for draft validation and package compilation. The catalog endpoint does not accept configuration writes and does not execute component code. The release compiler should call the same validator before producing a deployment artifact.


## Workflow-to-release resolution (M0.2.4)

The component catalog is now consumed at two lifecycle boundaries:

1. **Definition validation / publish**: a process is mapped from BPMN element types to stable component IDs, then resolved against the profile-filtered registry. A process cannot be published if one of its required component contracts is unavailable or its dependency closure is invalid. Existing BPMN node configuration validation remains authoritative for per-element configuration; component schemas are metadata contracts and are not substituted for the more detailed node schemas.
2. **Release compilation**: the publisher repeats resolution against the release's selected profile and extension set. It emits `src/main/resources/wayang/component-lock.json`, a deterministic dependency-first lock containing component IDs, exact versions, executor types, isolation levels, feature requirements, capabilities and declared dependencies.

The release metadata at `src/main/resources/wayang/release.json` records the resolved component IDs and the SHA-256 digest of the component lock. The release plan also returns the resolved component IDs so the Studio can preview the dependency set before generating the archive.

Current BPMN mapping:

| BPMN element family | Component ID |
|---|---|
| Start/end, gateways, timer and message events | `wayang.bpmn.control-flow` |
| Service task | `wayang.service.connector` |
| Business rule task | `wayang.dmn.decision` |
| Agent task | `wayang.agent.invoke` |
| EIP task | `wayang.eip.pipeline` |
| User task | `wayang.human.task` |

The lockfile is a component contract lock, not yet a minimal Maven/JAR dependency graph. Existing release generation still controls Maven module selection through connector and multimodal usage. Component-to-artifact coordinates and transitive artifact closure are a later packaging increment; this implementation does not pretend that a component ID alone proves a JAR has been pruned from the generated application.
