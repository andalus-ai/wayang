# Wayang Extension Platform and Collaborator Marketplace

> Status: **target architecture / proposed roadmap**. Wayang already has a runtime extension SPI, plugin manifest model, manifest/component consistency validation, in-memory dependency registry and release component lockfile. This document extends those foundations into a secure extension distribution and collaborator ecosystem. It does not claim that marketplace publishing, package installation, signature verification, sandboxing, dynamic activation or remote worker provisioning is implemented.

## 1. Product direction

Wayang should be an extensible platform whose ecosystem can grow beyond the built-in features maintained by the core team. Collaborators should be able to build, test, publish, distribute and maintain reusable extensions for every Studio mode.

The marketplace is not just a gallery of connectors. It is the distribution and trust layer for versioned Wayang capabilities.

Examples:

- **Agent Builder:** model providers, tools, memory providers, RAG retrievers, evaluators, agent templates and guardrail packs.
- **Integration / ESB:** HTTP, Kafka, AMQP, SFTP, database, SaaS and protocol connectors; EIP processors; endpoint adapters.
- **Business Automation:** BPMN/DMN domain extensions, task handlers, forms, decision libraries and engine adapters.
- **Intelligent Business Automation:** reusable industry solution packs, cross-domain templates, policy packs and orchestration adapters.
- **Creative Content (future):** media nodes, model adapters, image/audio/video processors, asset providers and GPU worker images.
- **Platform-wide:** Studio panels, inspectors, palettes, validators, schemas, templates, documentation and observability exporters.

A single extension package may contribute several components and several contribution types. A plugin is a package/lifecycle boundary, not a single node.

## 2. Keep five concerns separate

Do not build one monolithic `PluginManager` that owns every part of the ecosystem.

| Concern | Responsibility | Source of truth |
|---|---|---|
| Extension contract | IDs, manifest schema, compatibility, contribution and permission vocabulary | Versioned Wayang extension API / shared Core contracts |
| Runtime discovery and activation | Discover packaged extensions, validate descriptors, build registries and manage lifecycle | Wayang runtime |
| Artifact repository | Store immutable package bytes, digests, signatures, SBOM and provenance | OCI-compatible or equivalent artifact storage |
| Marketplace catalog | Search/listing, publisher pages, categories, reviews, channels, deprecation and policy metadata | Marketplace service |
| Installation and governance | Resolve, verify, approve, pin, stage, activate, update, revoke and audit | Local installer or Console/control-plane service |

The marketplace catalog must never execute extension code. The artifact repository stores immutable bytes; the catalog describes and indexes them. Runtime activation occurs only in the target runtime or worker after trust and compatibility checks.

## 3. Extension contribution model

A package can contribute one or more of these capability families:

| Contribution | Examples | Execution location |
|---|---|---|
| Node/component | Typed node descriptors and executable component bindings | Built-in runtime, isolated worker or remote worker |
| Connector/trigger | Protocol, SaaS, event source, poller, webhook | Runtime or worker |
| Domain pack | Document kinds, schemas, validators, migrations, compiler adapters | Design-time and/or runtime |
| Studio contribution | Palette group, inspector, form schema, visualization, specialized editor | Studio |
| Template/solution | Starter projects, workflows, agents, integration blueprints | Design-time only |
| Engine adapter | Andalus, Gamelan, BPMN/DMN or integration engine adapter | Runtime |
| Worker image | Prebuilt isolated execution service | Separate process/container/cloud workload |
| Policy/observability | Policy rules, audit/exporter integrations, metrics/traces | Design-time, control-plane or runtime |

Each contribution declares its API contract, dependencies, permissions, compatibility, lifecycle and delivery artifact. Design-time-only assets must not force runtime dependencies into the release.

### 3.1 Extension bundle composition

Treat a marketplace extension as a logical product with one or more immutable artifacts:

- `manifest.yaml`: identity, publisher, version, contribution inventory, compatibility, dependencies, permissions and license.
- `schemas/`: JSON Schemas and domain document schemas.
- `descriptors/`: node, port, component, connector and editor descriptors.
- `studio/`: declarative editor contributions or a separately signed UI bundle.
- `runtime/`: packaged JVM extension or build-time module reference.
- `workers/`: OCI image references and supported worker protocol versions.
- `templates/`: optional templates and examples.
- `docs/`: documentation, setup instructions and support policy.
- `checksums/`, `sbom/`, `provenance/`, `signatures/`: supply-chain evidence.

These are logical bundle areas, not a required archive layout. Large runtime and worker artifacts may be published as separate linked artifacts so users install only what their target requires.

## 4. Manifest direction

The existing `PluginManifest` already models plugin ID/version, API version, publisher, runtime compatibility, component inventory, dependencies, permissions and license. Keep it as the compatibility starting point and evolve it additively. Do not introduce a second unrelated marketplace manifest.

The next manifest revision should add explicit metadata for:

- `displayName`, `description`, `categories`, `tags`, `icon`, `documentationUrl`, `sourceUrl`, `supportUrl`;
- publisher identity/namespace and verified publisher key references;
- contribution inventory with kind, ID, version, API compatibility and artifact reference;
- Studio API compatibility and declarative UI contribution inventory;
- engine/API compatibility and runtime target matrix;
- dependency constraints, optional features and conflict declarations;
- permissions by resource/action and secret scopes;
- network egress domains/protocols, file/storage scopes and resource limits;
- artifact digests, signature/attestation references, SBOM and license expression;
- lifecycle channel, deprecation and minimum supported upgrade path.

Example (illustrative only; field names require schema/API review before implementation):

```yaml
apiVersion: wayang.extension/v1
kind: Extension
metadata:
  id: com.example.crm
  version: 1.4.2
  publisher: example-org
  displayName: CRM integration pack
  license: Apache-2.0
compatibility:
  wayangApi: "^1.0"
  studioApi: "^1.0"
  runtimeApi: "^1.0"
  engines:
    - id: wayang.integration
      version: "^1.0"
contributes:
  components:
    - id: com.example.crm.customer.lookup
      version: 1.4.2
  connectors:
    - id: com.example.crm.connector
      version: 1.4.2
  studio:
    - id: com.example.crm.customer-lookup-editor
      kind: declarative-form
artifacts:
  runtime:
    uri: oci://registry.example.com/wayang/com-example-crm-runtime:1.4.2
    digest: sha256:...
  studio:
    uri: oci://registry.example.com/wayang/com-example-crm-studio:1.4.2
    digest: sha256:...
permissions:
  - resource: secret:crm-credentials
    actions: [read-reference]
  - resource: network:api.example.com
    actions: [connect]
  - resource: data:crm.customer
    actions: [read, write]
dependencies: []
security:
  sbom: sbom.spdx.json
  provenance: provenance.intoto.jsonl
  signature: signatures/extension.sig
```

The marketplace's publisher profile and signature identity must be cryptographically linked. A plain `publisher` string in a manifest is not proof of publisher identity.

## 5. Identity, namespaces and versioning

- Extension IDs must be stable, globally unique, lowercase and namespaced (for example `com.vendor.product` or an approved organization namespace).
- Component/node/connector IDs are independently stable and owned by one extension publisher namespace.
- Published versions are immutable. Never overwrite an artifact at an existing version.
- Use semantic versions for package and contribution APIs, with explicit compatibility ranges.
- Production releases pin exact extension versions and artifact digests in the deployment lock.
- A project may request a compatible range during authoring, but compilation resolves it to an exact immutable version.
- Updates must be explicit; no silent `latest` resolution during runtime startup.
- Support release channels such as stable, preview and development, but do not let a channel change the bytes behind an already-resolved digest.
- Provide deprecation, yanking and revocation metadata. Yanking prevents new resolution; revocation can block activation of a compromised artifact under policy.

Avoid unbounded dependency ranges and ambiguous Maven/npm-like syntax. The resolver must have a documented, tested grammar and deterministic resolution.

## 6. Publisher collaboration lifecycle

1. A collaborator creates an extension project using a Wayang extension SDK/template.
2. They declare nodes, ports, schemas, component descriptors, permissions and compatibility.
3. They implement runtime behavior using the supported SPI or a remote worker protocol.
4. They add Studio contributions using declarative schemas or the approved UI extension API.
5. They run local validation, compatibility fixtures, security checks and integration tests.
6. They publish an immutable candidate artifact with digest, SBOM, provenance and signature.
7. The marketplace validates the manifest, ownership, signature, compatibility, dependencies, licenses and policy.
8. The extension enters review/verification; public listing is a separate decision from private distribution.
9. Consumers install from a trusted source, review requested permissions and pin a version.
10. Wayang resolves the dependency graph and creates an installation/release lock.
11. The target runtime stages the package, verifies it again, activates it and reports health.
12. Updates, rollback, deprecation and security revocation are managed with audit history.

Public listing should not be required for private organization extensions. Support private catalogs and organization-scoped distribution from the beginning of the registry design, even if the first implementation uses a local file or OCI registry.

## 7. Trust and security model

Marketplace publication is not a security guarantee. Treat every extension as untrusted until independently verified and authorized.

### 7.1 Required controls

- Verified publisher identity and namespace ownership.
- Cryptographic signing of immutable artifacts; verify signatures at publish, install and activation boundaries.
- Digest pinning and tamper detection.
- SBOM generation and dependency/license analysis.
- Vulnerability and malware scanning appropriate to artifact type.
- Reproducible or attestable build provenance where feasible.
- Compatibility and API conformance tests.
- Explicit least-privilege permissions, with tenant/admin approval for sensitive grants.
- Network egress allowlists, secret-reference scopes, file/storage boundaries and resource quotas.
- Audit events for install, approve, activate, update, disable, revoke and uninstall.
- Revocation and emergency disable mechanism.
- No secrets embedded in package manifests, templates, images or logs.

### 7.2 Execution isolation

Do not execute third-party arbitrary code in the Studio server or core runtime process as a security boundary.

| Extension type | Initial policy |
|---|---|
| Declarative schemas, templates and metadata | Safe to parse only with bounded parsers, schema validation and size limits |
| Studio UI | Prefer declarative forms. If executable UI is later supported, use a separately versioned, sandboxed origin/iframe and strict CSP; never unrestricted scripts in the Studio origin |
| Trusted JVM extension | Explicit administrator trust; package into a build-time or distribution artifact; do not call it sandboxed |
| Untrusted/third-party executable | Run out-of-process in a restricted worker/container or a separately evaluated sandbox technology |
| Remote worker | Mutual authentication, authorization, signed image/artifact, capability negotiation, bounded payloads, deadlines, cancellation and audit |

Quarkus build-time extensions and native executables cannot promise arbitrary hot installation of JVM classes. Use build-time packaging for native-compatible artifacts, or isolated/remote workers for independently deployable extensions. Do not promise safe classloader unload without lifecycle and leak tests.

### 7.3 Permission model

Permissions should be machine-readable and granular. Examples:

- `secret:<reference>`: read a secret reference or request a scoped credential, not enumerate all tenant secrets.
- `network:<host-or-service>`: connect to explicitly allowed destinations.
- `storage:<bucket-or-namespace>`: read/write scoped assets.
- `data:<resource>`: read/write a declared data resource.
- `event:<topic>`: publish/subscribe to named topics.
- `identity:<capability>`: request only approved identity claims/actions.

Permissions are reviewed and granted by the target organization/tenant. A marketplace approval does not grant runtime permissions automatically.

## 8. Studio extension API

Studio must not depend on a plugin's Java implementation. It consumes a stable, versioned design-time contribution contract.

Start with declarative contributions:

- palette categories and node descriptors;
- configuration forms driven by JSON Schema plus Wayang UI hints;
- typed port and connection metadata;
- inspector sections and validation messages;
- documentation links and examples;
- templates and starter projects;
- previews/visualizations from constrained data contracts.

Specialized editors may later use a sandboxed UI extension API. Define message schemas, capability permissions, CSP, origin isolation and API versioning before enabling executable frontend bundles.

The Studio must continue to operate when an optional extension UI is unavailable. Unknown extension nodes should remain visible as unresolved placeholders, preserve their serialized configuration and ports, and show a clear compatibility diagnostic; never silently delete or rewrite them.

## 9. Runtime packaging and deployment

The extension platform must align with the two-phase Wayang lifecycle.

### Phase 1 — authoring

Studio discovers available extensions from configured catalogs and local development sources. It validates document/node compatibility, records requested extension constraints and exposes permissions/configuration requirements.

### Phase 2 — compile and deploy

The compiler resolves the exact transitive dependency closure for the published project:

- document kinds and schema migrations;
- node/port descriptors and validators needed for compilation;
- component and connector implementations actually referenced;
- engine adapters and required runtime APIs;
- configuration schemas and secret references;
- worker images and capability requirements, where used.

The release lock records extension ID/version/digest, component IDs/versions, dependency graph, engine/API compatibility, permissions, artifact digests and provenance references.

| Edition/target | Extension behavior |
|---|---|
| Community | Built-in and explicitly installed local/classpath extensions in the all-in-one distribution. No marketplace service or dynamic hot installer is required. Keep the SPI usable for community collaborators. |
| Pro | Resolve and package only required signed extensions into standalone/local or microservice artifacts; support controlled updates and rollback. |
| Enterprise | Add organization/private catalogs, allowlists, approval workflows, fleet rollout, staged deployment, revocation and policy enforcement across distributed runtimes. |

Edition entitlements govern operational distribution and fleet management, not the basic ability to author or implement an extension. Do not make the Community core depend on a hosted marketplace.

### Runtime execution choices

- Embedded: trusted and packaged with the runtime.
- Isolated local process/container: separate lifecycle and resource limits.
- Remote worker: registered, authenticated service with declared capabilities.
- Cloud-native worker: orchestrated workload with resource policy and rollout controls.

These modes are execution targets, not plugin categories. The deployment compiler selects only a mode supported by the extension, target, security policy and available infrastructure.

## 10. Marketplace service boundaries

A marketplace can be introduced incrementally as separate services or modules:

- **Catalog API:** search, categories, tags, listing metadata, version history and compatibility filters.
- **Publisher service:** organization membership, namespace ownership, signing-key registration and publisher verification.
- **Artifact repository:** immutable package and worker artifact storage.
- **Verification pipeline:** schema validation, signature/provenance checks, SBOM/license/vulnerability analysis, conformance tests and review status.
- **Policy service:** public/private visibility, allowlists, deny lists, license policy and trust levels.
- **Install/resolution client:** dependency resolution, artifact download, verification and local lock generation.
- **Audit/telemetry:** installation and lifecycle events, downloads and compatibility reports.

Marketplace availability must not be in the runtime's synchronous execution path. Already installed runtimes should continue to execute if the public marketplace or catalog is offline.

A private organization catalog should use the same manifest and artifact format as the public catalog. This avoids creating incompatible enterprise-only plugin packaging.

## 11. Recommended API/lifecycle boundaries

Keep the following conceptual APIs separate (names illustrative until the shared Core contract is designed):

- `ExtensionDescriptorProvider`: contributes immutable metadata.
- `ExtensionCatalog`: discovers metadata from built-in, local or remote catalogs.
- `ExtensionResolver`: resolves version and dependency constraints deterministically.
- `ExtensionVerifier`: validates digest, signature, provenance, compatibility and policy.
- `ExtensionInstaller`: stages immutable artifacts in a local content-addressed store.
- `ExtensionActivator`: activates only supported package types and manages lifecycle.
- `ExtensionLifecycleManager`: drain, disable, rollback and close owned resources.
- `ExtensionWorkerClient`: invokes separately deployed workers through a versioned protocol.

The existing `WayangExtension`, `PluginManifest`, `PluginManifestValidator`, `PluginRegistry`, `ComponentRegistry` and `ComponentLockfile` should be characterized and incrementally adapted. Avoid duplicate registries or replacing the working startup/classpath path prematurely.

## 12. Compatibility and failure behavior

- Reject unknown or incompatible mandatory manifest contracts with actionable diagnostics.
- Fail closed on invalid signatures, digest mismatch, revoked artifacts, unsupported API versions, dependency conflicts and missing required permissions.
- Optional missing contributions must not make unrelated modes unavailable.
- A missing Studio editor must not erase the underlying document node.
- A runtime artifact must not fetch an unpinned dependency at startup.
- Failed activation must leave the prior healthy version active or restore the previous known-good deployment.
- Uninstall must reject while published projects/runtimes still depend on the extension, unless an explicit migration/removal plan is applied.
- Deactivation drains active invocations before resource cleanup; it must not interrupt durable workflow state without an engine-owned recovery procedure.
- Never allow extension code to redefine an existing component ID silently.

## 13. Delivery roadmap

### M1 — Contract foundation (current)

- Characterize existing manifest, registry, descriptor and lockfile contracts.
- Define stable extension IDs, versioning, contribution kinds and compatibility rules.
- Define neutral node/port descriptor and capability-pack contribution model.
- Add manifest schema fixtures and compatibility tests.
- Keep the current classpath/ServiceLoader path working.

### M2 — Local developer ecosystem

- Extension SDK, Maven archetype/template and sample extension repositories.
- Local directory/package catalog.
- CLI commands to validate, package, sign (developer key), inspect and test.
- Contract test kit and sample packs for connector, agent node, storage/RAG and Studio declarative form.
- Deterministic local install lock; no arbitrary hot loading.

### M3 — Trusted artifact distribution

- OCI-compatible immutable artifact storage.
- Publisher namespace and signing-key registration.
- Signature/digest verification, SBOM, provenance and license metadata.
- Catalog search/listing and version resolution.
- Private catalogs and organization allowlists.

### M4 — Controlled installation and lifecycle

- Local installer and content-addressed cache.
- Compatibility/dependency resolution, permission review and staged activation.
- Runtime drain, health, rollback and revocation.
- Isolated worker packaging for third-party executable code.

### M5 — Public collaborator marketplace

- Public publisher onboarding and verified identities.
- Extension submission/review/moderation.
- Ratings/reviews, support policy, release channels and deprecation.
- Security response, vulnerability reporting and artifact revocation.
- Revenue sharing or paid listings only after legal, billing, licensing and support models are defined.

### M6 — Enterprise ecosystem

- Private/curated catalogs, internal publisher policies and approval workflows.
- Fleet-wide rollout, staged deployment, allowlists and policy enforcement.
- Organization-level audit, compliance reports and support/SLA metadata.

## 14. Initial acceptance criteria

- Existing built-in and classpath extensions still load as before.
- Existing plugin manifest and registry tests remain compatible.
- A package can declare multiple contribution types and multiple components.
- The same extension can publish design-time metadata separately from runtime artifacts.
- Unknown or incompatible extensions fail with actionable diagnostics.
- Exact versions and digests are pinned into release locks.
- Runtime startup never resolves an unpinned `latest` dependency from a marketplace.
- Untrusted executable code is not loaded into the Studio/core process.
- Permissions are explicit, reviewable and enforced at the target boundary.
- An optional marketplace outage does not affect already installed runtime execution.
- Community works offline and without a marketplace service.
- Pro can package a minimal extension dependency closure into an independent runtime.
- Enterprise can add private catalog and fleet governance without changing extension package format.
- A third-party collaborator can build and validate an extension using documented SDK and contract fixtures.

## 15. Relationship to the M1 foundation audit

This extension platform depends on the M1 foundation contracts:

- Project/document envelope and immutable versions.
- Extensible document-kind/schema registry.
- Neutral node/port descriptors separate from runtime component descriptors.
- Capability-pack manifest and designer contribution contract.
- Deterministic artifact dependency lock and provenance.

It also extends the existing plugin contract documents:

- `docs/COMPONENT-PLUGIN-CONTRACT.md`
- `docs/PLUGIN-REGISTRY.md`
- `docs/COMPONENT-IDENTITY.md`

Keep these focused documents consistent with this roadmap. The extension platform is shared infrastructure across all Wayang modes; individual mode packs must not create their own incompatible plugin or marketplace mechanisms.
