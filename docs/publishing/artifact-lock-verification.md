# Release artifact lock verification

Generated releases contain `src/main/resources/wayang/artifact-lock.json`. It records the internal Wayang Maven coordinates selected by the release catalog, their compile-scope dependency edges, and the release roots.

## Verification contract

`ReleaseArtifactResolver.verify(String)` validates:

- the `wayang.artifact-lock` format and supported format version;
- the SHA-256 digest over the canonical lock document with the `sha256` field omitted;
- unique artifact coordinates and non-empty, unique roots;
- that every root exists in the locked Wayang artifact set;
- that every dependency edge points to an exact locked coordinate and uses the supported compile scope;
- that every locked artifact is reachable from a root and the graph contains no dependency cycle.

The publisher runs this validation before it emits a release archive. The verifier is also available to future runtime launchers, deployment agents and external release tooling that need to reject a modified or incomplete coordinate lock before resolving dependencies.

## Integrity boundary

This digest protects the lock document, not the bytes downloaded from a Maven repository. It is not a substitute for repository-provided checksums, artifact signatures, trusted repository configuration or SBOM verification. Binary SHA-256 verification belongs in the later artifact-fetch/install stage, where the actual downloaded bytes are available. The current release catalog also describes the published module dependency graph; it does not claim that the present `wayang-runtime` Maven dependency tree is already pruned to only the selected feature modules.
