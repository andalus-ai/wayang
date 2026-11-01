package tech.kayys.wayang.rest;

import java.time.Clock;

import tech.kayys.wayang.api.RateLimiter;

/** Process-wide limiters (a resource is created per request, so the counters live here). */
final class Limits {

    /** Outbound calls made for a designer by "describe" and "test": 20 a minute per user. */
    static final RateLimiter CONNECTOR_CALLS = new RateLimiter(20, 60_000, Clock.systemUTC());

    private Limits() {
    }
}
