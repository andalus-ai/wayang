package tech.kayys.wayang.endpoints;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * Connector {@code sql}: run the configured query with the caller's parameters. Config: {@code url}, {@code user}, {@code password}, {@code query},
 * {@code mode} (read, the default, or write), {@code maxRows}, {@code queryTimeoutSeconds}. Input: {@code params} (a list of values for the
 * {@code ?} placeholders). Output for a read: {@code rows}, {@code count}, {@code truncated}; for a write: {@code updated}. See {@link Jdbc}.
 */
final class SqlConnector implements ServiceHandler.Connector {

    private final Jdbc jdbc;

    SqlConnector(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ConnectorInput> inputs() {
        return List.of(ConnectorInput.optional("params", "array", "Values for the ? placeholders of the query, in order"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ConnectorException("sql needs node configuration (url, query)", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception {
        String mode = config.get("mode") == null ? "read" : String.valueOf(config.get("mode")).trim().toLowerCase(java.util.Locale.ROOT);
        if (!mode.equals("read") && !mode.equals("write")) {
            throw new ConnectorException("mode must be read or write", false);
        }
        boolean write = mode.equals("write");
        jdbc.check(config);
        String sql = Jdbc.query(config, write);
        int max = Jdbc.rows(config, Jdbc.MAX_ROWS);
        int timeout = Jdbc.timeout(config);
        Object p = in.get("params");
        List<?> params = p == null ? List.of() : p instanceof List<?> l ? l : List.of(p);
        if (params.size() > 100) {
            throw new ConnectorException("at most 100 parameters", false);
        }
        long placeholders = sql.chars().filter(c -> c == '?').count();
        if (placeholders != params.size()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(write ? "updated" : "rows", write ? 0 : List.of());
            out.put("count", 0);
            out.put("reason", "the query has " + placeholders + " placeholders but " + params.size() + " parameters were given");
            return out; // a mandatory input is missing: warn, do not fail the run
        }
        try (Connection c = jdbc.open(config, !write); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(timeout);
            Jdbc.bind(ps, params);
            Map<String, Object> out = new LinkedHashMap<>();
            if (write) {
                out.put("updated", ps.executeUpdate());
                return out;
            }
            ps.setMaxRows(max + 1);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = Jdbc.read(rs, max + 1);
                boolean truncated = rows.size() > max;
                out.put("rows", truncated ? rows.subList(0, max) : rows);
                out.put("count", Math.min(rows.size(), max));
                out.put("truncated", truncated);
                return out;
            }
        } catch (SQLException e) {
            throw Jdbc.fail("the query failed", e);
        }
    }
}
