package tech.kayys.wayang.api;

import java.util.List;
import java.util.Map;

import tech.kayys.wayang.runtime.SecretResolver;

/** The secret-name prefixes that are read from outside Wayang's own store, and how a run is routed between them. */
public final class ExternalSecrets {

    public static final List<String> PREFIXES = List.of("vault.", "file.", "aws.", "gcp.", "azure.");

    private ExternalSecrets() {
    }

    public static boolean isExternalName(String name) {
        if (name != null) {
            for (String p : PREFIXES) {
                if (name.startsWith(p)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Each external prefix goes to its resolver; a prefix with no resolver configured reads as "no such secret" (it is never
     * looked up in the local store). Everything else goes to {@code local}.
     */
    public static SecretResolver routing(SecretResolver local, Map<String, SecretResolver> byPrefix) {
        return (tenant, name) -> {
            if (name != null) {
                for (String p : PREFIXES) {
                    if (name.startsWith(p)) {
                        SecretResolver r = byPrefix.get(p);
                        return r == null ? null : r.resolve(tenant, name);
                    }
                }
            }
            return local == null ? null : local.resolve(tenant, name);
        };
    }
}
