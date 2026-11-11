package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcDeploymentLedgerTest {
    private JdbcDeploymentLedger ledger;

    @BeforeEach
    void setUp() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:deployment;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ledger = new JdbcDeploymentLedger(source);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS wayang_deployment_snapshot (
                      deployment_id VARCHAR(160) PRIMARY KEY, tenant_id VARCHAR(160) NOT NULL,
                      environment VARCHAR(120) NOT NULL, target_id VARCHAR(200) NOT NULL,
                      release_id VARCHAR(200) NOT NULL, state VARCHAR(32) NOT NULL,
                      version BIGINT NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, last_reason VARCHAR(2048))
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS wayang_deployment_event (
                      deployment_id VARCHAR(160) NOT NULL REFERENCES wayang_deployment_snapshot(deployment_id),
                      sequence BIGINT NOT NULL, from_state VARCHAR(32), to_state VARCHAR(32) NOT NULL,
                      actor VARCHAR(200) NOT NULL, reason VARCHAR(2048) NOT NULL, correlation_id VARCHAR(200) NOT NULL,
                      idempotency_key VARCHAR(200) NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                      PRIMARY KEY (deployment_id, sequence), UNIQUE (deployment_id, idempotency_key))
                    """);
        }
        var now = Instant.parse("2026-11-11T00:00:00Z");
        ledger.register(new DeploymentSnapshot("dep-jdbc", "tenant-a", "prod", "target-a", "release-a",
                        DeploymentState.INSTALLED, 0, now, "installed"),
                new DeploymentEvent("dep-jdbc", 0, null, DeploymentState.INSTALLED,
                        "publisher", "installed", "corr-a", "install-a", now));
    }

    @Test
    void persistsSnapshotAndOrderedEvents() {
        var current = ledger.find("dep-jdbc").orElseThrow();
        var next = new DeploymentSnapshot("dep-jdbc", "tenant-a", "prod", "target-a", "release-a",
                DeploymentState.ACTIVATING, 1, current.updatedAt().plusSeconds(1), "activate");
        var event = new DeploymentEvent("dep-jdbc", 1, DeploymentState.INSTALLED, DeploymentState.ACTIVATING,
                "operator", "activate", "corr-b", "activate-a", next.updatedAt());

        ledger.append(0, next, event);

        assertEquals(DeploymentState.ACTIVATING, ledger.find("dep-jdbc").orElseThrow().state());
        assertEquals(2, ledger.events("dep-jdbc").size());
        assertEquals(1, ledger.events("dep-jdbc").get(1).sequence());
    }

    @Test
    void duplicateIdempotencyKeyRollsBackSnapshotUpdate() {
        var current = ledger.find("dep-jdbc").orElseThrow();
        var duplicate = new DeploymentSnapshot("dep-jdbc", "tenant-a", "prod", "target-a", "release-a",
                DeploymentState.ACTIVATING, 1, current.updatedAt().plusSeconds(1), "duplicate");
        var event = new DeploymentEvent("dep-jdbc", 1, DeploymentState.INSTALLED, DeploymentState.ACTIVATING,
                "operator", "duplicate", "corr-b", "install-a", duplicate.updatedAt());

        assertThrows(RuntimeException.class, () -> ledger.append(0, duplicate, event));
        assertEquals(0, ledger.find("dep-jdbc").orElseThrow().version());
        assertEquals(1, ledger.events("dep-jdbc").size());
    }
}
