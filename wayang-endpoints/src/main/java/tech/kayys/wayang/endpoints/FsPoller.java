package tech.kayys.wayang.endpoints;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * Trigger source {@code fs}: a run per new file in a directory. Config: {@code path} (directory, default the endpoint directory), {@code glob}
 * (default {@code *}), {@code settleSeconds} (0-3600, default 2: a file modified more recently is left for the next poll, so a half-written file
 * is never handed over), plus the injected {@code wayang.tenant}. The cursor is the (modified time, name) of the last file handed over, so a file
 * is seen once, in order, and delivery is at-least-once like every poller. Events are small and flat: {@code path, name, size, modifiedAt}; the
 * workflow reads the file with {@code fs-read} (and may move it away with {@code fs-move}).
 */
final class FsPoller implements Poller {

    static final int MAX_EVENTS = 100;

    private final FsRoot root;
    private final Clock clock;

    FsPoller(FsRoot root, Clock clock) {
        this.root = root;
        this.clock = clock;
    }

    @Override
    public Result poll(Map<String, Object> config, String cursor) throws Exception {
        String tenant = FsRoot.tenant(config);
        Path dir = root.resolve(tenant, config.get("path") == null ? "" : String.valueOf(config.get("path")));
        long settleMs = settle(config) * 1000L;
        long cutoff = clock.millis() - settleMs;
        long afterTime = Long.MIN_VALUE;
        String afterName = "";
        if (cursor != null && cursor.contains("|")) {
            try {
                afterTime = Long.parseLong(cursor.substring(0, cursor.indexOf('|')));
                afterName = cursor.substring(cursor.indexOf('|') + 1);
            } catch (NumberFormatException e) {
                afterTime = Long.MIN_VALUE; // a damaged cursor starts over rather than stopping the trigger
            }
        } else if (cursor == null) {
            // first poll: only files that appear from now on (an existing backlog is not replayed unless asked)
            if (!Boolean.TRUE.equals(config.get("includeExisting")) && !"true".equalsIgnoreCase(String.valueOf(config.get("includeExisting")))) {
                return new Result(List.of(), clock.millis() + "|");
            }
        }
        if (!Files.isDirectory(dir)) {
            return new Result(List.of(), null);
        }
        Path base = root.base(tenant);
        record Seen(Path path, long time, String name, long size) {}
        List<Seen> fresh = new ArrayList<>();
        for (Path f : FsConnectors.matching(dir, config.get("glob") == null ? null : String.valueOf(config.get("glob")))) {
            BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class);
            long t = a.lastModifiedTime().toMillis();
            String n = f.getFileName().toString();
            boolean after = t > afterTime || (t == afterTime && n.compareTo(afterName) > 0);
            if (after && t <= cutoff) {
                fresh.add(new Seen(f, t, n, a.size()));
            }
        }
        fresh.sort((x, y) -> x.time != y.time ? Long.compare(x.time, y.time) : x.name.compareTo(y.name));
        List<Map<String, Object>> events = new ArrayList<>();
        Seen last = null;
        for (Seen s : fresh) {
            if (events.size() >= MAX_EVENTS) {
                break;
            }
            events.add(Map.of("path", FsRoot.relative(base, s.path), "name", s.name, "size", s.size, "modifiedAt", Instant.ofEpochMilli(s.time).toString()));
            last = s;
        }
        return new Result(events, last == null ? null : last.time + "|" + last.name);
    }

    private static int settle(Map<String, Object> config) throws ConnectorException {
        Object v = config.get("settleSeconds");
        if (v == null || v instanceof String s && s.isBlank()) {
            return 2;
        }
        try {
            int n = v instanceof Number x ? x.intValue() : Integer.parseInt(String.valueOf(v).trim());
            if (n < 0 || n > 3600) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new ConnectorException("settleSeconds must be a whole number from 0 to 3600", false);
        }
    }
}
