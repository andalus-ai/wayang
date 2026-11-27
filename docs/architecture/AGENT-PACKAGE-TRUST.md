# Agent Package Trust and Artifact Verification

> Status: **M1.8 implementation submitted for review**. This is a verification gate for declarative Agent Package archives. It is not the extension-plugin installer and does not enable executable code loading.

## 1. Trust boundary

A package archive and its detached signature are untrusted input. Verification must complete before the verified artifact is passed to the secure installation API.

```mermaid
sequenceDiagram
    participant Importer as Studio / API importer
    participant Verify as AgentPackageArtifactVerifier
    participant Trust as Operator trust store
    participant Reader as AgentPackageReader
    participant Installer as AgentPackageInstaller
    participant Store as InstalledAgentStore

    Importer->>Verify: archive bytes + detached signature envelope
    Verify->>Verify: SHA-256 exact archive bytes
    Verify->>Trust: publisher ID + key ID
    Trust-->>Verify: trusted Ed25519 key + namespace + validity
    Verify->>Verify: verify detached signature
    Verify->>Verify: bounded ZIP inspection; no extraction
    Verify->>Reader: parse manifest from archive
    Reader-->>Verify: validated AgentPackage
    Verify->>Verify: match signed identity + verify every resource digest
    Verify-->>Importer: opaque VerifiedArtifact
    Importer->>Installer: installVerified(VerifiedArtifact, verified dependencies)
    Installer->>Store: persist project-scoped installation
```

The installer exposes `installVerified` and `upgradeVerified` methods. These accept only `VerifiedArtifact` instances produced by the verifier; their constructors are private. The existing lower-level `install(PackageArtifact, ...)` methods remain for local/trusted catalog workflows and tests. A future HTTP or marketplace endpoint must use the verified methods and must never convert untrusted request fields directly into a resolver artifact.

## 2. Archive format

The current archive is a ZIP containing exactly:

- One root manifest: `agent-package.yaml` or `agent-package.json`.
- Only the files declared under `spec.resources`.
- No directory entries, undeclared files, duplicate names or case-colliding names.

Every packaged resource must declare a SHA-256 digest. The verifier checks the digest of each resource against the manifest and rejects missing or undeclared files. A relative metadata icon must also be a declared resource. HTTPS icon references remain external presentation references; they are not fetched during verification.

The verifier inspects bytes in memory and never extracts or executes content. Current hard limits:

| Limit | Value |
|---|---:|
| Compressed archive | 64 MiB |
| ZIP entries | 256 |
| Manifest | 1 MiB |
| Individual resource | 16 MiB |
| Total uncompressed content | 64 MiB |
| Entry compression ratio | 200:1 for entries over 1 KiB |

ZIP entry paths reject absolute paths, traversal segments, backslashes, drive-qualified names, NULs, empty segments and case-colliding paths. These checks are defense in depth; no filesystem extraction is performed by this increment.

## 3. Detached signature envelope

Signature format version 1 uses Ed25519 and binds these fields using a domain-separated UTF-8 payload:

`wayang-agent-package-signature-v1`, package ID, exact SemVer version, publisher ID, archive SHA-256, key ID and algorithm.

The signature is detached from the ZIP, avoiding a self-referential archive digest. The exact archive bytes—including manifest and resources—are covered by the signed SHA-256 value.

The verifier rejects:

- Archive digest mismatch.
- Invalid Ed25519 signature.
- Unknown publisher/key ID.
- Revoked or out-of-validity key.
- A key not authorized for the package ID namespace.
- Signed package identity that differs from the parsed manifest.

The trusted public key and its package namespace prefixes are operator-provisioned through `AgentPackageTrustStore`; a key included inside the package is never trusted. `AgentPackageTrustStore.InMemory` is provided for tests and embedded/local configuration. Persistent trust administration, KMS/HSM-backed key management, key rotation workflows and distributed revocation propagation are not part of this increment.

## 4. SBOM and provenance evidence

If a resource declares role `sbom`, the verifier checks that it is a structurally valid CycloneDX JSON document (currently versions 1.4, 1.5 and 1.6), with a UUID serial number and valid component entries.

If a resource declares role `provenance`, the verifier checks the in-toto Statement v1 structure, predicate type, predicate object and a subject identifying the package ID with a SHA-256 digest.

All evidence files are covered by the archive signature and their manifest resource digests. These checks are deliberately not described as complete supply-chain attestation verification:

- The SBOM is not yet checked against the full CycloneDX JSON Schema or independently reconciled against a resolved dependency lock.
- The in-toto statement's builder identity, SLSA predicate semantics, certificate chain, transparency log inclusion and independent attestation signature are not verified.
- The package publisher's Ed25519 signature authenticates the archive as a whole; it does not prove that the publisher is the builder.

A later supply-chain assurance phase should add independent attestation verification and policy evaluation before marketplace publication or execution.

## 5. Installation and permission boundary

A verified package is still not automatically trusted to use every capability it requests.

- `requestedPermissions` remain requests and require a separate policy/approval decision.
- Configuration values remain external secret, connection or config references.
- Verification does not download dependencies; every dependency candidate passed to `installVerified` must itself be a `VerifiedArtifact`.
- Verification does not activate, deploy, start or execute the agent.
- The opaque verified result is an in-process capability, not a durable proof token. Persisted installations must retain the verified archive digest and lock; a future restart/recovery path must re-verify the retained artifact or validate a signed verification receipt.

## 6. Explicit non-goals

This increment does not implement marketplace publishing, artifact download, trust-store REST administration, persistent publisher-key storage, package archive extraction, executable extension sandboxing, permission grants, dynamic activation, deployment or runtime rollback.

The archive is kept in memory and not persisted by `AgentPackageArtifactVerifier`; artifact storage and retention belong to the next artifact repository lifecycle increment.
