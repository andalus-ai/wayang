package tech.kayys.wayang.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Persistence SPI for definitions. Implementations must be tenant-scoped and
 * make {@link #putDraft} (compare-and-set on revision) and {@link #publish}
 * atomic. The bundled {@link InMemory} store is for tests and single-node
 * demos only; production needs a database-backed implementation.
 */
public interface DefinitionStore {

    /**
     * Creates or replaces the draft.
     *
     * @param expectedRevision revision the caller edited from; {@code null} = create only if absent;
     *                         a mismatch raises {@link ApiException.Code#CONFLICT}
     */
    StoredDefinition putDraft(String tenant, DefinitionKind kind, String id, Map<String, Object> doc, Long expectedRevision,
            String user, Instant now);

    Optional<StoredDefinition> getDraft(String tenant, DefinitionKind kind, String id);

    /** Freezes the current draft as the next immutable version. */
    StoredDefinition publish(String tenant, DefinitionKind kind, String id, String user, Instant now);

    Optional<StoredDefinition> getVersion(String tenant, DefinitionKind kind, String id, int version);

    /** Highest published version. */
    Optional<StoredDefinition> getLatest(String tenant, DefinitionKind kind, String id);

    List<DefinitionSummary> list(String tenant, DefinitionKind kind);

    boolean deleteDraft(String tenant, DefinitionKind kind, String id);

    final class InMemory implements DefinitionStore {
        private record Key(String tenant, DefinitionKind kind, String id) {
        }

        private final Map<Key, StoredDefinition> drafts = new java.util.HashMap<>();
        private final Map<Key, TreeMap<Integer, StoredDefinition>> versions = new java.util.HashMap<>();

        @Override
        public synchronized StoredDefinition putDraft(String tenant, DefinitionKind kind, String id, Map<String, Object> doc,
                Long expectedRevision, String user, Instant now) {
            Key k = new Key(tenant, kind, id);
            StoredDefinition cur = drafts.get(k);
            if (cur == null && expectedRevision != null && expectedRevision != 0) {
                throw new ApiException(ApiException.Code.CONFLICT, "Draft '" + id + "' does not exist");
            }
            if (cur != null && (expectedRevision == null || expectedRevision != cur.revision())) {
                throw new ApiException(ApiException.Code.CONFLICT, "Draft '" + id + "' was changed by someone else (revision "
                        + cur.revision() + ", you edited " + expectedRevision + ")");
            }
            StoredDefinition next = new StoredDefinition(tenant, kind, id, 0, cur == null ? 1 : cur.revision() + 1, doc, now, user);
            drafts.put(k, next);
            return next;
        }

        @Override
        public synchronized Optional<StoredDefinition> getDraft(String tenant, DefinitionKind kind, String id) {
            return Optional.ofNullable(drafts.get(new Key(tenant, kind, id)));
        }

        @Override
        public synchronized StoredDefinition publish(String tenant, DefinitionKind kind, String id, String user, Instant now) {
            Key k = new Key(tenant, kind, id);
            StoredDefinition d = drafts.get(k);
            if (d == null) {
                throw ApiException.notFound("Draft " + kind + " '" + id + "'");
            }
            TreeMap<Integer, StoredDefinition> vs = versions.computeIfAbsent(k, x -> new TreeMap<>());
            int v = vs.isEmpty() ? 1 : vs.lastKey() + 1;
            StoredDefinition pub = new StoredDefinition(tenant, kind, id, v, d.revision(), d.document(), now, user);
            vs.put(v, pub);
            return pub;
        }

        @Override
        public synchronized Optional<StoredDefinition> getVersion(String tenant, DefinitionKind kind, String id, int version) {
            TreeMap<Integer, StoredDefinition> vs = versions.get(new Key(tenant, kind, id));
            return vs == null ? Optional.empty() : Optional.ofNullable(vs.get(version));
        }

        @Override
        public synchronized Optional<StoredDefinition> getLatest(String tenant, DefinitionKind kind, String id) {
            TreeMap<Integer, StoredDefinition> vs = versions.get(new Key(tenant, kind, id));
            return vs == null || vs.isEmpty() ? Optional.empty() : Optional.of(vs.lastEntry().getValue());
        }

        @Override
        public synchronized List<DefinitionSummary> list(String tenant, DefinitionKind kind) {
            java.util.Set<Key> keys = new java.util.TreeSet<>(java.util.Comparator.comparing(Key::id));
            drafts.keySet().stream().filter(k -> k.tenant().equals(tenant) && k.kind() == kind).forEach(keys::add);
            versions.keySet().stream().filter(k -> k.tenant().equals(tenant) && k.kind() == kind).forEach(keys::add);
            List<DefinitionSummary> out = new ArrayList<>();
            for (Key k : keys) {
                StoredDefinition d = drafts.get(k);
                TreeMap<Integer, StoredDefinition> vs = versions.get(k);
                StoredDefinition latest = vs == null || vs.isEmpty() ? null : vs.lastEntry().getValue();
                StoredDefinition newest = d != null ? d : latest;
                Object n = newest.document().get("name");
                out.add(new DefinitionSummary(kind, k.id(), n instanceof String s ? s : k.id(), d != null,
                        latest == null ? 0 : latest.version(), newest.updatedAt(), newest.updatedBy(), Studio.projectId(newest.document())));
            }
            return out;
        }

        @Override
        public synchronized boolean deleteDraft(String tenant, DefinitionKind kind, String id) {
            return drafts.remove(new Key(tenant, kind, id)) != null;
        }
    }
}
