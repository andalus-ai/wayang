package tech.kayys.wayang.api.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedFilesTest {

    @TempDir
    Path dir;

    @Test
    void readsAFileUpToTheLimit() throws IOException {
        Path f = dir.resolve("small");
        Files.writeString(f, "abcde", StandardCharsets.UTF_8);
        assertEquals("abcde", BoundedFiles.readString(f, 5));
        assertEquals("", BoundedFiles.readString(Files.createFile(dir.resolve("empty")), 5));
    }

    @Test
    void refusesALongerFileWithoutEchoingThePath() throws IOException {
        Path f = dir.resolve("big-secret-name");
        Files.write(f, new byte[6]);
        IOException e = assertThrows(IOException.class, () -> BoundedFiles.readString(f, 5));
        assertTrue(e.getMessage().contains("larger than 5 bytes"));
        assertFalse(e.getMessage().contains("big-secret-name"));
    }

    @Test
    void aMissingFileIsAnIoError() {
        assertThrows(IOException.class, () -> BoundedFiles.readString(dir.resolve("nope"), 5));
    }

    @Test
    void keyringAndVaultTokenReadersRefuseAHugeFile() throws IOException {
        Path f = dir.resolve("keys");
        Files.write(f, new byte[BoundedFiles.SMALL + 1]);
        assertThrows(IllegalStateException.class, () -> Keyring.fromFile(f, true));
        var token = tech.kayys.wayang.api.secret.cloud.VaultClient.tokenFile(f);
        assertThrows(tech.kayys.wayang.api.secret.cloud.VaultClient.VaultException.class, token::get);
    }
}
