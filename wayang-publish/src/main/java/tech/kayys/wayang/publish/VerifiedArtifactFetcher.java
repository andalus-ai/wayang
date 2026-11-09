package tech.kayys.wayang.publish;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Fetches the POM and JAR for one coordinate using only digests from a verified,
 * signed release manifest. Repository order is preserved as a failover policy.
 */
public final class VerifiedArtifactFetcher {

    public record CachedArtifact(ReleaseArtifactResolver.Coordinate coordinate, Path pom, Path jar) {
        public CachedArtifact {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(pom, "pom");
            Objects.requireNonNull(jar, "jar");
        }
    }

    private final MavenArtifactLocationResolver locations;
    private final VerifiedArtifactCache cache;

    public VerifiedArtifactFetcher(MavenArtifactLocationResolver locations, VerifiedArtifactCache cache) {
        this.locations = Objects.requireNonNull(locations, "locations");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    public CachedArtifact fetch(ReleaseArtifactResolver.Coordinate coordinate,
            ReleaseArtifactDigestManifest.VerifiedManifest manifest) throws IOException, InterruptedException {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(manifest, "manifest");
        var digest = manifest.artifact(coordinate);
        List<MavenArtifactLocationResolver.ArtifactLocation> candidates =
                locations.locations(new MavenArtifactLocationResolver.Coordinate(
                        coordinate.groupId(), coordinate.artifactId(), coordinate.version()));
        IOException lastFailure = null;
        for (var candidate : candidates) {
            try {
                Path pom = cache.fetch(candidate.pom(), digest.pomSha256());
                Path jar = cache.fetch(candidate.jar(), digest.jarSha256());
                return new CachedArtifact(coordinate, pom, jar);
            } catch (IOException failure) {
                if (lastFailure != null) failure.addSuppressed(lastFailure);
                lastFailure = failure;
            }
        }
        if (lastFailure != null) throw new IOException(
                "Unable to fetch verified POM and JAR for " + coordinate.artifactId()
                        + " from configured repositories", lastFailure);
        throw new IOException("No configured repository is available for " + coordinate.artifactId());
    }
}
