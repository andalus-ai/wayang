# Phase 4T — Capacity-aware pressure classification

`EvaluationPressureClassifier` classifies an `EvaluationRuntimeSnapshot` against the host's explicit `EvaluationAdmissionPolicy`, `EvaluationExecutionPolicy`, and `EvaluationPressurePolicy`.

## Thresholds

`EvaluationPressurePolicy` accepts elevated and saturated thresholds in basis points (10,000 = 100%). Its conservative default is 70% elevated and 90% saturated; hosts may supply different values. Threshold ordering is validated.

Each capacity dimension is assessed independently:

- active admitted evaluations / global admission concurrency
- admission waiters / admission queue capacity
- active provider workers / provider worker count
- queued provider tasks / provider queue capacity

The overall level is the most severe dimension. A dimension at or above the configured saturated threshold makes the overall assessment saturated. A zero-capacity queue is normal while empty and saturated if it contains work. Counts above a configured capacity are saturated.

## Reasons and lifecycle

The assessment includes stable enum reasons identifying the pressure dimension. Admission-closed and executor-shutdown flags are included as lifecycle reasons but do not, by themselves, mean capacity saturation; callers can distinguish a deliberately stopped runtime from a busy one.

## Boundaries

This is a deterministic classification of a weakly consistent, process-local snapshot. It does not change admission decisions, resize pools, reject work, preempt providers, or guarantee CPU/heap limits. The thresholds are operational signals, not policy enforcement. No tenant/request labels or exporter dependency is introduced. CEL remains isolated in the qualification spike; FEEL and Andalus core are unchanged.
