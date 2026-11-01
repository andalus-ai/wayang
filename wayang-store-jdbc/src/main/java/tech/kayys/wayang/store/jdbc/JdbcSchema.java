package tech.kayys.wayang.store.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

/** Creates the Wayang tables if missing (the scripts are idempotent: {@code CREATE ... IF NOT EXISTS}). */
public final class JdbcSchema {

    private static final List<String> SCRIPTS = List.of("/wayang/schema-wait-registry.sql", "/wayang/schema-definitions.sql");

    private JdbcSchema() {
    }

    public static void apply(DataSource ds) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String script : SCRIPTS) {
                String ddl;
                try (InputStream in = JdbcSchema.class.getResourceAsStream(script)) {
                    if (in == null) {
                        throw new IllegalStateException("Missing resource " + script);
                    }
                    ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                for (String stmt : ddl.replaceAll("--[^\n]*", "").split(";")) {
                    if (!stmt.isBlank()) {
                        st.execute(stmt);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create Wayang schema: " + e.getMessage(), e);
        }
    }
}
