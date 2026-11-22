package tech.kayys.wayang.api;

import java.util.Locale;

/**
 * How this server is used (see {@code docs/EDITIONS.md}).
 *
 * <ul>
 *   <li>{@link #COMMUNITY}: all-in-one. Designs <em>and</em> executes (the default).
 *   <li>{@link #DESIGN}: design only, for the production Studio of the separated edition. Definitions, validation, versions, projects,
 *       secrets and releases work; starting runs, completing tasks, sending messages and firing timers are refused, so this server is never a
 *       production execution path. Runs are executed by generated runtimes.
 * </ul>
 */
public enum Mode {
    COMMUNITY, DESIGN;

    /** From {@code WAYANG_MODE}; unset means {@link #COMMUNITY}. */
    public static Mode fromEnvironment() {
        return parse(System.getenv("WAYANG_MODE"));
    }

    /** A typo is an error, never a silent fall-back to executing. */
    public static Mode parse(String value) {
        if (value == null || value.isBlank()) {
            return COMMUNITY;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "community", "all-in-one", "development" -> COMMUNITY;
            case "design", "design-only" -> DESIGN;
            default -> throw new IllegalArgumentException("WAYANG_MODE must be 'community' or 'design'");
        };
    }

    public boolean executes() {
        return this == COMMUNITY;
    }
}
