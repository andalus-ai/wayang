package tech.kayys.wayang.adapter.gamelan;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import tech.kayys.gamelan.sdk.client.GamelanClient;

/**
 * One Gamelan client per tenant (Gamelan scopes by tenant id), created lazily
 * against a single endpoint. Requires a client transport on the classpath, for
 * example {@code gamelan-sdk-client-remote}.
 */
public final class GamelanClients implements Function<String, GamelanClient>, AutoCloseable {

    private final String endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final Map<String, GamelanClient> clients = new ConcurrentHashMap<>();

    public GamelanClients(String endpoint, String apiKey, Duration timeout) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.timeout = timeout;
    }

    @Override
    public GamelanClient apply(String tenantId) {
        return clients.computeIfAbsent(tenantId, t -> {
            var b = GamelanClient.builder().endpoint(endpoint).tenantId(t).timeout(timeout);
            if (apiKey != null && !apiKey.isBlank()) {
                b.apiKey(apiKey);
            }
            return b.build();
        });
    }

    @Override
    public void close() {
        clients.values().forEach(GamelanClient::close);
        clients.clear();
    }
}
