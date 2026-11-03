package tech.kayys.wayang.api.config;

import tech.kayys.wayang.api.ConfigEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Persistence of plain settings, tenant-scoped. Values are not secret. */
public interface ConfigStore {

    Optional<ConfigEntry> get(String tenant, String scope, String key);

    /** The entries of one scope, by key. */
    List<ConfigEntry> list(String tenant, String scope);

    /** Compare-and-set: {@code expectedVersion} 0 means "must not exist", else the stored version must equal it. @return false on mismatch */
    boolean put(ConfigEntry entry, int expectedVersion);

    boolean delete(String tenant, String scope, String key);

    /** Removes every entry of a scope (a deleted project). @return how many */
    int deleteScope(String tenant, String scope);

    final class InMemory implements ConfigStore {
        private record K(String tenant, String scope, String key) {
        }

        private final Map<K, ConfigEntry> all = new TreeMap<>(Comparator.comparing(K::tenant).thenComparing(K::scope).thenComparing(K::key));

        @Override
        public synchronized Optional<ConfigEntry> get(String tenant, String scope, String key) {
            return Optional.ofNullable(all.get(new K(tenant, scope, key)));
        }

        @Override
        public synchronized List<ConfigEntry> list(String tenant, String scope) {
            List<ConfigEntry> out = new ArrayList<>();
            all.forEach((k, v) -> {
                if (k.tenant().equals(tenant) && k.scope().equals(scope)) {
                    out.add(v);
                }
            });
            return out;
        }

        @Override
        public synchronized boolean put(ConfigEntry e, int expectedVersion) {
            K k = new K(e.tenant(), e.scope(), e.key());
            ConfigEntry cur = all.get(k);
            if ((cur == null ? 0 : cur.version()) != expectedVersion) {
                return false;
            }
            all.put(k, e);
            return true;
        }

        @Override
        public synchronized boolean delete(String tenant, String scope, String key) {
            return all.remove(new K(tenant, scope, key)) != null;
        }

        @Override
        public synchronized int deleteScope(String tenant, String scope) {
            int before = all.size();
            all.keySet().removeIf(k -> k.tenant().equals(tenant) && k.scope().equals(scope));
            return before - all.size();
        }
    }
}
