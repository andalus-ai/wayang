# Phase 4V — Pressure transition events

EvaluationPressureTracker optionally notifies a host listener when hysteresis commits a new pressure level.

## Event contract

- No event is emitted for an observation that does not change the committed level.
- EvaluationPressureEvent records the occurrence timestamp, previous and current committed levels, instantaneous observed level, and the reasons attached to that observation.
- The event and its reason list are immutable. Events intentionally carry no tenant, request, expression, activation, or provider data.
- Existing constructors remain source-compatible and use a no-op listener. Hosts may supply an EvaluationPressureEventListener explicitly.

## Callback behavior

The tracker commits state while synchronized, then invokes the listener after leaving its monitor. A listener can safely query the tracker or re-enter host code without holding the tracker lock. Listener RuntimeExceptions are isolated: they do not roll back the committed transition or change the returned transition.

Callbacks are synchronous on the thread calling observe; a slow listener therefore slows that caller. Hosts that need asynchronous delivery should enqueue the immutable event in their own bounded adapter. This contract is not a durable audit log or an event bus, and it does not provide delivery retries or persistence.

## Boundaries

Events are optional operational notifications only. They do not change classification, hysteresis, admission, provider execution, resource limits, cancellation, retries, or Gamelan lifecycle. Fatal JVM errors are not swallowed.
