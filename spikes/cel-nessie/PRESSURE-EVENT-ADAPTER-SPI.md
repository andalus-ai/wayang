# Phase 4X — Pressure event adapter SPI

Wayang exposes a small Java SPI for optional host integrations of immutable pressure-transition events. The SPI lives in the isolated CEL qualification spike and has no dependency on Quarkus, Micrometer, OpenTelemetry, Kafka, or a particular event bus.

## Contracts

- `PressureEventAdapter` receives the existing `EvaluationPressureEvent` and is `AutoCloseable`. Creating an adapter activates it; the creating host owns its shutdown.
- `PressureEventAdapterProvider` supplies a stable provider ID, exact version, and factory.
- `PressureEventAdapterConfig` carries the exact provider ID/version and an immutable string-property map. Properties are opaque to the core.
- `PressureEventAdapterRegistry` supports explicit provider registration and opt-in Java `ServiceLoader` discovery.

## Deterministic selection

Hosts must select the exact `providerId + providerVersion` pair. There is no version fallback, classpath-order winner, or automatic selection of an arbitrary provider. Duplicate ID/version registrations fail registry construction. The registry verifies that the returned adapter reports the same identity; a mismatched adapter is closed before creation fails.

A host can construct a registry explicitly:

```java
var registry = PressureEventAdapterRegistry.of(List.of(myProvider));
var config = new PressureEventAdapterConfig(
    "com.example.metrics", "1.0.0", Map.of("queue-capacity", "256"));
var adapter = registry.create(config);
try {
    // Wire adapter into EvaluationPressureTracker or BoundedPressureEventDispatcher.
} finally {
    adapter.close();
}
```

For extension modules that publish a `META-INF/services/tech.kayys.wayang.spike.PressureEventAdapterProvider` descriptor, hosts may opt in with `PressureEventAdapterRegistry.load(classLoader)`. Discovery is explicit; the core does not scan the classpath at startup.

## Security and lifecycle

- The registry never logs property values. Provider implementations must validate their own property names/values and protect secrets.
- Do not put credentials in ordinary string properties unless the host's configuration and secret-management policy explicitly supports it.
- Providers should not include event payloads, tenant identifiers, or secrets in exception messages or metrics.
- The host owns adapter construction, wiring, and close order. Close adapters before shutting down dependencies they use.
- An adapter that wraps `BoundedPressureEventDispatcher` should close the dispatcher and await termination according to host shutdown policy.

## Boundaries

The SPI defines integration and lifecycle contracts only. It does not ship broker clients, exporters, retries, persistence, delivery guarantees, tenant routing, configuration-file parsing, dependency injection, or a default adapter implementation. Durable delivery remains the responsibility of an extension/host using its own outbox or broker integration. This SPI does not change CEL evaluation, pressure classification, hysteresis, admission, provider execution, or Gamelan lifecycle.
