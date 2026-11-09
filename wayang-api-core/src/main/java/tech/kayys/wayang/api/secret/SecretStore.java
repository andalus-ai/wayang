package tech.kayys.wayang.api;

import java.util.List;
import java.util.Optional;

/** Persistence of sealed secrets. Never sees plaintext. */
public interface SecretStore {

    Optional<SealedSecret> get(String tenant, String name);

    /** Metadata order: by name. */
    List<SealedSecret> list(String tenant);

    /**
     * Compare-and-set write. {@code expectedVersion} 0 means "must not exist"; otherwise the stored version must
     * equal it. @return false on mismatch.
     */
    boolean put(SealedSecret secret, int expectedVersion);

    /** Replaces only the ciphertext/keyId of the same version (key rotation); false if version changed or gone. */
    boolean reseal(SealedSecret secret);

    boolean delete(String tenant, String name);
}
