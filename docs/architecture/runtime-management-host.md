# Runtime management host integration

## Status

M7.7.2 adds an opt-in heartbeat and inventory reporter, a host-owned credential-store SPI, a local file implementation, a profile-aware inventory provider, and an explicit command-handler SPI.

M7.7.3 adds handler-gated command polling and acknowledgement:
- Polling is disabled unless management is enabled and at least one handler is registered.
- Duplicate command-type declarations fail closed; no command is polled while handler ownership is ambiguous.
- Unknown command types are acknowledged as FAILED and are never executed.
- STARTED is acknowledged before handler invocation.
- SUCCEEDED/FAILED is acknowledged after handler completion.
- APPLY_DEPLOYMENT success requires a verified `sha256:<64 hex>` digest.
- Scheduler overlap is skipped, the poll limit is clamped to 1..50, and transient failures use bounded exponential backoff with jitter.
- Shutdown sets a closed flag so the next scheduled tick and the command loop stop.

## Enablement

Management is disabled by default. To enable periodic calls after an operator has enrolled this runtime:

```properties
wayang.management.enabled=true
wayang.management.endpoint=https://console.example.com
wayang.management.credential-file=/var/lib/wayang/management/registration-token
wayang.management.heartbeat-interval=30s
wayang.management.inventory-interval=2m
wayang.management.command-poll-interval=10s
wayang.management.command-poll-limit=10
wayang.management.connect-timeout=5S
wayang.management.request-timeout=10S
```

The registration token file must be mounted on persistent storage writable only by the Wayang process user. The file implementation applies owner-only POSIX permissions to the credential file where supported and writes via a temporary file followed by atomic replacement. On non-POSIX filesystems, enforce equivalent ACLs outside the application. Do not use this local implementation for multi-replica deployments; provide a shared secret-store implementation of `ManagementCredentialStore`.

The host refuses non-HTTPS management endpoints. A missing credential is not an instruction to enroll: the host waits. Enrollment is intentionally operator-driven; a one-time enrollment request must never be blindly retried after an ambiguous network failure.

## Inventory semantics

`ProfileRuntimeManagementInventoryProvider` uses the same profile-filtered `ComponentRegistry` and extension metadata as the component catalog. It reports component capability tuples. Workflow inventory remains empty until a durable authoritative deployment registry is connected; the host must not mistake authoring definitions for deployed workflows.

Applications may replace the default provider with their own CDI bean to include deployment-specific metadata.

## Command handling and delivery semantics

The Console leases commands and redelivers them when delivery/execution leases expire. This is at-least-once delivery, not exactly-once execution. A handler MUST durably deduplicate by both `commandId` and `idempotencyKey` across process restarts. The runtime poller itself does not claim durable execution deduplication; handlers own the domain-specific transaction boundary and must return only after their durable operation is committed.

The poller does not fetch commands if there are no handlers. If multiple handlers claim the same command type, polling fails closed for that tick. Unknown command types are failed without execution so they do not consume delivery attempts indefinitely.

The poller sends STARTED before executing a handler. A handler result is acknowledged as SUCCEEDED or FAILED. If a process crashes after a handler commits but before the terminal acknowledgement reaches Console, the command may be redelivered; the handler's idempotency key must make that replay safe.

For APPLY_DEPLOYMENT, the handler must independently verify the installed artifact and return `sha256:<64 hex>`. The Console also compares that digest with the expected deployment artifact digest before marking deployment successful.

## Failure handling

- 401/403: polling pauses for the same stored credential fingerprint; changing the stored token permits another attempt.
- 408/425/429/5xx and transport failures: exponential backoff with jitter, capped at five minutes.
- Terminal acknowledgement failures are not blindly retried in a tight loop. The Console's lease/redelivery and handler idempotency provide recovery.
- Credentials and exception messages are never written to logs.
- Heartbeat, inventory, and command jobs skip overlapping invocations within one process.

## Remaining work

- An operator-facing enrollment/rotation flow.
- Durable handler implementations for deployment execution.
- A deployment handler that fetches/verifies the bundle, stages it, applies it, and verifies the installed digest.
- Cross-repository integration tests against a live Console API.
- Multi-replica credential storage and coordinated command ownership.
