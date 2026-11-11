# Offline package verification and installation receipt

## Verify before installation

`ReleaseOfflinePackageVerifier.verify(packageFile, trustedReleaseSigner)` validates an offline ZIP in place and does not extract or execute its contents.

The verifier:

- Requires a regular, non-symlink package file.
- Rejects absolute paths, traversal segments, backslashes, drive-qualified paths, directory entries, duplicate names, unexpected entries, and missing entries.
- Applies entry-count, per-entry, metadata, and total-uncompressed-size limits.
- Verifies the embedded release lock and detached Ed25519 artifact digest manifest using an externally trusted release public key.
- Regenerates the deterministic CycloneDX SBOM and requires an exact match.
- Re-hashes every packaged POM and JAR against the signed manifest.
- Requires the exact expected Maven-layout artifact set.

The verifier intentionally does not extract files. A later installer may extract only after verification, using a separate staging directory and its own normalized-path and no-follow-link protections. The current release lock covers internal Wayang artifacts only, not the full external Maven dependency closure.

## Sign an installation receipt

After successful verification, `ReleaseInstallationReceipt.create(verifiedPackage, installationPrivateKey, installationPublicKey)` creates a deterministic Ed25519-signed JSON receipt. It binds:

- Final package SHA-256.
- Verified lock, signed manifest, and SBOM SHA-256 values.
- Artifact count and per-file artifact digests.
- SHA-256 fingerprint of the installation signer's encoded public key.

The installation signing key is distinct from the release-manifest signing key. The receipt proves that the holder of the installation key attested to successful verification; it does not itself prove that installation or application startup completed. Consumers must obtain the installation public key through an independent trust channel and call `ReleaseInstallationReceipt.verify`.

The receipt has no wall-clock timestamp, host identity, tenant identity, or mutable machine-specific data, keeping it reproducible and avoiding accidental disclosure. A deployment system that needs audit chronology should wrap the receipt in its own append-only event record.
