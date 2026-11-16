# M7.7.4 — authenticated runtime artifact retrieval

The management client can download the artifact URI carried by an `APPLY_DEPLOYMENT` command. This is a transport boundary, not a deployment executor.

## Request and trust boundary

`ManagementArtifactClient`:
- Sends the registration bearer token only to the configured Console origin.
- Resolves the command's relative `artifactUri` against that origin and rejects cross-origin, user-info, query, fragment, and non-runtime-artifact paths.
- Requires the URI path to identify the exact artifact digest supplied by the command.
- Streams the response to a temporary file in the destination directory; it does not buffer the artifact in memory.
- Enforces a caller-supplied maximum size and checks `Content-Length` when present.
- Requires the Console artifact and manifest digest headers to match the command.
- Computes SHA-256 over the downloaded bytes and compares it with the command digest.
- Publishes the file only with an atomic filesystem move. A failed download or verification removes the temporary file.

The result includes the detached signature and signing-key ID response headers. They are untrusted metadata until a runtime-local trust resolver verifies the signature using an independently provisioned publisher public key.

## Explicit non-goals

This client does not:
- trust a signing key merely because the Console supplied its key ID;
- treat a digest match as signature verification or package safety;
- extract or execute package contents;
- activate a deployment, mutate deployment lifecycle state, or claim rollback;
- acknowledge `APPLY_DEPLOYMENT` as successful.

The runtime host already requires HTTPS for its configured management endpoint. Production deployments must use HTTPS; HTTP is useful only for isolated loopback transport tests.

## Next integration boundary

A deployment handler must validate the typed payload, resolve the publisher key from local trust configuration, verify the detached artifact signature and signed package manifest, then pass the verified package to a trusted installation/activation adapter. Durable idempotency must use the Console command ID and idempotency key together with the deployment generation. The handler may report success only after the installed artifact is independently measured and the deployment target confirms readiness.

No default `APPLY_DEPLOYMENT` handler is registered by this milestone.