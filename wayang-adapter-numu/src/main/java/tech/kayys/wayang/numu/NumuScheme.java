package tech.kayys.wayang.numu;

import java.util.regex.Pattern;

import tech.kayys.wayang.api.Permission;

/**
 * How Wayang maps onto Numu's path-scoped permissions.
 *
 * <pre>
 *   scope path   = &lt;tenant&gt;.wayang            (a role granted at &lt;tenant&gt; or &lt;tenant&gt;.wayang applies here)
 *   project path = &lt;tenant&gt;.wayang.project.&lt;id&gt;
 *   permission   = wayang.definition.read, wayang.run.start, ...   (see {@link #code})
 * </pre>
 *
 * Numu grants inherit down the dot-separated path, so a tenant administrator holding roles at {@code <tenant>}
 * automatically has them here. Segments must not contain '.', otherwise a crafted tenant or project id could
 * address a different scope ("a.wayang" is a tenant id that would otherwise alias into tenant "a").
 */
public final class NumuScheme {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_@-]{1,128}");

    private final String product;

    public NumuScheme() {
        this("wayang");
    }

    public NumuScheme(String product) {
        if (!SEGMENT.matcher(product).matches()) {
            throw new IllegalArgumentException("Invalid product segment");
        }
        this.product = product;
    }

    /** A tenant id usable as one scope segment. */
    public boolean validSegment(String s) {
        return s != null && SEGMENT.matcher(s).matches();
    }

    public String tenantPath(String tenantId) {
        requireSegment(tenantId, "tenant");
        return tenantId + "." + product;
    }

    public String projectPath(String tenantId, String projectId) {
        requireSegment(projectId, "project");
        return tenantPath(tenantId) + ".project." + projectId;
    }

    /** {@code DEFINITION_READ} becomes {@code wayang.definition.read}. */
    public String code(Permission p) {
        return product + "." + p.name().toLowerCase(java.util.Locale.ROOT).replace('_', '.');
    }

    private void requireSegment(String s, String what) {
        if (!validSegment(s)) {
            throw new IllegalArgumentException("Invalid " + what + " id for a scope path");
        }
    }
}
