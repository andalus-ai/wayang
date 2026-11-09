# Phase 4U — Pressure hysteresis and state transitions

`EvaluationPressureTracker` stabilizes the instantaneous `EvaluationPressureClassifier` output before a host presents or reacts to a pressure level.

## Consecutive-sample hysteresis

The tracker commits a different level only after the same candidate level has been observed for a configured number of consecutive calls to `observe(...)`. The default constructor does not choose a sampling cadence; the host owns sampling and can configure the required sample count. A value of one disables temporal smoothing.

- A repeated observation of the committed level clears a pending candidate.
- A different candidate replaces the previous candidate and restarts its count at one.
- Both escalation and recovery require the same consecutive-sample count.
- The tracker starts at `NORMAL` unless the host supplies an explicit initial level.
- `resetCandidate()` discards pending evidence without changing the committed level.

Each `EvaluationPressureTransition` reports the previous committed level, resulting committed level, instantaneous observed level, candidate sample count, whether a transition occurred, and the latest classifier reasons. Reasons describe the latest instantaneous observation; they are not historical reasons for the committed level.

## Concurrency and lifecycle

Tracker operations are synchronized and deterministic for the order in which observations enter the tracker. The tracker is in-memory and process-local. It does not persist state across restarts, create timers, sample the runtime, emit events, or depend on a metrics system. A host that shares one tracker across threads gets serialized observations; hosts needing independent views should use independent trackers.

## Boundaries

This is presentation/operational state only. It does not alter admission limits, provider execution, queue capacity, cancellation, retries, or Gamelan lifecycle. It does not infer CPU or heap pressure. The underlying runtime snapshot remains weakly consistent, so sample-count hysteresis reduces short-lived classification noise but cannot make snapshots atomic.
