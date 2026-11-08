package tech.kayys.wayang.publish;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maps verified Maven coordinates to repository URLs without performing I/O.
 *
 * <p>Every generated URL is checked against the configured repository allow-list.
 * This class deliberately does not follow redirects, download bytes, or treat
 * a repository sidecar as proof of artifact integrity.</p>
 */
public final class MavenArtifactLocationResolver {

    public record Coordinate(String groupId, String artifactId, String version) {
        public Coordinate {
            requireCoordinatePart(groupId, "groupId", true);
            requireCoordinatePart(artifactId, "artifactId", false);
            requireCoordinatePart(version, "version", false);
        }
    }

    public record ArtifactLocation(String repositoryId, Coordinate coordinate,
            URI pom, URI jar, URI pomSha256, URI jarSha256) {
        public ArtifactLocation {
            Objects.requireNonNull(repositoryId, "repositoryId");
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(pom, "pom");
            Objects.requireNonNull(jar, "jar");
            Objects.requireNonNull(pomSha256, "pomSha256");
            Objects.requireNonNull(jarSha256, "jarSha256");
        }
    }

    private final RepositoryPolicy policy;

    public MavenArtifactLocationResolver(RepositoryPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Returns locations in configured repository order; no network request is made. */
    public List<ArtifactLocation> locations(Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        String groupPath = String.join("/", coordinate.groupId().split("\\."));
        String fileBase = coordinate.artifactId() + "-" + coordinate.version();
        String relative = groupPath + "/" + coordinate.artifactId() + "/" + coordinate.version() + "/";
        List<ArtifactLocation> result = new ArrayList<>();
        for (RepositoryPolicy.Repository repository : policy.repositories()) {
            URI base = repository.baseUri();
            URI pom = artifactUri(base, relative + fileBase + ".pom");
            URI jar = artifactUri(base, relative + fileBase + ".jar");
            URI pomSha = artifactUri(base, relative + fileBase + ".pom.sha256");
            URI jarSha = artifactUri(base, relative + fileBase + ".jar.sha256");
            policy.requireAllowed(pom);
            policy.requireAllowed(jar);
            policy.requireAllowed(pomSha);
            policy.requireAllowed(jarSha);
            result.add(new ArtifactLocation(repository.id(), coordinate, pom, jar, pomSha, jarSha));
        }
        return List.copyOf(result);
    }

    private static URI artifactUri(URI base, String relativePath) {
        return URI.create(base.toASCIIString() + relativePath);
    }

    private static void requireCoordinatePart(String value, String field, boolean group) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String pattern = group ? "[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*"
                : "[A-Za-z0-9][A-Za-z0-9._+-]*";
        if (!value.matches(pattern)) {
            throw new IllegalArgumentException("Invalid Maven " + field + ": " + value);
        }
    }
}
