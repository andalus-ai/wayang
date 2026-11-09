package tech.kayys.wayang.publish;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

class RepositoryPolicyTest {

    @Test
    void allowsArtifactsOnlyWithinConfiguredHttpsRepositoryPath() {
        var policy = new RepositoryPolicy(List.of(
                new RepositoryPolicy.Repository("central", URI.create("https://repo.example/maven2"))));

        assertTrue(policy.allows(URI.create("https://repo.example/maven2/tech/kayys/wayang/wayang-runtime/1.2.3/wayang-runtime-1.2.3.jar")));
        assertDoesNotThrow(() -> policy.requireAllowed(
                URI.create("https://repo.example/maven2/tech/kayys/wayang/wayang-runtime/1.2.3/wayang-runtime-1.2.3.pom")));
        assertFalse(policy.allows(URI.create("https://repo.example/maven/wayang-runtime.jar")));
        assertFalse(policy.allows(URI.create("https://repo.example.evil/maven2/wayang-runtime.jar")));
    }

    @Test
    void rejectsInsecureOrCredentialBearingRepositoryDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryPolicy(List.of(
                new RepositoryPolicy.Repository("insecure", URI.create("http://repo.example/maven")))));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryPolicy(List.of(
                new RepositoryPolicy.Repository("credentials", URI.create("https://user:secret@repo.example/maven")))));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryPolicy(List.of(
                new RepositoryPolicy.Repository("query", URI.create("https://repo.example/maven?mirror=1")))));
    }

    @Test
    void rejectsDuplicateIdsAndDuplicateNormalizedUrls() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryPolicy(List.of(
                new RepositoryPolicy.Repository("one", URI.create("https://repo.example/maven")),
                new RepositoryPolicy.Repository("one", URI.create("https://other.example/maven")))));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryPolicy(List.of(
                new RepositoryPolicy.Repository("one", URI.create("https://repo.example/maven")),
                new RepositoryPolicy.Repository("two", URI.create("https://repo.example/maven/")))));
    }

    @Test
    void rejectsUrlsOutsideTheAllowList() {
        var policy = new RepositoryPolicy(List.of(
                new RepositoryPolicy.Repository("central", URI.create("https://repo.example/maven2"))));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireAllowed(URI.create("https://repo.example/other/artifact.jar")));
    }
}
