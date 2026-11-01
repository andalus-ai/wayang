package tech.kayys.wayang.api;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tech.kayys.wayang.runtime.SecretResolver;

/**
 * Write-only secrets: plaintext goes in via {@link #put} and comes out only through {@link #resolve}, which is
 * called by the runtime (never exposed over the API). Management calls return metadata only.
 */
public final class SecretService implements SecretResolver {

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    public static final int MAX_VALUE_CHARS = 16 * 1024;

    /** Metadata view; deliberately has no value or ciphertext. */
    public record Meta(String name, int version, String keyId, java.time.Instant updatedAt, String updatedBy) {
    }

    private final SecretStore store;
    private volatile SecretCipher cipher;
    private final Clock clock;

    public SecretService(SecretStore store, SecretCipher cipher, Clock clock) {
        this.store = store;
        this.cipher = cipher;
        this.clock = clock;
    }

    /** Names under this prefix belong to the platform (OAuth); people cannot create, read, list or delete them. */
    public static final String RESERVED_PREFIX = "oauth";

    public static boolean reserved(String name) {
        return name != null && (name.startsWith(RESERVED_PREFIX + ".") || name.startsWith(RESERVED_PREFIX + "-"));
    }

    public Meta put(Principal p, String name, String value) {
        require(p, Permission.SECRET_MANAGE);
        checkName(name);
        if (VaultSecretResolver.isExternalName(name)) {
            throw ApiException.badRequest("Names starting with " + ExternalSecrets.PREFIXES + " are read from outside this store and cannot be stored here");
        }
        if (reserved(name)) {
            throw ApiException.badRequest("Secret names starting with '" + RESERVED_PREFIX + ".' or '" + RESERVED_PREFIX + "-' are reserved");
        }
        return write(p.tenantId(), name, value, p.userId());
    }

    /** Platform use only (OAuth refresh tokens); not reachable from the API. */
    void putSystem(String tenant, String name, String value, String by) {
        write(tenant, name, value, by);
    }

    boolean deleteSystem(String tenant, String name) {
        return store.delete(tenant, name);
    }

    private Meta write(String tenant, String name, String value, String by) {
        if (value == null || value.isEmpty() || value.length() > MAX_VALUE_CHARS) {
            throw ApiException.badRequest("Secret value must be 1.." + MAX_VALUE_CHARS + " characters");
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            int cur = store.get(tenant, name).map(SealedSecret::version).orElse(0);
            int next = cur + 1;
            SecretCipher c = cipher; // one snapshot: the key id recorded must be the key that sealed
            SealedSecret s = new SealedSecret(tenant, name, next, c.activeKeyId(),
                    c.seal(tenant, name, next, value), clock.instant(), by);
            if (store.put(s, cur)) {
                return meta(s);
            }
        }
        throw new ApiException(ApiException.Code.CONFLICT, "Concurrent update of secret " + name);
    }

    public List<Meta> list(Principal p) {
        require(p, Permission.SECRET_LIST);
        return store.list(p.tenantId()).stream().filter(x -> !reserved(x.name())).map(SecretService::meta).toList();
    }

    public void delete(Principal p, String name) {
        require(p, Permission.SECRET_MANAGE);
        checkName(name);
        if (reserved(name) || !store.delete(p.tenantId(), name)) {
            throw ApiException.notFound("secret " + name);
        }
    }

    /** Re-seals every secret of the caller's tenant not already under the active key. @return how many */
    public int rotate(Principal p) {
        require(p, Permission.SECRET_MANAGE);
        int n = 0;
        SecretCipher c = cipher;
        for (SealedSecret s : store.list(p.tenantId())) {
            if (s.keyId().equals(c.activeKeyId())) {
                continue;
            }
            String plain = c.open(s.tenant(), s.name(), s.version(), s.sealed());
            SealedSecret r = new SealedSecret(s.tenant(), s.name(), s.version(), c.activeKeyId(),
                    c.seal(s.tenant(), s.name(), s.version(), plain), s.updatedAt(), s.updatedBy());
            if (store.reseal(r)) {
                n++;
            }
        }
        return n;
    }

    /** Runtime-only. Returns null when absent or undecryptable (fails closed). */
    @Override
    public String resolve(String tenantId, String name) {
        if (tenantId == null || name == null) {
            return null;
        }
        return store.get(tenantId, name).map(s -> {
            try {
                return cipher.open(s.tenant(), s.name(), s.version(), s.sealed());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }).orElse(null);
    }

    // ------------------------------------------------------------------ keys and archives

    /**
     * Picks up a changed keyring (a rotated key file or Vault secret) without a restart. The new ring is read first and swapped in
     * only if it still holds every key the current one has: dropping a key could lock out secrets sealed under it, and that takes a
     * restart (after {@link #rotate} has re-sealed everything). A failed read leaves the current keys in place.
     *
     * @return the key status after the swap, plus {@code reloaded: true}
     */
    public Map<String, Object> reloadKeys(Principal p, java.util.function.Supplier<SecretCipher> source) {
        require(p, Permission.SECRET_MANAGE);
        require(p, Permission.ADMIN);
        SecretCipher fresh;
        try {
            fresh = source.get();
        } catch (RuntimeException e) {
            throw ApiException.badRequest("The keyring could not be read: " + e.getClass().getSimpleName() + ". The current keys are unchanged");
        }
        if (fresh == null) {
            throw ApiException.badRequest("There is no key source to reload (the key comes from wayang.secrets.key)");
        }
        synchronized (this) {
            SecretCipher cur = cipher;
            if (!fresh.keyIds().containsAll(cur.keyIds())) {
                java.util.Set<String> missing = new java.util.TreeSet<>(cur.keyIds());
                missing.removeAll(fresh.keyIds());
                throw ApiException.badRequest("The new keyring drops key(s) " + missing + " that this server still has loaded. Re-seal with rotate, then restart to remove a key");
            }
            this.cipher = fresh;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>(keyStatus(p));
        out.put("reloaded", true);
        return out;
    }

    /** Which master key each of the tenant's secrets is sealed under: how far a rotation has got. No secret data. */
    public Map<String, Object> keyStatus(Principal p) {
        require(p, Permission.SECRET_MANAGE);
        Map<String, Integer> by = new java.util.TreeMap<>();
        SecretCipher c = cipher;
        int stale = 0;
        for (SealedSecret s : store.list(p.tenantId())) {
            if (reserved(s.name())) {
                continue;
            }
            by.merge(s.keyId(), 1, Integer::sum);
            if (!s.keyId().equals(c.activeKeyId())) {
                stale++;
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("activeKeyId", c.activeKeyId());
        out.put("secretsByKey", by);
        out.put("needRotation", stale);
        return out;
    }

    /**
     * An encrypted copy of every secret of the tenant, to move them to another server (see {@link SecretArchive}). This is the one
     * place plaintext leaves the store on purpose, so it needs the admin permission, and the caller's REST layer keeps it off unless
     * the operator turns it on. Connected-account tokens are not included: they are re-created by connecting the account again.
     */
    public Map<String, Object> exportArchive(Principal p, String passphrase) {
        require(p, Permission.SECRET_MANAGE);
        require(p, Permission.ADMIN);
        SecretArchive.checkPassphrase(passphrase);
        Map<String, String> all = new java.util.LinkedHashMap<>();
        for (SealedSecret s : store.list(p.tenantId())) {
            if (reserved(s.name())) {
                continue;
            }
            all.put(s.name(), cipher.open(s.tenant(), s.name(), s.version(), s.sealed()));
        }
        return SecretArchive.seal(passphrase, all);
    }

    public record ImportResult(int created, int updated, int skipped, List<String> names) {
    }

    /** @param overwrite replace secrets that already exist; otherwise they are left as they are and counted as skipped */
    public ImportResult importArchive(Principal p, String passphrase, Map<String, Object> archive, boolean overwrite) {
        require(p, Permission.SECRET_MANAGE);
        SecretArchive.checkPassphrase(passphrase);
        Map<String, String> entries;
        try {
            entries = SecretArchive.open(passphrase, archive);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Cannot open the archive: wrong passphrase, or the file was changed");
        }
        if (entries.size() > SecretArchive.MAX_SECRETS) {
            throw ApiException.badRequest("At most " + SecretArchive.MAX_SECRETS + " secrets per archive");
        }
        for (var e : entries.entrySet()) { // all or nothing: check everything before writing anything
            checkName(e.getKey());
            if (reserved(e.getKey()) || VaultSecretResolver.isExternalName(e.getKey())) {
                throw ApiException.badRequest("The archive holds a name that is reserved here: " + e.getKey());
            }
            if (e.getValue().isEmpty() || e.getValue().length() > MAX_VALUE_CHARS) {
                throw ApiException.badRequest("The archive holds a value of the wrong size for " + e.getKey());
            }
        }
        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<String> names = new java.util.ArrayList<>();
        for (var e : entries.entrySet()) {
            boolean exists = store.get(p.tenantId(), e.getKey()).isPresent();
            if (exists && !overwrite) {
                skipped++;
                continue;
            }
            write(p.tenantId(), e.getKey(), e.getValue(), p.userId());
            names.add(e.getKey());
            if (exists) {
                updated++;
            } else {
                created++;
            }
        }
        return new ImportResult(created, updated, skipped, names);
    }

    private static Meta meta(SealedSecret s) {
        return new Meta(s.name(), s.version(), s.keyId(), s.updatedAt(), s.updatedBy());
    }

    private static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw ApiException.badRequest("Secret name must match " + NAME.pattern());
        }
    }

    private static void require(Principal p, Permission perm) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.can(perm)) {
            throw ApiException.forbidden(perm);
        }
    }
}
