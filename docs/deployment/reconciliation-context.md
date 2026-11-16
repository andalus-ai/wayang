# Durable deployment reconciliation context

A deployment operation receipt deliberately stores only bounded identity, outcome, and lease metadata.
It does not contain arbitrary command parameters, actor identity, reason, or correlation data. The
lifecycle reconciliation service therefore requires the original trusted request context.

## Persistence boundary

Receipt-aware orchestration can be constructed with a
`DeploymentLifecycleReconciliationContextStore`. When configured, each durable activation,
deactivation, or rollback stores its reconciliation context after winning the exclusive receipt claim
and before writing the pending lifecycle transition or invoking the target adapter.

The JDBC implementation stores:

- deployment ID, operation key, operation, and canonical command fingerprint for lookup and integrity;
- an opaque encrypted payload containing the original command (including parameters), actor, reason,
  correlation ID, and readiness timeout;
- creation time.

It does not store command parameters, audit text, or credentials in plaintext columns. The migration
is `V3__deployment_reconciliation_context.sql`.

## Required codec configuration

The application must supply a `DeploymentLifecycleReconciliationContextCodec` implementation to
`JdbcDeploymentLifecycleReconciliationContextStore`. The deployment module intentionally provides
no plaintext or implicit-key codec.

Production codecs must:

1. use authenticated encryption (AES-GCM or envelope encryption backed by a KMS);
2. bind deployment ID, operation key, operation, and command fingerprint as authenticated associated
   data so ciphertext cannot be moved to another operation;
3. reject authentication failures and malformed payloads;
4. support key rotation and retain old decryption keys for at least the maximum reconciliation retention
   period;
5. avoid logging plaintext context, ciphertext, credentials, or adapter output.

The codec is responsible for a versioned serialization format. Keep serialized payloads bounded to
1 MiB after encryption. A missing codec is a configuration error; do not replace it with plaintext
serialization for convenience.

## Ordering and failure behavior

The context is written only by the caller that acquired the durable operation claim. Duplicate
requests do not overwrite the original context and do not invoke the target. If context persistence
fails, the pre-dispatch callback fails, the receipt is completed as
`PRE_DISPATCH_REJECTED`, and the target is not invoked.

The context store is not itself a lifecycle reconciler or scheduler. A subsequent worker must load a
terminal receipt, retrieve its matching context, verify the fingerprint and scope, and call
`DeploymentLifecycleReconciliationService`. The worker must never dispatch or retry the target
operation. If a pre-dispatch lifecycle transition is rejected after the context was stored, the
`PRE_DISPATCH_REJECTED` receipt is not applicable to lifecycle reconciliation; the context may be
retained for audit or removed by a separately governed retention process.

This design does not claim distributed exactly-once execution. The receipt claim provides at-most-once
dispatch from this process boundary; external side effects remain subject to adapter idempotency and
read-only target observation.
