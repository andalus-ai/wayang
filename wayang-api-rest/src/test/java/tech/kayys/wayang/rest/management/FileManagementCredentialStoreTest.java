package tech.kayys.wayang.rest.management;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileManagementCredentialStoreTest {
    @TempDir Path directory;

    @Test
    void savesLoadsAndClearsCredentialWithoutCreatingWorldReadableFile() throws Exception {
        FileManagementCredentialStore store = store(directory.resolve("secrets/registration-token"));
        assertTrue(store.load().isEmpty());

        store.save("registration-secret-value");

        assertEquals(Optional.of("registration-secret-value"), store.load());
        Path file = directory.resolve("secrets/registration-token");
        assertTrue(Files.isRegularFile(file));
        try {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems are covered by deployment ACLs.
        }

        store.clear();
        assertTrue(store.load().isEmpty());
    }

    @Test
    void rejectsBlankAndMultilineCredentials() throws Exception {
        FileManagementCredentialStore store = store(directory.resolve("registration-token"));
        assertThrows(IllegalArgumentException.class, () -> store.save(" "));
        assertThrows(IllegalArgumentException.class, () -> store.save("secret\\nsecond-line"));
        assertFalse(Files.exists(directory.resolve("registration-token")));
    }

    @Test
    void rejectsSymbolicLinkCredentialPath() throws Exception {
        Path target = directory.resolve("target");
        Files.writeString(target, "do-not-read");
        Path link = directory.resolve("registration-token");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            return;
        }
        FileManagementCredentialStore store = store(link);
        assertThrows(java.io.IOException.class, store::load);
    }

    private static FileManagementCredentialStore store(Path path) throws Exception {
        FileManagementCredentialStore store = new FileManagementCredentialStore();
        Field field = FileManagementCredentialStore.class.getDeclaredField("configuredPath");
        field.setAccessible(true);
        field.set(store, Optional.of(path.toString()));
        return store;
    }
}
