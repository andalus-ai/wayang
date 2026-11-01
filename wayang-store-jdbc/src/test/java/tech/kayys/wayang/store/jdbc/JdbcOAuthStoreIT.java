package tech.kayys.wayang.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.api.testkit.OAuthStoreContract;

/** Runs {@link OAuthStoreContract} against a real database; skipped unless WAYANG_TEST_JDBC_URL is set. */
class JdbcOAuthStoreIT {

    @Test
    void satisfiesTheOAuthStoreContract() {
        String url = System.getenv("WAYANG_TEST_JDBC_URL");
        if (url == null || url.isBlank()) {
            return;
        }
        var ds = new JdbcWaitRegistryIT.DriverManagerDataSource(url);
        JdbcSchema.apply(ds);
        OAuthStoreContract.verify(() -> {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DELETE FROM wayang_oauth_state");
                st.execute("DELETE FROM wayang_oauth_connection");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return new JdbcOAuthStore(ds);
        });
    }
}
