# Release artifact resolution

Wayang release generation now emits two separate locks:

- `component-lock.json` records workflow-level component contracts.
- `artifact-lock.json` records the internal Wayang Maven modules selected by the packaging catalog, their versions, direct internal dependencies, roots, and a canonical SHA-256 digest.

The artifact catalog is intentionally located in `wayang-publish`. Maven coordinates are a packaging concern and must not leak into the runtime-neutral `ComponentDescriptor` SPI in `wayang-runtime`.

## Current resolution model

The generated standalone/service project uses `wayang-embed` as its entry-point artifact. The Gamelan target uses `wayang-gamelan-worker`. Component mappings add relevant modules such as `wayang-connectors` and `wayang-multimodal`. The resolver expands the audited internal module dependency graph, fails closed for an unmapped component, and emits stable sorted output.

The lock currently covers **internal Wayang artifacts only**. It does not resolve external Maven dependencies, repository metadata, classifiers, exclusions, optional scopes, or dependency-management/BOM mediation. It is therefore not a complete Maven lockfile or SBOM.

## Important current limitation

The existing POM graph is not yet component-minimal. `wayang-embed` depends on `wayang-runtime`, and `wayang-runtime` currently depends on the BPMN, DMN, EIP, and agent modules. Consequently even a simple release currently resolves those modules transitively. The lock records that fact instead of claiming that those modules have been pruned.

The generated POM still uses its existing target-specific dependency declarations. The artifact lock is currently an auditable resolution contract and metadata output; it does not yet rewrite the POM from a complete Maven dependency graph.

## Next steps

1. Add explicit artifact contribution metadata for third-party extensions, without coupling runtime SPI to Maven.
2. Parse effective Maven POM models (including dependency management, exclusions, scopes, optional dependencies, and classifiers) rather than maintaining a hand-copied graph.
3. Reconcile the resolved artifact graph against the generated POM and fail if they diverge.
4. Split the runtime into smaller deployable modules only where code/package boundaries permit it, then prove that a minimal workflow excludes unrelated runtime features.
5. Generate a complete CycloneDX SBOM from the effective Maven graph and verify package digests/signatures.
