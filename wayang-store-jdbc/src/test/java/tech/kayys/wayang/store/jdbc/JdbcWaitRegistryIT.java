package tech.kayys.wayang.store.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.testkit.WaitRegistryContract;

/**
 * Runs the shared {@link WaitRegistryContract} against a real database.
 * Skipped (returns immediately) unless {@code WAYANG_TEST_JDBC_URL} is set, e.g.
 * {@code jdbc:postgresql://localhost/wayang?user=..&password=..}; add the
 * driver as a test dependency. The tables are wiped before each contract case.
 *
 * <p>
 * This has not been executed yet (no JDBC driver or database was available
 * where this was written).
 */
class JdbcWaitRegistryIT {

    @Test
    void satisfiesTheWaitRegistryContract() throws Exception {
        String url = System.getenv("WAYANG_TEST_JDBC_URL");
        if (url == null || url.isBlank()) {
            return;
        }
        DataSource ds = new DriverManagerDataSource(url);
        String ddl = new String(JdbcWaitRegistryIT.class.getResourceAsStream("/wayang/schema-wait-registry.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String stmt : ddl.split(";")) {
                if (!stmt.replaceAll("--[^\n]*", "").isBlank()) {
                    st.execute(stmt);
                }
            }
        }
        WaitRegistryContract.verify(() -> {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DELETE FROM wayang_wait");
                st.execute("DELETE FROM wayang_buffered_message");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return new JdbcWaitRegistry(ds);
        });
    }

    record DriverManagerDataSource(String url) implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String u, String p) throws SQLException {
            return DriverManager.getConnection(url, u, p);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
