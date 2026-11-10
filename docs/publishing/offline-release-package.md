# Offline release package

`ReleaseOfflinePackageAssembler` creates a ZIP that can be copied to an isolated installation environment. It re-verifies the release lock and detached Ed25519 artifact digest manifest using the caller's externally trusted public key. The caller must supply exactly one cached POM/JAR pair for every locked coordinate; extra or missing artifacts are rejected.

Before writing the archive, every source must be a regular non-symlink file and its bytes are re-hashed against the signed manifest. The archive contains:

- `release/lock.json` — original lock document.
- `release/artifact-digests.json` — original detached signed manifest.
- `release/bom.cdx.json` — generated CycloneDX 1.5 SBOM.
- `repository/<group path>/<artifact>/<version>/...` — Maven-layout POM and JAR files.

ZIP entries are emitted in sorted order with normalized timestamps. The archive is written to a temporary file and moved into place only after successful completion. The returned result includes the final package SHA-256, byte size, and artifact count.

The package digest is an integrity identifier, not a signature. Distribution systems should sign or attest the final archive separately. This first assembler intentionally packages only the internal artifacts in the verified Wayang lock; external Maven dependencies must be represented in a future expanded lock format before they can be included safely.