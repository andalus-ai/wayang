package tech.kayys.wayang.embed;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ConfigResolver;
import tech.kayys.wayang.runtime.SecretResolver;

/**
 * Where a release finds {@code ${secret:NAME}} and {@code ${config:NAME}}: the environment of the process it runs in, never the
 * bundle (a bundle carries no secret values). A secret is the file {@code <secrets dir>/NAME} (Docker/Kubernetes secrets; one
 * trailing newline dropped) or else the variable {@code WAYANG_SECRET_<NAME>} (upper case, {@code . -} become {@code _}). A
 * setting is the variable {@code WAYANG_CONFIG_<NAME>}, else the value the bundle carried. Names of the cloud or Vault forms
 * ({@code vault.}, {@code aws.}, ...) are not supported in a release and read as missing.
 */
public final class ReleaseSecrets {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final List<String> UNSUPPORTED = List.of("vault.", "file.", "aws.", "gcp.", "azure.");
    private static final long MAX_FILE = 256 * 1024;

    private ReleaseSecrets() {
    }

    public static String envName(String prefix, String name) {
        return prefix + name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    public static boolean supported(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return false;
        }
        for (String p : UNSUPPORTED) {
            if (name.startsWith(p)) {
                return false;
            }
        }
        return true;
    }

    /** @param secretsDir null to use the environment only; @param env the environment (a map in tests) */
    public static SecretResolver secrets(Path secretsDir, Function<String, String> env) {
        return (tenant, name) -> {
            if (!supported(name)) {
                return null;
            }
            if (secretsDir != null) {
                String v = readFile(secretsDir, name);
                if (v != null) {
                    return v;
                }
            }
            String v = env.apply(envName("WAYANG_SECRET_", name));
            return v == null || v.isEmpty() ? null : v;
        };
    }

    public static ConfigResolver config(Map<String, String> bundled, Function<String, String> env) {
        return (tenant, process, name) -> {
            if (name == null || !NAME.matcher(name).matches()) {
                return null;
            }
            String v = env.apply(envName("WAYANG_CONFIG_", name));
            return v != null ? v : bundled.get(name);
        };
    }

    private static String readFile(Path dir, String name) {
        try {
            Path root = dir.toRealPath();
            Path f = root.resolve(name);
            if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            Path real = f.toRealPath();
            if (!real.startsWith(root) || !Files.isRegularFile(real) || Files.size(real) > MAX_FILE) {
                return null;
            }
            String v = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(real))).toString();
            if (v.endsWith("\r\n")) {
                v = v.substring(0, v.length() - 2);
            } else if (v.endsWith("\n")) {
                v = v.substring(0, v.length() - 1);
            }
            return v.isEmpty() ? null : v;
        } catch (CharacterCodingException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
