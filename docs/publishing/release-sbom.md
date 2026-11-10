# Release software bill of materials

ReleaseArtifactSbom creates a deterministic CycloneDX 1.5 JSON SBOM from two inputs that must already have been verified:

1. ReleaseArtifactResolver.VerifiedLock, which supplies the complete coordinate graph.
2. ReleaseArtifactDigestManifest.VerifiedManifest, which supplies the signed POM and JAR SHA-256 values.

The builder rejects inputs bound to different lock digests. Each locked Maven artifact becomes a CycloneDX library component with its Maven Package URL and the JAR SHA-256 as the component hash and the POM SHA-256 as a namespaced component property. Dependency relationships are copied from the verified lock; no repository POM or checksum sidecar is interpreted as authoritative dependency metadata.

The serial number is derived deterministically from the lock digest, and the output intentionally omits a generation timestamp. Identical verified inputs therefore produce identical SBOM JSON. This is a release inventory, not a vulnerability assessment: vulnerability enrichment, license conclusions, and external dependency discovery are separate pipeline stages.

Example:

```java
var lock = ReleaseArtifactResolver.verify(lockJson);
var manifest = ReleaseArtifactDigestManifest.verify(manifestJson, lock, trustedPublicKey);
String sbomJson = ReleaseArtifactSbom.create(lock, manifest);
```

The resulting JSON can be stored as bom.cdx.json alongside the release lock and signed digest manifest.