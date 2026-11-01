package tech.kayys.wayang.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface McpStore {

    /** @throws ApiException CONFLICT when the id exists */
    void create(McpServerDef d);

    Optional<McpServerDef> get(String tenant, String id);

    List<McpServerDef> list(String tenant);

    void update(McpServerDef d);

    boolean delete(String tenant, String id);

    final class InMemory implements McpStore {
        private final Map<String, McpServerDef> all = new LinkedHashMap<>();

        private static String key(String t, String id) {
            return t + "\u0000" + id;
        }

        @Override
        public synchronized void create(McpServerDef d) {
            if (all.putIfAbsent(key(d.tenant(), d.id()), d) != null) {
                throw new ApiException(ApiException.Code.CONFLICT, "MCP server '" + d.id() + "' already exists");
            }
        }

        @Override
        public synchronized Optional<McpServerDef> get(String tenant, String id) {
            return Optional.ofNullable(all.get(key(tenant, id)));
        }

        @Override
        public synchronized List<McpServerDef> list(String tenant) {
            List<McpServerDef> out = new ArrayList<>();
            all.values().forEach(d -> {
                if (d.tenant().equals(tenant)) {
                    out.add(d);
                }
            });
            return out;
        }

        @Override
        public synchronized void update(McpServerDef d) {
            all.replace(key(d.tenant(), d.id()), d);
        }

        @Override
        public synchronized boolean delete(String tenant, String id) {
            return all.remove(key(tenant, id)) != null;
        }
    }
}
