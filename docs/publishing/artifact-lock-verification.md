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

## Repository policy boundary

`RepositoryPolicy` is an immutable HTTPS repository allow-list. It rejects duplicate identifiers and normalized URLs, non-HTTPS URLs, embedded credentials, query strings and fragments. Candidate URLs must match a configured origin and path boundary.

## Maven coordinate location stage

`MavenArtifactLocationResolver` converts validated Maven coordinates into the conventional repository layout for POM, JAR, and SHA-256 sidecar locations. It returns one location set per configured repository, preserving repository priority, and checks every generated URL against `RepositoryPolicy`.

This stage is intentionally a pure URL planner: it does not make HTTP requests, download artifacts, follow redirects, or trust checksum sidecars. The next fetch/install stage must revalidate every redirect target, impose connection/read/size limits, verify downloaded bytes against a trusted digest or signature policy, and only then expose artifacts to packaging. A sidecar served by the same repository is useful for transport-integrity checks but is not an independent trust anchor.

## Verified artifact cache

`VerifiedArtifactCache` is the byte-fetch stage for callers that already possess an authenticated expected SHA-256 digest. It checks the repository allow-list before transport, uses a JDK HTTP client configured not to follow redirects, applies a request timeout and maximum byte count, and verifies the downloaded bytes before atomically publishing them into a content-addressed cache.

Cache entries are re-hashed before reuse. Failed downloads and digest mismatches do not become visible cache entries. The expected digest must come from a separately authenticated release manifest or equivalent trust source; this class intentionally does not trust a checksum sidecar fetched from the same repository. The cache does not yet provide signature verification, cross-process locking, eviction, or a complete Maven dependency resolver.
