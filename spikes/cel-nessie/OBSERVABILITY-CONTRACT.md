# Phase 4Q — Admission and execution observability

This phase adds a provider-neutral observation boundary to the isolated CEL
qualification spike. It introduces no metrics vendor dependency and does not
promote CEL-Java into the Wayang runtime.

## Observation surface

EvaluationObserver receives immutable EvaluationEvent values for admission
grant/rejection and elapsed admission time; execution submission/rejection and
worker start; provider completion/failure and execution duration; cancellation
or deadline detected before provider invocation; and caller wait
timeout/interruption or unavailable task result.

Durations are monotonic elapsed nanoseconds, not wall-clock timestamps.

## Cardinality and privacy

Events contain no tenant ID, correlation ID, expression text, activation/result,
provider exception message, or arbitrary metadata fields. reasonCode accepts
only stable uppercase codes. Metrics adapters should use event type and stable
reason code as bounded labels. Never add tenant, correlation, source, expression,
or user-provided values as metric labels.

The observer is optional; existing constructors use a no-op observer. Observer
RuntimeExceptions are isolated and do not change admission, execution, result,
or lease semantics. Implementations should remain non-blocking.

## Adapter guidance

A Micrometer or OpenTelemetry adapter can map event types to counters and
durations to histograms. Keep that adapter outside this core contract. Suggested
instruments include admission attempts, admission wait, execution tasks,
execution duration, and caller wait timeouts. Use bounded outcome/reason
enumerations only. Queue depth and active-worker gauges can be sampled from
existing aggregate snapshots.

## Semantics and limitations

A caller wait timeout is not provider cancellation. The worker may continue
and retains its admission lease until it actually terminates. Cancellation and
deadline events are emitted only when detected before provider invocation;
they do not imply an already-running provider was stopped.

The observer SPI is synchronous and process-local. It does not guarantee event
delivery, persistence, exactly-once telemetry, or export when an observer
throws. No hard CPU/heap bound, distributed quota, provider preemption, or
production CEL qualification is claimed.
