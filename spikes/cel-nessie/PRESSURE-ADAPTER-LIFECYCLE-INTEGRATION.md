# Pressure adapter lifecycle integration

## Scope

Phase 4Z adds host-side composition for optional pressure-event adapters and a
real Java `ServiceLoader` discovery test. It does not add a production telemetry
provider, transport dependency, exporter, or runtime dependency to Wayang core.

## Discovery

`PressureEventAdapterRegistry.load(classLoader)` uses Java `ServiceLoader`.
The qualification spike verifies the mechanism with a provider descriptor under
`src/test/resources/META-INF/services`. The fixture is deliberately test-only:
the bounded-dispatcher reference provider still requires a host-supplied
downstream listener and is not silently activated as a production default.

Hosts may also construct a registry explicitly with `of(...)`. Exact
provider ID/version selection and duplicate rejection remain unchanged.

## Group ownership and delivery

`PressureEventAdapterGroup` accepts an immutable snapshot of host-created
adapters and implements `EvaluationPressureEventListener`. It:

- fans each committed transition out to every configured adapter;
- isolates each adapter's `RuntimeException` and counts aggregate delivery
  failures without recording event or tenant data;
- rejects new dispatch after close begins;
- waits for already-started dispatches to finish before closing resources;
- closes adapters in reverse construction order;
- attempts every close and reports failures as suppressed exceptions.

The host should stop the pressure tracker before closing the group. The group
is safe against concurrent dispatch and shutdown; adapter callbacks must not
call `group.close()` reentrantly from inside their own delivery callback.

## Lifecycle ordering

Recommended host sequence:

1. Discover or explicitly register adapter providers.
2. Create adapters with exact provider ID/version and validated configuration.
3. Construct one `PressureEventAdapterGroup` and supply it to the pressure
   tracker as its listener.
4. Stop tracker/event production during host shutdown.
5. Close the group; it drains in-flight callbacks and closes adapters in
   reverse order.

## Boundaries

- The group is in-process and best-effort; it does not persist events.
- Delivery is not exactly-once and has no cross-process ordering guarantee.
- A callback that blocks indefinitely can delay group shutdown indefinitely.
- The ServiceLoader descriptor in this phase is test-only. Production packaging
  must add descriptors for providers that have a no-argument construction
  strategy or use a host-owned provider factory.
- This remains a CEL qualification spike, not a promoted Wayang runtime API.
