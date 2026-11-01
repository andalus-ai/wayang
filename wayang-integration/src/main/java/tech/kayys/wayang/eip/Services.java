package tech.kayys.wayang.eip;

import java.time.Clock;

/**
 * Everything a pipeline may need from its host, in one place. All fields have safe defaults except {@code invoker}
 * (null: {@code call} steps refuse to load). {@code namespace} scopes claim checks, aggregation groups and breaker/throttle
 * state to a tenant, process and node; the host must always set it for multi-tenant use.
 */
public record Services(IdempotencyStore idempotency, Invoker invoker, ClaimStore claims, AggregationStore aggregation,
        EipState state, String namespace, Clock clock, Sleeper sleeper) {

    public Services {
        namespace = namespace == null ? "" : namespace;
        clock = clock == null ? Clock.systemUTC() : clock;
        sleeper = sleeper == null ? Sleeper.SYSTEM : sleeper;
        state = state == null ? new EipState() : state;
    }

    public static Services basic(IdempotencyStore idem, Invoker invoker) {
        return new Services(idem, invoker, null, null, null, "", null, null);
    }

    /** Throw-away in-memory services: for validation and previews, which must have no side effects. */
    public static Services scratch(Invoker invoker) {
        return new Services(new IdempotencyStore.InMemory(1000), invoker, new ClaimStore.InMemory(1000),
                new AggregationStore.InMemory(1000), new EipState(), "scratch", null, millis -> { });
    }

    public Services withInvoker(Invoker i) {
        return new Services(idempotency, i, claims, aggregation, state, namespace, clock, sleeper);
    }

    public Services withIdempotency(IdempotencyStore i) {
        return new Services(i, invoker, claims, aggregation, state, namespace, clock, sleeper);
    }

    public Services withNamespace(String ns) {
        return new Services(idempotency, invoker, claims, aggregation, state, ns, clock, sleeper);
    }

    public Services withClock(Clock c, Sleeper s) {
        return new Services(idempotency, invoker, claims, aggregation, state, namespace, c, s);
    }

    public Services withStores(ClaimStore c, AggregationStore a) {
        return new Services(idempotency, invoker, c, a, state, namespace, clock, sleeper);
    }
}
