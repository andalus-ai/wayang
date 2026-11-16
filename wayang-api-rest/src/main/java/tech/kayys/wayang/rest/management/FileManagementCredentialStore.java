package tech.kayys.wayang.rest.management;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Optional single-node credential store. Configure a path on a persistent, access-controlled
 * volume; clustered deployments should replace this bean with a shared secret-store adapter.
 */
@ApplicationScoped
public class FileManagementCredentialStore implements ManagementCredentialStore {
    private static final Set<PosixFilePermission> FILE_MODE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> DIR_MODE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    @ConfigProperty(name = "wayang.management.credential-file")
    Optional<String> configuredPath;

    private Path path() throws IOException {
        String value = configuredPath.orElseThrow(() ->
                new IOException("wayang.management.credential-file must be configured before management credentials can be stored"));
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException("Management credential path must not be a symbolic link");
        }
        return path;
    }

    @Override
    public synchronized Optional<String> load() throws IOException {
        Path target = path();
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw new IOException("Management credential path is not a regular file");
        }
        String token = Files.readString(target, StandardCharsets.UTF_8).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    @Override
    public synchronized void save(String registrationToken) throws IOException {
        if (registrationToken == null || registrationToken.isBlank()
                || registrationToken.indexOf('\\n') >= 0 || registrationToken.indexOf('\\r') >= 0) {
            throw new IllegalArgumentException("registrationToken must be a non-blank single-line value");
        }
        Path target = path();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Management credential file must have a parent directory");
        Files.createDirectories(parent);
        setPermissionsIfSupported(parent, DIR_MODE);
        Path temporary = Files.createTempFile(parent, ".wayang-management-", ".tmp");
        try {
            setPermissionsIfSupported(temporary, FILE_MODE);
            Files.writeString(temporary, registrationToken, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            setPermissionsIfSupported(target, FILE_MODE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public synchronized void clear() throws IOException {
        Files.deleteIfExists(path());
    }

    private static void setPermissionsIfSupported(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows and some mounted filesystems do not expose POSIX permissions. Operators
            // must enforce equivalent ACLs at the filesystem/container boundary.
        }
    }
}
