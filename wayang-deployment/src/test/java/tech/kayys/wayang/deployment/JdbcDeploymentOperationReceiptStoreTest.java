package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcDeploymentOperationReceiptStoreTest {
    private JdbcDeploymentOperationReceiptStore store;
    private final Instant now = Instant.parse("2026-11-14T00:00:00Z");

    @BeforeEach void setUp() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:operation_receipts;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (var c = source.getConnection(); var s = c.createStatement()) {
            s.execute("""
                CREATE TABLE wayang_deployment_snapshot (
                  deployment_id VARCHAR(160) PRIMARY KEY, tenant_id VARCHAR(160) NOT NULL,
                  environment VARCHAR(120) NOT NULL, target_id VARCHAR(200) NOT NULL,
                  release_id VARCHAR(200) NOT NULL, state VARCHAR(32) NOT NULL,
                  version BIGINT NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, last_reason VARCHAR(2048))
                """);
            s.execute("""
                CREATE TABLE wayang_deployment_operation_receipt (
                  deployment_id VARCHAR(160) NOT NULL REFERENCES wayang_deployment_snapshot(deployment_id),
                  operation_key VARCHAR(200) NOT NULL, command_fingerprint CHAR(64) NOT NULL,
                  operation VARCHAR(40) NOT NULL, status VARCHAR(16) NOT NULL, attempt INTEGER NOT NULL,
                  target_operation_key VARCHAR(200) NOT NULL, result_code VARCHAR(120), result_detail VARCHAR(2048),
                  lease_until TIMESTAMP WITH TIME ZONE, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  updated_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (deployment_id, operation_key))
                """);
            s.execute("INSERT INTO wayang_deployment_snapshot VALUES ('dep','tenant','prod','target','release','INSTALLED',0,CURRENT_TIMESTAMP,'installed')");
        }
        store = new JdbcDeploymentOperationReceiptStore(source);
    }

    @Test void duplicateClaimReturnsSameRunningReceiptWithoutSecondAttempt() {
        var first = store.claim("dep", "key", "fingerprint", "ACTIVATE", "target-key", now, now.plusSeconds(30));
        var second = store.claim("dep", "key", "fingerprint", "ACTIVATE", "target-key", now.plusSeconds(1), now.plusSeconds(31));
        assertEquals(DeploymentOperationStatus.RUNNING, second.status());
        assertEquals(first.attempt(), second.attempt());
        assertEquals(first.createdAt(), second.createdAt());
    }

    @Test void sameIdempotencyKeyWithDifferentFingerprintIsRejected() {
        store.claim("dep", "key", "fingerprint-a", "ACTIVATE", "target-key", now, now.plusSeconds(30));
        assertThrows(SecurityException.class, () -> store.claim("dep", "key", "fingerprint-b", "ACTIVATE",
                "target-key", now, now.plusSeconds(30)));
    }

    @Test void expiredRunningOperationBecomesUnknownAndIsNotReexecuted() {
        store.claim("dep", "key", "fingerprint", "ACTIVATE", "target-key", now, now.plusSeconds(10));
        var recovered = store.claim("dep", "key", "fingerprint", "ACTIVATE", "target-key",
                now.plusSeconds(11), now.plusSeconds(41));
        assertEquals(DeploymentOperationStatus.UNKNOWN, recovered.status());
        assertEquals(0, store.findExpiredRunning(now.plusSeconds(20), 100).size());
        var replay = store.claim("dep", "key", "fingerprint", "ACTIVATE",
                "target-key", now.plusSeconds(20), now.plusSeconds(50));
        assertEquals(DeploymentOperationStatus.UNKNOWN, replay.status());
    }

    @Test void terminalResultIsReplayableAndCannotBeOverwritten() {
        store.claim("dep", "key", "fingerprint", "ACTIVATE", "target-key", now, now.plusSeconds(30));
        var done = store.complete("dep", "key", "fingerprint", DeploymentOperationStatus.SUCCEEDED,
                "ACTIVE", "ready", now.plusSeconds(2));
        assertEquals(DeploymentOperationStatus.SUCCEEDED, done.status());
        assertEquals("ACTIVE", store.find("dep", "key").orElseThrow().resultCode());
        assertThrows(IllegalStateException.class, () -> store.complete("dep", "key", "fingerprint",
                DeploymentOperationStatus.FAILED, "ERROR", "late overwrite", now.plusSeconds(3)));
    }
}
