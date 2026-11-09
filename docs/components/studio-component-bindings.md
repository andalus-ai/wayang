# Studio component bindings

Wayang Studio can bind a BPMN node to an explicit runtime component from the active profile's component catalog.

## Node configuration contract

| Key | Type | Meaning |
| --- | --- | --- |
| `wayang.component.id` | string | Namespaced component identifier |
| `wayang.component.version` | string | Optional exact version pin; Studio pins the catalog version on selection |
| `wayang.component.operation` | string | Optional operation name; defaults to `execute` |

The component binding is additive. Nodes without `wayang.component.id` keep their existing element-type / `DomainHandler` behavior.

## Authoring behavior

- The inspector reads the active runtime's profile-filtered component catalog.
- Selecting a component writes its ID and exact catalog version into the node configuration.
- Clearing the selection removes all three reserved keys and restores the default node handler.
- An unavailable existing ID remains visible as an unavailable selection; Studio does not silently erase it.
- The operation field is only shown for explicitly bound components.

The BPMN document mapper already preserves node configuration, so these keys round-trip through save/load and workspace document operations.

## Validation and release resolution

The API node schema reserves these keys for every element type. Definition validation parses the binding, verifies that the component exists in the active profile, checks an optional exact version pin, and includes explicitly selected component IDs in the component dependency closure. Release lockfiles include these explicit IDs as roots and resolve their exact transitive dependencies.

This contract does not install plugins or hot-load code. The selected component must already be present in the runtime's available catalog and executor bindings.
