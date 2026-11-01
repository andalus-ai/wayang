package tech.kayys.wayang.embed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.kayys.wayang.agent.ConversationMemory;
import tech.kayys.wayang.runtime.json.Json;

/**
 * {@link ConversationMemory} in a directory, one file per conversation, so a restarted release keeps talking where it left off.
 * File names are a hash of the namespace and key (nothing a person typed reaches a path); writes are atomic. It is for one process: two
 * replicas sharing a directory could overwrite each other's turns. Turns older than the retention are dropped on every 64th append.
 */
public final class FileConversationMemory implements ConversationMemory {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_CONVERSATIONS = 10_000;
    private static final long RETENTION_MS = 30L * 86_400_000;

    private final Path dir;
    private int appends;

    public FileConversationMemory(Path dir) {
        this.dir = dir;
    }

    /** A file memory in {@code dir}, or an in-memory one when no directory is given. */
    public static ConversationMemory forDir(String dir) {
        return dir == null || dir.isBlank() ? new ConversationMemory.InMemory(10_000) : new FileConversationMemory(Path.of(dir));
    }

    private Path file(String ns, String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((ns + '\0' + key).getBytes(StandardCharsets.UTF_8));
            return dir.resolve("memory-" + HexFormat.of().formatHex(h) + ".json");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized List<Turn> load(String ns, String key, int maxTurns) {
        List<Turn> all = read(file(ns, key));
        return List.copyOf(all.subList(Math.max(0, all.size() - Math.max(0, maxTurns)), all.size()));
    }

    @Override
    public synchronized void append(String ns, String key, Turn turn, int maxTurns) {
        Path f = file(ns, key);
        try {
            Files.createDirectories(dir);
            if (!Files.exists(f) && count() >= MAX_CONVERSATIONS) {
                return; // a full directory forgets new conversations rather than growing without bound
            }
            List<Turn> all = new ArrayList<>(read(f));
            all.add(turn);
            while (all.size() > Math.max(1, maxTurns)) {
                all.remove(0);
            }
            write(f, all);
            if (++appends % 64 == 0) {
                purge(System.currentTimeMillis() - RETENTION_MS);
            }
        } catch (IOException | RuntimeException e) {
            // best effort: a conversation that cannot be saved is a conversation the next message starts without
        }
    }

    @Override
    public synchronized boolean clear(String ns, String key) {
        try {
            return Files.deleteIfExists(file(ns, key));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public synchronized int purge(long cutoffMs) {
        int removed = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "memory-*.json")) {
            for (Path f : ds) {
                List<Turn> turns = read(f);
                List<Turn> keep = turns.stream().filter(t -> t.atMs() >= cutoffMs).toList();
                if (keep.size() == turns.size()) {
                    continue;
                }
                removed += turns.size() - keep.size();
                if (keep.isEmpty()) {
                    Files.deleteIfExists(f);
                } else {
                    write(f, keep);
                }
            }
        } catch (IOException | RuntimeException e) {
            // best effort
        }
        return removed;
    }

    private int count() throws IOException {
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "memory-*.json")) {
            for (Path ignored : ds) {
                n++;
            }
        }
        return n;
    }

    private static List<Turn> read(Path f) {
        try {
            if (!Files.isRegularFile(f) || Files.size(f) > MAX_FILE_BYTES) {
                return List.of();
            }
            List<Turn> out = new ArrayList<>();
            if (Json.read(Files.readString(f, StandardCharsets.UTF_8)) instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m && m.get("user") instanceof String u && m.get("assistant") instanceof String a) {
                        out.add(new Turn(u, a, m.get("at") instanceof Number n ? n.longValue() : 0L));
                    }
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return List.of(); // unreadable or damaged: start the conversation over
        }
    }

    private void write(Path f, List<Turn> turns) throws IOException {
        List<Object> out = new ArrayList<>();
        for (Turn t : turns) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("user", t.user());
            m.put("assistant", t.assistant());
            m.put("at", t.atMs());
            out.add(m);
        }
        Path tmp = Files.createTempFile(dir, "memory-", ".tmp");
        try {
            Files.writeString(tmp, Json.write(out), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
