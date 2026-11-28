package tech.kayys.wayang.agent;

import java.util.Optional;

/** Durable storage for publisher-verified Agent Package archives. */
public interface AgentPackageArtifactStore {
    /** Stores the exact archive accepted by the verifier. */
    ArtifactReference put(AgentPackageArtifactVerifier.VerifiedArtifact verified, byte[] archive);

    /** Retrieves by full lowercase SHA-256 digest and verifies bytes on read. */
    Optional<StoredArtifact> get(String sha256);

    /** Resolves an immutable package ID/version coordinate to its content digest. */
    Optional<ArtifactReference> find(String packageId, String version);

    record ArtifactReference(String packageId, String version, String sha256) {
        public ArtifactReference {
            if (packageId == null || packageId.isBlank() || version == null || version.isBlank()
                    || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid Agent Package artifact reference");
            }
        }
    }

    record StoredArtifact(String sha256, byte[] archive) {
        public StoredArtifact {
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || archive == null) {
                throw new IllegalArgumentException("Artifact digest and bytes are required");
            }
            archive = archive.clone();
        }
        @Override public byte[] archive() { return archive.clone(); }
    }
}
