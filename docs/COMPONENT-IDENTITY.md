# Workflow component identity contract

## Status

Implemented as an additive runtime contract. Studio authoring controls, server-side definition validation, and release-manifest propagation remain follow-up work. This does not implement plugin installation or dynamic hot-loading.

## Reserved node configuration keys

| Key | Type | Required | Meaning |
|---|---|---:|---|
| `wayang.component.id` | string | when explicitly bound | Namespaced component ID |
| `wayang.component.version` | string | no | Exact requested version; omitted uses the resolved descriptor version |
| `wayang.component.operation` | string | no | Operation name; defaults to `execute` |

The ID uses the lowercase namespaced syntax enforced by `ComponentDescriptor`. Version and operation without an ID are invalid. Non-string values are rejected. Operation names are limited to 128 characters.

## Runtime behavior

- Nodes without an ID continue through legacy `DomainHandler` dispatch.
- Nodes with an ID require a configured `ComponentExecutorCatalog`.
- The ID must exist in the catalog's resolved component closure.
- A supplied version must exactly match the catalog descriptor; there is no implicit upgrade or downgrade.
- The operation is passed unchanged to the executor.
- Invalid or unavailable explicit bindings fail closed; there is no handler fallback.

Example:

```yaml
wayang.component.id: wayang.connector.http
wayang.component.version: "2.1.0"
wayang.component.operation: request
url: https://api.example.test/items
method: GET
```

The binding currently lives in the node configuration map. It is not yet a first-class typed BPMN XML or Flutter editor field. Import/export and document transformations must preserve these reserved keys. The next increment will wire Studio catalog selection and publication validation to this contract.

## Compatibility

Existing definitions need no migration. The binding is active only when `wayang.component.id` is present. Production definitions should pin a version. When omitted, execution uses the exact version resolved into the runtime catalog, not a "latest" lookup.
