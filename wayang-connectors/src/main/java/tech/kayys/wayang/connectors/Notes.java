package tech.kayys.wayang.connectors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Connector {@code notes}: a small private notebook that needs no outside service, for local and all-in-one deployments.
 *
 * <p>Config: {@code operation} one of {@code add} (input {@code text}, optional {@code title}), {@code list} (optional {@code limit}),
 * {@code search} (input {@code q}, case-insensitive), {@code delete} (input {@code id}); optional {@code namespace} (letters, digits, - and _,
 * default {@code default}) keeps separate notebooks. Notes live in a JSON file per namespace under the connector's directory, or in memory when
 * there is none. Bounds: 1000 notes per notebook, 4000 characters per note, 200 per title. Text is data, never executed.
 */
public final class Notes implements ServiceHandler.Connector {

    static final int MAX_NOTES = 1000;
    static final int MAX_TEXT = 4000;
    static final int MAX_TITLE = 200;
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern ID = Pattern.compile("n[0-9]{1,12}");

    private final Path dir;
    private final Clock clock;
    private final Map<String, List<Map<String, Object>>> memory = new java.util.HashMap<>();

    /** @param dir where notebooks are stored, or null to keep them in memory only */
    public Notes(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    /** {@code WAYANG_NOTES_DIR}, else {@code WAYANG_STATE_DIR/notes}, else memory only. */
    public static Notes fromEnvironment() {
        String d = System.getenv("WAYANG_NOTES_DIR");
        String s = System.getenv("WAYANG_STATE_DIR");
        Path p = d != null && !d.isBlank() ? Path.of(d) : s != null && !s.isBlank() ? Path.of(s).resolve("notes") : null;
        return new Notes(p, Clock.systemUTC());
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ServiceHandler.ConnectorException("notes needs node configuration", false);
    }

    @Override
    public synchronized Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        String ns = config.get("namespace") instanceof String n && !n.isBlank() ? n : "default";
        if (!NAMESPACE.matcher(ns).matches()) {
            throw new ServiceHandler.ConnectorException("notes namespace must be letters, digits, - and _", false);
        }
        String op = String.valueOf(config.getOrDefault("operation", "list"));
        List<Map<String, Object>> all = load(ns);
        switch (op) {
            case "add": {
                String text = str(inputs.get("text"));
                if (text == null || text.isBlank() || text.length() > MAX_TEXT) {
                    throw new ServiceHandler.ConnectorException("notes add needs 'text' of 1.." + MAX_TEXT + " characters", false);
                }
                String title = str(inputs.get("title"));
                if (title != null && title.length() > MAX_TITLE) {
                    throw new ServiceHandler.ConnectorException("notes title is longer than " + MAX_TITLE + " characters", false);
                }
                if (all.size() >= MAX_NOTES) {
                    throw new ServiceHandler.ConnectorException("The notebook is full (" + MAX_NOTES + " notes); delete some first", false);
                }
                long next = all.stream().mapToLong(m -> Long.parseLong(((String) m.get("id")).substring(1))).max().orElse(0) + 1;
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("id", "n" + next);
                note.put("title", title == null ? "" : title);
                note.put("text", text);
                note.put("created", clock.instant().toString());
                all.add(note);
                save(ns, all);
                return Map.of("id", note.get("id"), "count", all.size());
            }
            case "list": {
                int limit = limit(inputs);
                List<Map<String, Object>> out = new ArrayList<>(all.subList(Math.max(0, all.size() - limit), all.size()));
                java.util.Collections.reverse(out);
                return Map.of("notes", out, "count", all.size());
            }
            case "search": {
                String q = str(inputs.get("q"));
                if (q == null || q.isBlank() || q.length() > 200) {
                    throw new ServiceHandler.ConnectorException("notes search needs 'q' of 1..200 characters", false);
                }
                String needle = q.toLowerCase(Locale.ROOT);
                int limit = limit(inputs);
                List<Map<String, Object>> out = new ArrayList<>();
                for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
                    Map<String, Object> m = all.get(i);
                    if (((String) m.get("text")).toLowerCase(Locale.ROOT).contains(needle)
                            || ((String) m.get("title")).toLowerCase(Locale.ROOT).contains(needle)) {
                        out.add(m);
                    }
                }
                return Map.of("notes", out, "count", all.size());
            }
            case "delete": {
                String id = str(inputs.get("id"));
                if (id == null || !ID.matcher(id).matches()) {
                    throw new ServiceHandler.ConnectorException("notes delete needs an 'id' like n3", false);
                }
                boolean removed = all.removeIf(m -> id.equals(m.get("id")));
                if (removed) {
                    save(ns, all);
                }
                return Map.of("deleted", removed, "count", all.size());
            }
            default:
                throw new ServiceHandler.ConnectorException("Unsupported notes operation '" + op + "'", false);
        }
    }

    private static int limit(Map<String, Object> inputs) {
        return inputs.get("limit") instanceof Number n ? (int) Math.max(1, Math.min(50, n.longValue())) : 10;
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> load(String ns) throws ServiceHandler.ConnectorException {
        if (dir == null) {
            return memory.computeIfAbsent(ns, k -> new ArrayList<>());
        }
        Path f = dir.resolve(ns + ".json");
        if (!Files.exists(f)) {
            return new ArrayList<>();
        }
        try {
            Object v = Json.read(Files.readString(f, StandardCharsets.UTF_8));
            List<Map<String, Object>> out = new ArrayList<>();
            if (v instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m && m.get("id") instanceof String id && ID.matcher(id).matches()
                            && m.get("text") instanceof String && m.get("title") instanceof String) {
                        out.add((Map<String, Object>) m);
                    }
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            throw new ServiceHandler.ConnectorException("The notebook file cannot be read", true);
        }
    }

    private void save(String ns, List<Map<String, Object>> all) throws ServiceHandler.ConnectorException {
        if (dir == null) {
            memory.put(ns, all);
            return;
        }
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "notes-", ".tmp");
            Files.writeString(tmp, Json.write(all), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(ns + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ServiceHandler.ConnectorException("The notebook file cannot be written", true);
        }
    }
}
