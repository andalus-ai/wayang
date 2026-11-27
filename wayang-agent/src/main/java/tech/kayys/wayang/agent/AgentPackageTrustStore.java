package tech.kayys.wayang.agent;

import java.security.PublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Publisher-key trust configuration supplied by an operator, never by the package being verified. */
public interface AgentPackageTrustStore {

    Optional<TrustedPublisherKey> find(String publisherId, String keyId);

    record TrustedPublisherKey(String publisherId, String keyId, PublicKey publicKey,
            List<String> packageIdPrefixes, Instant notBefore, Instant notAfter, boolean revoked) {
        public TrustedPublisherKey {
            requireText(publisherId, "publisherId");
            requireText(keyId, "keyId");
            Objects.requireNonNull(publicKey, "publicKey");
            if (!"EdDSA".equalsIgnoreCase(publicKey.getAlgorithm())
                    && !"Ed25519".equalsIgnoreCase(publicKey.getAlgorithm())) {
                throw new IllegalArgumentException("Publisher key must use Ed25519");
            }
            packageIdPrefixes = List.copyOf(packageIdPrefixes == null ? List.of() : packageIdPrefixes);
            if (packageIdPrefixes.isEmpty() || packageIdPrefixes.stream().anyMatch(p -> p == null || !p.matches("[a-z][a-z0-9.-]*:"))) {
                throw new IllegalArgumentException("At least one valid package ID namespace prefix is required");
            }
            Objects.requireNonNull(notBefore, "notBefore");
            Objects.requireNonNull(notAfter, "notAfter");
            if (!notAfter.isAfter(notBefore)) throw new IllegalArgumentException("Publisher key validity interval is invalid");
        }

        public boolean permits(String packageId) {
            return packageIdPrefixes.stream().anyMatch(packageId::startsWith);
        }

        public boolean validAt(Instant instant) {
            return !revoked && !instant.isBefore(notBefore) && instant.isBefore(notAfter);
        }
    }

    /** Small in-memory implementation for tests, embedded deployments and local trust configuration. */
    final class InMemory implements AgentPackageTrustStore {
        private final ConcurrentHashMap<String, TrustedPublisherKey> keys = new ConcurrentHashMap<>();

        public void trust(TrustedPublisherKey key) {
            Objects.requireNonNull(key, "key");
            keys.put(coordinate(key.publisherId(), key.keyId()), key);
        }

        public void revoke(String publisherId, String keyId) {
            keys.compute(coordinate(publisherId, keyId), (ignored, current) -> {
                if (current == null) return null;
                return new TrustedPublisherKey(current.publisherId(), current.keyId(), current.publicKey(),
                        current.packageIdPrefixes(), current.notBefore(), current.notAfter(), true);
            });
        }

        @Override
        public Optional<TrustedPublisherKey> find(String publisherId, String keyId) {
            return Optional.ofNullable(keys.get(coordinate(publisherId, keyId)));
        }

        private static String coordinate(String publisherId, String keyId) {
            return publisherId + "\n" + keyId;
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " is required and must be at most 256 characters");
        }
    }
}
