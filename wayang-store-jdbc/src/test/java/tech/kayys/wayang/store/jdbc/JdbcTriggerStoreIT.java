package tech.kayys.wayang.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.api.testkit.TriggerStoreContract;

/** Runs {@link TriggerStoreContract} against a real database; skipped unless WAYANG_TEST_JDBC_URL is set. */
class JdbcTriggerStoreIT {

    @Test
    void satisfiesTheTriggerStoreContract() {
        String url = System.getenv("WAYANG_TEST_JDBC_URL");
        if (url == null || url.isBlank()) {
            return;
        }
        var ds = new JdbcWaitRegistryIT.DriverManagerDataSource(url);
        JdbcSchema.apply(ds);
        TriggerStoreContract.verify(() -> {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DELETE FROM wayang_trigger");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return new JdbcTriggerStore(ds);
        });
    }
}
