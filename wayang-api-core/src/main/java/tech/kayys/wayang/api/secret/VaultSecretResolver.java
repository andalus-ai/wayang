package tech.kayys.wayang.api;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.SecretResolver;

/**
 * Secrets that live in HashiCorp Vault instead of Wayang's own store: {@code ${secret:vault.NAME}} reads
 * {@code <mount>/data/<basePath>/<tenant>/NAME} (KV v2), field {@code value}. The tenant is part of the path and is taken from
 * the run, never from the name, so a tenant cannot name its way into another's secrets; Wayang never writes to Vault.
 *
 * <p>Hits are cached briefly (so a busy workflow does not hammer Vault) and misses even more briefly. Anything that goes wrong
 * reads as "no such secret" to the run, which then fails closed; the reason is only in the server log, via {@code onError}.
 */
public final class VaultSecretResolver implements SecretResolver {

    public static final String PREFIX = "vault.";
    private static final Pattern REST = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)*");

    private record Cached(String value, Instant until) {
    }

    private final VaultClient client;
    private final String mount;
    private final String basePath;
    private final long hitTtlSeconds;
    private final long missTtlSeconds;
    private final Clock clock;
    private final java.util.function.Consumer<String> onError;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public VaultSecretResolver(VaultClient client, String mount, String basePath, long hitTtlSeconds, long missTtlSeconds, Clock clock,
            java.util.function.Consumer<String> onError) {
        this.client = client;
        this.mount = mount;
        this.basePath = basePath;
        this.hitTtlSeconds = hitTtlSeconds;
        this.missTtlSeconds = missTtlSeconds;
        this.clock = clock;
        this.onError = onError;
    }

    public static boolean isVaultName(String name) {
        return name != null && name.startsWith(PREFIX);
    }

    @Override
    public String resolve(String tenantId, String name) {
        if (tenantId == null || !isVaultName(name)) {
            return null;
        }
        String rest = name.substring(PREFIX.length());
        if (rest.isEmpty() || rest.length() > 100 || !REST.matcher(rest).matches()) {
            return null; // no '..', no empty segment, nothing that is not a plain name
        }
        String key = tenantId + "\0" + rest;
        Cached c = cache.get(key);
        Instant now = clock.instant();
        if (c != null && now.isBefore(c.until())) {
            return c.value();
        }
        String value = null;
        long ttl = missTtlSeconds;
        try {
            Map<String, String> data = client.readKv2(mount, basePath + "/" + tenantId + "/" + rest);
            value = data.get("value");
            if (value != null && !value.isEmpty() && value.length() <= SecretService.MAX_VALUE_CHARS) {
                ttl = hitTtlSeconds;
            } else {
                value = null;
            }
        } catch (VaultClient.VaultException e) {
            if (e.status() != 404) {
                onError.accept("Vault read failed for a secret of tenant " + tenantId + ": " + e.getMessage());
            }
        } catch (IllegalArgumentException e) {
            onError.accept("Vault read refused for a secret of tenant " + tenantId + ": " + e.getMessage());
        }
        if (cache.size() > 10_000) {
            cache.clear();
        }
        cache.put(key, new Cached(value, now.plusSeconds(ttl)));
        return value;
    }

    /** Names read from outside Wayang's own store (see {@link ExternalSecrets}). They are never stored or exported as values. */
    public static boolean isExternalName(String name) {
        return ExternalSecrets.isExternalName(name);
    }

    /** Local secrets for everything but {@code vault.*}, Vault for those. */
    public static SecretResolver routing(SecretResolver local, SecretResolver vault) {
        return (tenant, name) -> isVaultName(name) ? vault.resolve(tenant, name) : local.resolve(tenant, name);
    }
}
