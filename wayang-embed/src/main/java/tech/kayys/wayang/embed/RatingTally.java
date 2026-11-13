package tech.kayys.wayang.embed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Counts the thumbs presses on a release's chat replies, per trigger. Only the trigger id, the direction and the time are kept: no chat,
 * user or message text. With a state directory every press is appended to {@code ratings.log} and the counts are read back at start, so a
 * restart keeps them; a file over {@value #MAX_BYTES} bytes is not read and a failed write only means the count is not persisted.
 */
final class RatingTally {

    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final String FILE = "ratings.log";

    private final Path file; // nullable
    private final Map<String, long[]> counts = new LinkedHashMap<>();

    RatingTally(Path stateDir) {
        this.file = stateDir == null ? null : stateDir.resolve(FILE);
        try {
            if (file != null && Files.isRegularFile(file) && Files.size(file) <= MAX_BYTES) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] p = line.split("\t");
                    if (p.length == 3) {
                        add(p[1], p[2]);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // start from zero
        }
    }

    private void add(String trigger, String rating) {
        if (!rating.equals("up") && !rating.equals("down")) {
            return;
        }
        counts.computeIfAbsent(trigger, k -> new long[2])[rating.equals("up") ? 0 : 1]++;
    }

    synchronized void record(String trigger, Object rating, Instant at) {
        if (!(rating instanceof String r) || !(r.equals("up") || r.equals("down"))) {
            return;
        }
        add(trigger, r);
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, at.toEpochMilli() + "\t" + trigger + "\t" + r + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // kept in memory only
        }
    }

    synchronized Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        long up = 0, down = 0;
        Map<String, Object> per = new LinkedHashMap<>();
        for (var e : counts.entrySet()) {
            per.put(e.getKey(), Map.of("up", e.getValue()[0], "down", e.getValue()[1]));
            up += e.getValue()[0];
            down += e.getValue()[1];
        }
        out.put("up", up);
        out.put("down", down);
        out.put("triggers", per);
        return out;
    }
}
