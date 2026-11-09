# Phase 4S — Runtime pressure snapshot

`EvaluationRuntimeSnapshot.capture(admission, coordinator)` combines the admission controller's aggregate snapshot with provider-executor queue/worker state.

## Fields

- admitted active evaluations and admission waiters
- admission-controller closed state
- active provider workers and queued provider tasks
- executor shutdown and terminated state

The value contains no tenant IDs, correlation IDs, expression source, activation/result data, provider messages, or dynamic labels.

## Consistency and interpretation

The admission and executor components are sampled sequentially. The returned record is immutable, but it is **not an atomic cross-component transaction**; counts can change between the two reads. Treat it as an operational point-in-time approximation, not an accounting or audit record.

Admission-active and provider-active counts describe different lifecycle boundaries. An admitted evaluation may be queued in the provider executor, so these numbers are intentionally not expected to match. Admission waiters and provider queued tasks are also distinct queues.

The snapshot reports raw counts and lifecycle flags only. It does not infer saturation percentages because the policy limits are not exposed through these runtime components. Consumers that need thresholds should configure them alongside the policies they own.

## Boundaries

This is process-local diagnostics. It does not add exporters, persistence, distributed aggregation, tenant-level metrics, hard CPU/heap limits, or provider preemption. CEL remains isolated in the qualification spike; FEEL and Andalus core are unchanged.
