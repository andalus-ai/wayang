# Phase 4Y — Bounded dispatcher reference adapter

This phase proves the Phase 4X adapter SPI with one concrete, dependency-free implementation.

## Provider

- ID: `tech.kayys.wayang.pressure.bounded-dispatcher`
- Version: `1.0.0`
- Required property: `capacity` (positive integer)
- Optional property: `overflow-policy` (`REJECT_NEWEST` by default, or `DROP_OLDEST`)

Unknown properties and unsupported values fail at construction. The downstream `EvaluationPressureEventListener` is injected into the provider by the host; no transport client or telemetry SDK is constructed by the CEL module.

## Wiring

```java
EvaluationPressureEventListener sink = event -> publishToHostTelemetry(event);
var provider = new BoundedDispatcherPressureEventAdapterProvider(sink);
var registry = PressureEventAdapterRegistry.of(List.of(provider));
var config = new PressureEventAdapterConfig(provider.id(), provider.version(),
    Map.of("capacity", "256", "overflow-policy", "REJECT_NEWEST"));

var adapter = (BoundedDispatcherPressureEventAdapter) registry.create(config);
var tracker = new EvaluationPressureTracker(3,
    EvaluationPressureAssessment.Level.NORMAL, adapter);

// During host shutdown:
adapter.close();
adapter.awaitTermination(Duration.ofSeconds(5));
```

The provider factory starts the dispatcher's daemon worker. The host owns the returned adapter and must close it before shutting down the downstream dependency. The dispatcher drains events accepted before close; events submitted after close are rejected internally.

## Guarantees and limits

- Bounded in-memory queue and one consumer thread.
- Non-blocking event submission with explicit overflow behavior.
- Orderly close drains already accepted events.
- Snapshot exposes aggregate queue and delivery counters.
- Delivery is process-local and best-effort; it is not durable or exactly-once.
- A blocked downstream listener can delay termination beyond the host's wait timeout.
- The reference adapter does not provide retries, persistence, tenant routing, batching, broker semantics, or metrics export.

This is a reference implementation for the SPI, not a default enabled CEL component.
