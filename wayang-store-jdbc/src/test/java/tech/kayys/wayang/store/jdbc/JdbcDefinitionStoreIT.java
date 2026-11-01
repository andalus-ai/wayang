package tech.kayys.wayang.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.api.testkit.DefinitionStoreContract;

/**
 * Runs {@link DefinitionStoreContract} against a real database. Skipped unless
 * {@code WAYANG_TEST_JDBC_URL} is set (add the driver to the test classpath).
 * Verified once against PostgreSQL 16.
 */
class JdbcDefinitionStoreIT {

    @Test
    void satisfiesTheDefinitionStoreContract() throws Exception {
        String url = System.getenv("WAYANG_TEST_JDBC_URL");
        if (url == null || url.isBlank()) {
            return;
        }
        var ds = new JdbcWaitRegistryIT.DriverManagerDataSource(url);
        String ddl = new String(JdbcDefinitionStoreIT.class.getResourceAsStream("/wayang/schema-definitions.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String stmt : ddl.split(";")) {
                if (!stmt.replaceAll("--[^\n]*", "").isBlank()) {
                    st.execute(stmt);
                }
            }
        }
        tech.kayys.wayang.api.testkit.ProjectStoreContract.verify(() -> {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DELETE FROM wayang_project");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return new JdbcProjectStore(ds);
        });
        DefinitionStoreContract.verify(() -> {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DELETE FROM wayang_definition_draft");
                st.execute("DELETE FROM wayang_definition_version");
                st.execute("DELETE FROM wayang_project");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return new JdbcDefinitionStore(ds);
        });
    }

}
