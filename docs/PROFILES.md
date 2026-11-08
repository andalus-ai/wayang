# Profiles and extensions

Wayang is one codebase with several deployment shapes. `WAYANG_PROFILE` selects which feature families a
runtime serves. BPMN control flow (events, gateways, timers, messages) is always present.

| Profile | Features | Use it as |
|---|---|---|
| `full` (default) | service, dmn, eip, agent, human, media | the whole assistant / agent-builder platform |
| `automation` | service, dmn, human | business-automation / BPM platform |
| `integration` | service, eip | integration platform (pipelines, connectors, triggers) |
| `agentic` | service, agent, human, eip, media | personal assistant / agent builder |
| custom | e.g. `dmn,service` | any comma list of `service,dmn,eip,agent,human,media` |

A typo is an error, never "everything enabled".

## What is enforced (implemented, unit-tested)

- `Profile.violations(plan)` names every node a profile cannot run.
- `EmbeddedRuntime` (standalone/service releases) refuses to load a release containing such a node.
- `NodeRunner.withProfile` drops disabled handlers; a node that still reaches the runner fails with
  "needs wayang-agent, which the 'automation' profile does not enable".
- The Gamelan worker and the design-time engine apply the same profile to their runner.
- Extensions that `requires()` a disabled feature are not loaded.
- Component execution capability declarations contributed by loaded extensions are exposed separately from
  component metadata and can be validated against exact component IDs and versions.

- Release generation takes `profile` in the options (`{"profile":"integration"}`): a bundle needing a disabled feature
  is refused up front, and the generated Dockerfile sets `WAYANG_PROFILE`.

- The server (`WayangApi.withProfile`, default from `WAYANG_PROFILE`) hides disabled node types from the palette
  (`/node-catalog`), reports them as validation errors (so they cannot be published) and offers no starter
  templates without the agent feature. Studio needs no change: it renders whatever the catalog returns.

Not yet: and dropping the
disabled `@Executor` classes from the Gamelan worker's registration (today a disabled type fails at run time
instead of never being claimed by that worker). The enforcement points above are the hooks for these.

## Deployment shapes

- **All-in-one, local:** `WAYANG_PROFILE=full`, STANDALONE release (or the server) on one machine.
- **Integration-only / automation-only:** same binaries, narrower profile; also the right base for slimmer images.
- **Distributed:** on the GAMELAN target each feature is a separate executor type
  (`wayang-agent`, `wayang-eip`, `wayang-dmn`, `wayang-human`, `wayang-service`, `wayang-bpmn`), so a worker
  pool per profile (`agentic` workers scaled for model calls, `integration` workers for pipelines) is the scaling
  unit; Gamelan routes by executor type. Verify claim routing against a real Gamelan before relying on it.

## Extensions (`WayangExtension`, `ServiceLoader`)

Implement `tech.kayys.wayang.runtime.WayangExtension` and list the class in
`META-INF/services/tech.kayys.wayang.runtime.WayangExtension`. An extension may contribute:

- connectors (usable from SERVICE_TASK / EIP / agent tools),
- trigger pollers and webhook providers,
- handlers for custom executor types (a SERVICE_TASK with `executorType: x-...`),
- component descriptors and optional execution-capability declarations.

Names cannot collide with built-ins or each other (start-up error). Connectors receive resolved inputs exactly
like built-ins; secrets stay `${secret:NAME}` references in definitions and bundles.

### Execution capability metadata

`ComponentExecutionCapabilities` declares the modes a component implementation supports: `EMBEDDED`,
`ISOLATED_LOCAL`, `REMOTE`, or `CLOUD_NATIVE`. It also declares state behavior (`STATELESS`,
`EXTERNALIZED`, or `AFFINE`), payload portability, cancellation/idempotency declarations, required worker
capabilities, and resource hints. Remote modes require portable payloads; affine state requires an affinity key.

`ComponentExecutionCapabilityRegistry` validates that declarations refer to an available component at the exact
component version and rejects duplicate declarations. Components without metadata do not implicitly gain remote
execution support. These types are a contract foundation only: there is not yet a placement router, worker protocol,
or dynamic plugin installer.

This is the intended seam for future multimodal and graph-style (ComfyUI-like) nodes: an image/audio/model
node is a handler for its own executor type, with its own feature flag and its own worker pool, so a GPU
runtime scales separately from the BPMN engine. None of those nodes exist yet.
