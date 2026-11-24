package tech.kayys.wayang.endpoints.sftp;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * Trigger source {@code sftp}: a run per new file in a remote directory. Config: server settings (see {@link Sftp}), {@code path} (directory,
 * default the home directory), {@code glob} (file name pattern with {@code *} and {@code ?}, default all), {@code settleSeconds} (default 5: a file
 * is reported only once its modification time is at least that old, so a file still being uploaded is not picked up half written),
 * {@code includeExisting} (default false: the first poll only notes where it is) and {@code maxFiles} (1-50, default 20). The cursor is
 * {@code <mtime seconds>|<name>} of the last reported file, so the trigger is at-least-once and ordered by modification time then name. The event
 * carries {@code name}, {@code path}, {@code size}, {@code mtime}; fetch the content with {@code sftp-read} and, to avoid reprocessing after a
 * restart that loses the cursor, move or delete the file when done. A file replaced with an older modification time is not seen again.
 */
final class SftpPoller implements Poller {

    private final Sftp sftp;
    private final Clock clock;

    SftpPoller(Sftp sftp, Clock clock) {
        this.sftp = sftp;
        this.clock = clock;
    }

    @Override
    public Result poll(Map<String, Object> config, String cursor) throws Exception {
        Sftp.Target target = sftp.target(config);
        String dir = Sftp.path(config.get("path"), true);
        Pattern glob = glob(config.get("glob"));
        long settle = number(config.get("settleSeconds"), 5, 0, 3600, "settleSeconds");
        int max = (int) number(config.get("maxFiles"), 20, 1, 50, "maxFiles");
        boolean existing = Boolean.parseBoolean(String.valueOf(config.get("includeExisting")));
        long[] after = parse(cursor);
        long ceiling = clock.instant().getEpochSecond() - settle;
        List<Sftp.Entry> found;
        try (Sftp.Session s = sftp.opener.open(target)) {
            found = new ArrayList<>(s.list(dir));
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            throw Sftp.fail("polling the sftp directory failed", e, Sftp.transientFailure(e));
        }
        List<Sftp.Entry> files = found.stream().filter(Sftp.Entry::file).filter(e -> glob.matcher(e.name()).matches())
                .filter(e -> e.mtimeSeconds() <= ceiling)
                .sorted(Comparator.comparingLong(Sftp.Entry::mtimeSeconds).thenComparing(Sftp.Entry::name)).toList();
        if (cursor == null && !existing) {
            // note where we are, report nothing: only files that arrive later
            return new Result(List.of(), files.isEmpty() ? format(ceiling, "") : format(last(files).mtimeSeconds(), last(files).name()));
        }
        List<Map<String, Object>> events = new ArrayList<>();
        Sftp.Entry lastSeen = null;
        for (Sftp.Entry e : files) {
            if (cursor != null && (e.mtimeSeconds() < after[0] || (e.mtimeSeconds() == after[0] && e.name().compareTo(name(cursor)) <= 0))) {
                continue;
            }
            if (events.size() >= max) {
                break;
            }
            events.add(SftpConnectors.entry(dir, e));
            lastSeen = e;
        }
        return new Result(events, lastSeen == null ? null : format(lastSeen.mtimeSeconds(), lastSeen.name()));
    }

    private static Sftp.Entry last(List<Sftp.Entry> l) {
        return l.get(l.size() - 1);
    }

    static String format(long mtime, String name) {
        return mtime + "|" + name;
    }

    private static String name(String cursor) {
        return cursor.substring(cursor.indexOf('|') + 1);
    }

    private static long[] parse(String cursor) throws ConnectorException {
        if (cursor == null) {
            return new long[] {0};
        }
        int bar = cursor.indexOf('|');
        try {
            if (bar < 1) {
                throw new NumberFormatException();
            }
            return new long[] {Long.parseLong(cursor.substring(0, bar))};
        } catch (NumberFormatException e) {
            throw new ConnectorException("the saved sftp position is not valid", false);
        }
    }

    private static long number(Object v, long dflt, long min, long max, String name) throws ConnectorException {
        if (v == null) {
            return dflt;
        }
        try {
            long n = Long.parseLong(String.valueOf(v).trim());
            if (n < min || n > max) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new ConnectorException(name + " must be a number from " + min + " to " + max, false);
        }
    }

    static Pattern glob(Object g) throws ConnectorException {
        if (g == null || String.valueOf(g).isEmpty()) {
            return Pattern.compile(".*");
        }
        String s = String.valueOf(g);
        if (s.length() > 128 || s.indexOf('/') >= 0) {
            throw new ConnectorException("glob is a file name pattern (no /) of at most 128 characters", false);
        }
        StringBuilder re = new StringBuilder();
        for (char c : s.toCharArray()) {
            re.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(re.toString());
    }
}
