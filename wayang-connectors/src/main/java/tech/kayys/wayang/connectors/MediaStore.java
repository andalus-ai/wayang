package tech.kayys.wayang.connectors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Claim-check storage for generated media: bytes go to disk and only a small reference ({@code mediaId}, {@code mimeType}, {@code bytes})
 * travels through process variables, so images never bloat a run or its history. Ids are content hashes plus an extension from a fixed
 * list, so an id can never be a path.
 */
public final class MediaStore {

    public static final long MAX_BYTES = 20L << 20;
    private static final Map<String, String> EXT = Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp", "image/gif", "gif");
    private static final Set<String> VALID_EXT = Set.copyOf(EXT.values());

    private final Path dir;

    public MediaStore(Path dir) {
        this.dir = dir;
    }

    /** {@code WAYANG_MEDIA_DIR}, else {@code WAYANG_STATE_DIR/media}; null when neither is set. */
    public static MediaStore fromEnvironment() {
        String d = System.getenv("WAYANG_MEDIA_DIR");
        String s = System.getenv("WAYANG_STATE_DIR");
        Path p = d != null && !d.isBlank() ? Path.of(d) : s != null && !s.isBlank() ? Path.of(s).resolve("media") : null;
        return new MediaStore(p);
    }

    /** The image type of the bytes by signature, or null: a server's content-type header is not trusted. */
    public static String sniff(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "image/gif";
        }
        return null;
    }

    public Map<String, Object> save(byte[] bytes) throws ServiceHandler.ConnectorException {
        if (dir == null) {
            throw new ServiceHandler.ConnectorException("No media directory: set WAYANG_MEDIA_DIR or WAYANG_STATE_DIR", false);
        }
        String mime = sniff(bytes);
        if (mime == null) {
            throw new ServiceHandler.ConnectorException("The provider did not return a PNG, JPEG, WebP or GIF image", false);
        }
        if (bytes.length > MAX_BYTES) {
            throw new ServiceHandler.ConnectorException("The image is larger than " + (MAX_BYTES >> 20) + " MiB", false);
        }
        try {
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 16) + "." + EXT.get(mime);
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "media-", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, dir.resolve(id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return Map.of("mediaId", id, "mimeType", mime, "bytes", bytes.length);
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new ServiceHandler.ConnectorException("The image cannot be stored", true);
        }
    }

    /** The stored bytes, or null when the id is malformed or unknown. */
    public byte[] read(String mediaId) throws IOException {
        if (dir == null || mediaId == null || !mediaId.matches("[0-9a-f]{32}\\.[a-z]{3,4}") || !VALID_EXT.contains(mediaId.substring(33))) {
            return null;
        }
        Path f = dir.resolve(mediaId);
        return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
    }
}
