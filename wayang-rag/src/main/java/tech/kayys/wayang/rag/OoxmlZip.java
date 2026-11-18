package tech.kayys.wayang.rag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads only the wanted parts of an Office Open XML package. Unwanted entries are never inflated (so a zip bomb hidden in an unused part costs nothing),
 * the entry count and every wanted entry's size are bounded, and an entry name that tries to leave the package is ignored.
 */
final class OoxmlZip {

    private OoxmlZip() {
    }

    static Map<String, byte[]> read(byte[] content, Limits limits, Predicate<String> wanted) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("wayang-rag-", ".zip");
            Files.write(tmp, content);
            Map<String, byte[]> out = new LinkedHashMap<>();
            try (ZipFile zip = new ZipFile(tmp.toFile())) {
                if (zip.size() > limits.maxZipEntries()) {
                    throw new RagException("the file has too many parts (limit " + limits.maxZipEntries() + ")");
                }
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String name = e.getName();
                    if (e.isDirectory() || name.contains("..") || name.startsWith("/") || !wanted.test(name)) {
                        continue;
                    }
                    out.put(name, bounded(zip.getInputStream(e), limits.maxEntryBytes()));
                }
            }
            return out;
        } catch (IOException e) {
            throw new RagException("the file is not a readable Office document", e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a leftover temp file is harmless and is removed with the temp directory
                }
            }
        }
    }

    private static byte[] bounded(InputStream in, int max) throws IOException {
        try (in) {
            byte[] buf = in.readNBytes(max + 1);
            if (buf.length > max) {
                throw new RagException("a part of the file is larger than " + (max >> 20) + " MiB once unpacked");
            }
            return buf;
        }
    }

    static boolean has(byte[] content, String entryName) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("wayang-rag-", ".zip");
            Files.write(tmp, content);
            try (ZipFile zip = new ZipFile(tmp.toFile())) {
                return zip.getEntry(entryName) != null;
            }
        } catch (IOException e) {
            return false;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // see read()
                }
            }
        }
    }
}
