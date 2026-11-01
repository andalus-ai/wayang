package tech.kayys.wayang.publish;

import java.util.Map;
import java.util.regex.Pattern;

/** What the generated project is called and what it is. Every field is validated: they end up in a pom and in Java source. */
public record ReleaseOptions(Target target, String groupId, String artifactId, String version, String packageName, String wayangVersion, int port,
        String gamelanVersion, String quarkusVersion) {

    /**
     * {@code STANDALONE}: a command-line program (run a process once, check, info). {@code SERVICE}: also serves HTTP, and starts that way by
     * default; runs live in memory. {@code GAMELAN}: a Quarkus service whose runs are durable on a Gamelan engine: it hosts the Wayang executors,
     * deploys the workflows to Gamelan at start and serves the same HTTP API and triggers.
     */
    public enum Target {
        STANDALONE, SERVICE, GAMELAN
    }

    public static final String DEFAULT_GAMELAN_VERSION = "0.1.1";
    public static final String DEFAULT_QUARKUS_VERSION = "3.32.2";

    /** The usual form: Gamelan and Quarkus at the versions Wayang itself is built against. */
    public ReleaseOptions(Target target, String groupId, String artifactId, String version, String packageName, String wayangVersion, int port) {
        this(target, groupId, artifactId, version, packageName, wayangVersion, port, DEFAULT_GAMELAN_VERSION, DEFAULT_QUARKUS_VERSION);
    }

    private static final Pattern GROUP = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Pattern ARTIFACT = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,49}");

    public ReleaseOptions {
        if (target == null || groupId == null || !GROUP.matcher(groupId).matches() || groupId.length() > 100) {
            throw new IllegalArgumentException("groupId must be a lower-case dotted name such as com.example");
        }
        if (artifactId == null || !ARTIFACT.matcher(artifactId).matches()) {
            throw new IllegalArgumentException("artifactId must be lower-case letters, digits, '.', '_' or '-'");
        }
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("version must be letters, digits, '.', '_' or '-'");
        }
        if (packageName == null || !GROUP.matcher(packageName).matches() || packageName.length() > 100) {
            throw new IllegalArgumentException("package must be a lower-case dotted Java package such as com.example.release");
        }
        for (String part : packageName.split("\\.")) {
            if (JAVA_KEYWORDS.contains(part)) {
                throw new IllegalArgumentException("package segment '" + part + "' is a Java keyword");
            }
        }
        if (wayangVersion == null || !VERSION.matcher(wayangVersion).matches()) {
            throw new IllegalArgumentException("wayangVersion must be letters, digits, '.', '_' or '-'");
        }
        if (gamelanVersion == null || !VERSION.matcher(gamelanVersion).matches() || quarkusVersion == null || !VERSION.matcher(quarkusVersion).matches()) {
            throw new IllegalArgumentException("gamelanVersion and quarkusVersion must be letters, digits, '.', '_' or '-'");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be 1..65535");
        }
    }

    private static final java.util.Set<String> JAVA_KEYWORDS = java.util.Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return",
            "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "_");

    /** From a request body; missing fields take the defaults. */
    public static ReleaseOptions of(Map<String, Object> m, String defaultArtifact, String wayangVersion) {
        Map<String, Object> o = m == null ? Map.of() : m;
        Target t;
        try {
            t = Target.valueOf(String.valueOf(o.getOrDefault("target", "SERVICE")).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("target must be STANDALONE, SERVICE or GAMELAN");
        }
        String artifact = str(o, "artifactId", defaultArtifact);
        int port = 8080;
        if (o.get("port") instanceof Number n) {
            port = n.intValue();
        }
        return new ReleaseOptions(t, str(o, "groupId", "com.example"), artifact, str(o, "version", "1.0.0"),
                str(o, "package", "com.example." + artifact.replaceAll("[^a-z0-9]", "").replaceAll("^[0-9]+", "") + (artifact.replaceAll("[^a-z0-9]", "").isEmpty() ? "release" : "")),
                str(o, "wayangVersion", wayangVersion), port, str(o, "gamelanVersion", DEFAULT_GAMELAN_VERSION),
                str(o, "quarkusVersion", DEFAULT_QUARKUS_VERSION));
    }

    private static String str(Map<String, Object> o, String k, String dflt) {
        Object v = o.get(k);
        return v == null ? dflt : String.valueOf(v);
    }
}
