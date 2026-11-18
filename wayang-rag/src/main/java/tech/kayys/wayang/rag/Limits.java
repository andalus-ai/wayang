package tech.kayys.wayang.rag;

/** Bounds applied to every document before and while it is read. Untrusted uploads must not be able to exhaust memory or time. */
public record Limits(long maxBytes, int maxChars, int maxZipEntries, int maxEntryBytes, int maxRows) {

    public static final Limits DEFAULT = new Limits(20L << 20, 5_000_000, 2000, 32 << 20, 200_000);

    public Limits {
        if (maxBytes < 1 || maxChars < 1 || maxZipEntries < 1 || maxEntryBytes < 1 || maxRows < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }
}
