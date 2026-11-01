package tech.kayys.wayang.api;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import tech.kayys.wayang.runtime.SecretResolver;

/**
 * {@code ${secret:aws.NAME}}, {@code gcp.NAME}, {@code azure.NAME}: a secret that lives in a cloud secret service instead of
 * Wayang's own store. Wayang only reads; the tenant is part of the cloud id and comes from the run. Hits are cached briefly (a
 * busy workflow must not hammer the service), misses more briefly. Anything that goes wrong reads as "no such secret" to the run,
 * which then fails closed; the reason is only in the server log, via {@code onError}.
 */
public final class CloudSecretResolver implements SecretResolver {

    private record Cached(String value, Instant until) {
    }

    private final String prefix;
    private final CloudBackend backend;
    private final long hitTtlSeconds;
    private final long missTtlSeconds;
    private final Clock clock;
    private final java.util.function.Consumer<String> onError;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public CloudSecretResolver(String prefix, CloudBackend backend, long hitTtlSeconds, long missTtlSeconds, Clock clock,
            java.util.function.Consumer<String> onError) {
        this.prefix = prefix;
        this.backend = backend;
        this.hitTtlSeconds = hitTtlSeconds;
        this.missTtlSeconds = missTtlSeconds;
        this.clock = clock;
        this.onError = onError;
    }

    @Override
    public String resolve(String tenantId, String name) {
        if (tenantId == null || name == null || !name.startsWith(prefix)) {
            return null;
        }
        String rest = name.substring(prefix.length());
        String key = tenantId + "\0" + rest;
        Instant now = clock.instant();
        Cached c = cache.get(key);
        if (c != null && now.isBefore(c.until())) {
            return c.value();
        }
        String value = null;
        long ttl = missTtlSeconds;
        try {
            value = backend.fetch(tenantId, rest);
            if (value != null && !value.isEmpty() && value.length() <= SecretService.MAX_VALUE_CHARS) {
                ttl = hitTtlSeconds;
            } else {
                value = null;
            }
        } catch (CloudException e) {
            if (e.status() != 404) {
                onError.accept(prefix + " read failed for a secret of tenant " + tenantId + ": " + e.getMessage());
            }
        } catch (IllegalArgumentException e) {
            return null; // a name or tenant the service cannot hold: nothing to ask, nothing to cache
        }
        if (cache.size() > 10_000) {
            cache.clear();
        }
        cache.put(key, new Cached(value, now.plusSeconds(ttl)));
        return value;
    }
}
