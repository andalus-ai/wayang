package tech.kayys.wayang.rest.management;

import java.io.IOException;
import java.util.Optional;

/** Host-owned storage for the Console registration bearer; implementations must never log secrets. */
public interface ManagementCredentialStore {
    Optional<String> load() throws IOException;
    void save(String registrationToken) throws IOException;
    void clear() throws IOException;
}
