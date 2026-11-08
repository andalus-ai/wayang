package tech.kayys.wayang.publish;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

class MavenArtifactLocationResolverTest {

    @Test
    void mapsCoordinatesToMavenLayoutAndChecksEveryLocationAgainstPolicy() {
        var policy = new RepositoryPolicy(List.of(
                new RepositoryPolicy.Repository("internal", URI.create("https://repo.example/maven/releases"))));
        var resolver = new MavenArtifactLocationResolver(policy);

        var locations = resolver.locations(
                new MavenArtifactLocationResolver.Coordinate("tech.kayys.wayang", "wayang-runtime", "1.2.3"));

        assertEquals(1, locations.size());
        var location = locations.get(0);
        assertEquals("internal", location.repositoryId());
        assertEquals("https://repo.example/maven/releases/tech/kayys/wayang/wayang-runtime/1.2.3/wayang-runtime-1.2.3.pom",
                location.pom().toString());
        assertEquals("https://repo.example/maven/releases/tech/kayys/wayang/wayang-runtime/1.2.3/wayang-runtime-1.2.3.jar",
                location.jar().toString());
        assertTrue(policy.allows(location.pomSha256()));
        assertTrue(policy.allows(location.jarSha256()));
    }

    @Test
    void preservesConfiguredRepositoryPriority() {
        var policy = new RepositoryPolicy(List.of(
                new RepositoryPolicy.Repository("primary", URI.create("https://one.example/maven")),
                new RepositoryPolicy.Repository("mirror", URI.create("https://two.example/repository"))));

        var locations = new MavenArtifactLocationResolver(policy).locations(
                new MavenArtifactLocationResolver.Coordinate("org.example", "engine", "2.0.0"));

        assertEquals(List.of("primary", "mirror"),
                locations.stream().map(MavenArtifactLocationResolver.ArtifactLocation::repositoryId).toList());
    }

    @Test
    void rejectsCoordinatePathInjectionAndInvalidMavenParts() {
        assertThrows(IllegalArgumentException.class,
                () -> new MavenArtifactLocationResolver.Coordinate("org.example/../../evil", "engine", "1.0"));
        assertThrows(IllegalArgumentException.class,
                () -> new MavenArtifactLocationResolver.Coordinate("org.example", "../engine", "1.0"));
        assertThrows(IllegalArgumentException.class,
                () -> new MavenArtifactLocationResolver.Coordinate("org.example", "engine", "../1.0"));
    }

    @Test
    void returnsImmutableLocations() {
        var resolver = new MavenArtifactLocationResolver(new RepositoryPolicy(List.of(
                new RepositoryPolicy.Repository("central", URI.create("https://repo.example/maven2")))));
        var locations = resolver.locations(new MavenArtifactLocationResolver.Coordinate("org.example", "engine", "1.0"));
        assertThrows(UnsupportedOperationException.class, () -> locations.add(locations.get(0)));
    }
}
