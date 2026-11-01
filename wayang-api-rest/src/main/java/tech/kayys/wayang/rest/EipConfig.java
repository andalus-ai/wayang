package tech.kayys.wayang.rest;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.eip.AggregationStore;
import tech.kayys.wayang.eip.ClaimStore;
import tech.kayys.wayang.eip.IdempotencyStore;
import tech.kayys.wayang.store.jdbc.JdbcEipStores;

/**
 * Stores behind the EIP steps. With {@code wayang.store=jdbc} claim checks, aggregation groups and the idempotent receiver are
 * durable and shared between replicas (tables {@code wayang_eip_*}); otherwise they are per-process memory and forget on restart.
 * Circuit breakers, throttles and round-robin counters are always per process.
 *
 * <pre>
 * wayang.eip.idempotency-retention-days=30     # how long an idempotent key is remembered (jdbc)
 * wayang.eip.collect-retention-days=30         # unfinished collect groups older than this are dropped (jdbc)
 * </pre>
 */
@ApplicationScoped
public class EipConfig {

    @ConfigProperty(name = "wayang.store", defaultValue = "memory")
    String storeKind;
    @ConfigProperty(name = "wayang.eip.idempotency-retention-days", defaultValue = "30")
    int retentionDays;

    @ConfigProperty(name = "wayang.eip.collect-retention-days", defaultValue = "30")
    int collectRetentionDays;

    @Inject
    WayangBeans beans;

    private volatile IdempotencyStore idempotency;
    private volatile ClaimStore claims;
    private volatile AggregationStore aggregation;

    public synchronized IdempotencyStore idempotency() {
        if (idempotency == null) {
            idempotency = "jdbc".equals(storeKind)
                    ? new JdbcEipStores.Idempotency(beans.jdbc(), Duration.ofDays(Math.max(1, retentionDays)))
                    : new IdempotencyStore.InMemory(100_000);
        }
        return idempotency;
    }

    public synchronized ClaimStore claims() {
        if (claims == null) {
            claims = "jdbc".equals(storeKind) ? new JdbcEipStores.Claims(beans.jdbc()) : new ClaimStore.InMemory(10_000);
        }
        return claims;
    }

    public synchronized AggregationStore aggregation() {
        if (aggregation == null) {
            aggregation = "jdbc".equals(storeKind) ? new JdbcEipStores.Aggregation(beans.jdbc(), Duration.ofDays(Math.max(1, collectRetentionDays))) : new AggregationStore.InMemory(10_000);
        }
        return aggregation;
    }

    private volatile tech.kayys.wayang.agent.ConversationMemory memory;

    /** What agents with a {@code memory} option remember: shared in the database with {@code wayang.store=jdbc}. */
    public synchronized tech.kayys.wayang.agent.ConversationMemory memory() {
        if (memory == null) {
            memory = "jdbc".equals(storeKind)
                    ? new tech.kayys.wayang.store.jdbc.JdbcConversationMemory(beans.jdbc(), Duration.ofDays(Math.max(1, retentionDays)))
                    : new tech.kayys.wayang.agent.ConversationMemory.InMemory(10_000);
        }
        return memory;
    }
}
