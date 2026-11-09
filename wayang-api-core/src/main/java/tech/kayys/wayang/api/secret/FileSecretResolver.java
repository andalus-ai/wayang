package tech.kayys.wayang.api.secret;

import tech.kayys.wayang.api.secret.cloud.VaultSecretResolver;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.SecretResolver;

/**
 * Secrets mounted as files (Docker/Kubernetes secrets, systemd credentials): {@code ${secret:file.NAME}} reads
 * {@code <dir>/<tenant>/NAME}. The tenant comes from the run, never the name; the real path must stay under the directory
 * (a symlink pointing outside is refused, though Kubernetes' own {@code ..data} links inside the directory are fine); only
 * regular files up to the secret size limit are read; one trailing newline is dropped. Anything wrong reads as "no such
 * secret" and the reason goes to the log without the path or content. Not cached: the file is the source of truth, so a
 * rotated mount takes effect on the next run.
 */
public final class FileSecretResolver implements SecretResolver {

    public static final String PREFIX = "file.";
    private static final Pattern REST = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)*");
    private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}");

    private final Path dir;
    private final java.util.function.Consumer<String> onError;

    public FileSecretResolver(Path dir, java.util.function.Consumer<String> onError) {
        this.dir = dir.toAbsolutePath().normalize();
        this.onError = onError;
    }

    public static boolean isFileName(String name) {
        return name != null && name.startsWith(PREFIX);
    }

    @Override
    public String resolve(String tenantId, String name) {
        if (tenantId == null || !isFileName(name) || !TENANT.matcher(tenantId).matches() || tenantId.contains("..")) {
            return null;
        }
        String rest = name.substring(PREFIX.length());
        if (rest.isEmpty() || rest.length() > 100 || !REST.matcher(rest).matches()) {
            return null;
        }
        try {
            Path root = dir.toRealPath();
            Path f = root.resolve(tenantId).resolve(rest);
            if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(f)) {
                return null;
            }
            Path real = f.toRealPath();
            if (!real.startsWith(root) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                onError.accept("A file secret of tenant " + tenantId + " was refused: it is not a regular file inside the secrets directory");
                return null;
            }
            if (Files.size(real) > (long) SecretService.MAX_VALUE_CHARS * 4) {
                onError.accept("A file secret of tenant " + tenantId + " was refused: too large");
                return null;
            }
            String v = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(real))).toString();
            if (v.endsWith("\r\n")) {
                v = v.substring(0, v.length() - 2);
            } else if (v.endsWith("\n")) {
                v = v.substring(0, v.length() - 1);
            }
            return v.isEmpty() || v.length() > SecretService.MAX_VALUE_CHARS ? null : v;
        } catch (CharacterCodingException e) {
            onError.accept("A file secret of tenant " + tenantId + " was refused: not valid UTF-8");
            return null;
        } catch (IOException | RuntimeException e) {
            onError.accept("A file secret of tenant " + tenantId + " could not be read: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Route {@code file.*} to files, {@code vault.*} to Vault (when configured), the rest to the local store. */
    public static SecretResolver routing(SecretResolver local, SecretResolver vault, SecretResolver file) {
        return (tenant, name) -> {
            if (isFileName(name)) {
                return file == null ? null : file.resolve(tenant, name);
            }
            if (VaultSecretResolver.isVaultName(name)) {
                return vault == null ? null : vault.resolve(tenant, name);
            }
            return local == null ? null : local.resolve(tenant, name);
        };
    }
}
