package tech.kayys.wayang.api;

import tech.kayys.wayang.api.secret.cloud.CloudException;

/** One cloud secret service: fetches the current value of {@code name} for {@code tenant}, or null when there is none. */
public interface CloudBackend {
    String fetch(String tenant, String name) throws CloudException;
}
