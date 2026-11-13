package tech.kayys.wayang.embed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import tech.kayys.wayang.runtime.RateLimiter;

/**
 * Keeps a release's per-chat rate-limit counts in {@code rate-events.log} inside the state directory, so a restart does not hand every chat a
 * fresh allowance. One line per allowed event ({@code scope TAB sha256(key) TAB epoch-millis}); chat ids are stored hashed. Only one running
 * copy of a release should use a state directory (as for the poll cursors). The file is read once per key and a read or write that fails
 * simply means the limit holds in memory only; a file bigger than {@value #MAX_BYTES} bytes is not read at all.
 */
final class FileRateJournal implements RateLimiter.Journal {

    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final String FILE = "rate-events.log";

    private final Path file;

    FileRateJournal(Path stateDir) {
        this.file = stateDir.resolve(FILE);
    }

    @Override
    public synchronized List<Instant> load(String scope, String key, Instant since) {
        List<Instant> out = new ArrayList<>();
        try {
            if (!Files.isRegularFile(file)) {
                return out;
            }
            if (Files.size(file) > MAX_BYTES) {
                throw new IllegalStateException("rate journal too large");
            }
            String h = hash(key);
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length == 3 && p[0].equals(scope) && p[1].equals(h)) {
                    try {
                        Instant at = Instant.ofEpochMilli(Long.parseLong(p[2]));
                        if (at.isAfter(since)) {
                            out.add(at);
                        }
                    } catch (NumberFormatException e) {
                        // a damaged line is skipped
                    }
                }
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("rate journal unreadable");
        }
    }

    @Override
    public synchronized void record(String scope, String key, Instant at) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, scope + "\t" + hash(key) + "\t" + at.toEpochMilli() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("rate journal unwritable");
        }
    }

    @Override
    public synchronized void purgeBefore(Instant before) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
                return;
            }
            List<String> keep = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                try {
                    if (p.length == 3 && !Instant.ofEpochMilli(Long.parseLong(p[2])).isBefore(before)) {
                        keep.add(line);
                    }
                } catch (NumberFormatException e) {
                    // dropped with the old ones
                }
            }
            Path tmp = Files.createTempFile(file.getParent(), "rate-", ".tmp");
            Files.write(tmp, keep, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("rate journal purge failed");
        }
    }

    private static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
