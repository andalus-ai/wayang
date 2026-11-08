# Phase 4R — Aggregate metrics adapter

AggregateEvaluationMetrics is a thread-safe, in-process adapter for the Phase 4Q EvaluationObserver SPI. It has no Micrometer/OpenTelemetry dependency and stores aggregate counters and duration totals/maxima only.

## Snapshot

EvaluationMetricsSnapshot exposes bounded aggregate values: admission grants/rejections; execution submissions, rejections, starts, completions and failures; caller wait timeouts, pre-start cancellation/deadline and unknown results; admission duration count/total; execution duration count/total/max.

No tenant, correlation, expression, activation, result, provider message or dynamic label is captured. CALLER_INTERRUPTED is not separately counted in this initial snapshot.

## Integration

Construct one adapter per coordinator or process scope and pass it as EvaluationObserver: new AdmittedEvaluationCoordinator(admission, policy, metrics). Read metrics.snapshot() for dashboards or adapt it to a metrics backend in a separate integration module. The snapshot is weakly consistent during concurrent traffic and is suitable for operational trends, not billing or exact audit.

## Limitations

Counters are process-local and reset with process lifetime. Duration totals are monotonic accumulators, not histograms or percentile estimators. No scrape server, exporter, persistence, distributed aggregation, or production CEL provider is included.
