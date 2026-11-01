package tech.kayys.wayang.store.jdbc;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.eip.testkit.EipStoreContracts;

/** Runs the EIP store contracts against a real database; skipped unless WAYANG_TEST_JDBC_URL is set. */
class JdbcEipStoresIT {

    private static javax.sql.DataSource ds() {
        String url = System.getenv("WAYANG_TEST_JDBC_URL");
        if (url == null || url.isBlank()) {
            return null;
        }
        var ds = new JdbcWaitRegistryIT.DriverManagerDataSource(url);
        JdbcSchema.apply(ds);
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("DELETE FROM wayang_eip_claim");
            st.execute("DELETE FROM wayang_eip_agg");
            st.execute("DELETE FROM wayang_eip_seen");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return ds;
    }

    @Test
    void claims() {
        var ds = ds();
        if (ds != null) {
            EipStoreContracts.claims(() -> new JdbcEipStores.Claims(ds));
        }
    }

    @Test
    void aggregation() throws Exception {
        var ds = ds();
        if (ds != null) {
            EipStoreContracts.aggregation(() -> new JdbcEipStores.Aggregation(ds));
            EipStoreContracts.aggregationRace(() -> new JdbcEipStores.Aggregation(ds));
        }
    }

    @Test
    void aggregationDropsAbandonedGroupsOnItsOwn() {
        var ds = ds();
        if (ds == null) {
            return;
        }
        var store = new JdbcEipStores.Aggregation(ds, java.time.Duration.ofSeconds(1));
        store.append("purge-ns", "abandoned", "{}", 0, 10);
        store.append("purge-ns", "abandoned", "{}", 1, 10);
        for (int i = 0; i < 40; i++) {
            store.append("purge-ns", "k" + i, "{}", 100_000, 10);
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, store.append("purge-ns", "abandoned", "{}", 100_000, 10).size(),
                "the old group was dropped by the periodic purge");
        org.junit.jupiter.api.Assertions.assertEquals(2, store.append("purge-ns", "k0", "{}", 100_000, 10).size(), "recent groups stay");
    }

    @Test
    void idempotency() {
        var ds = ds();
        if (ds != null) {
            EipStoreContracts.idempotency(() -> new JdbcEipStores.Idempotency(ds, java.time.Duration.ofDays(30)));
        }
    }

    @Test
    void idempotencyPurgesOldKeysAndKeepsRecentOnes() throws Exception {
        var ds = ds();
        if (ds == null) {
            return;
        }
        var store = new JdbcEipStores.Idempotency(ds, java.time.Duration.ofMillis(1));
        for (int i = 0; i < 499; i++) {
            store.markIfNew("old-" + i);
        }
        Thread.sleep(20);
        store.markIfNew("trigger-purge"); // the 500th insert purges everything older than the retention
        if (!store.markIfNew("old-0")) {
            throw new AssertionError("an expired key should be forgotten and accepted again");
        }
    }

    @Test
    void conversationMemory() {
        var ds = ds();
        if (ds != null) {
            tech.kayys.wayang.agent.testkit.MemoryContracts.memory(() -> new JdbcConversationMemory(ds, java.time.Duration.ofDays(30)));
        }
    }
}
