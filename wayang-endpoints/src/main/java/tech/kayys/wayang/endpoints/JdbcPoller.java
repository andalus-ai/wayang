package tech.kayys.wayang.endpoints;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * Trigger source {@code jdbc}: a run per new row. Config: {@code url}, {@code user}, {@code password}, {@code query} (read-only, exactly one
 * {@code ?}: the cursor, for example {@code SELECT id, total FROM orders WHERE id > ? ORDER BY id}), {@code cursorColumn} (a column of the result that
 * only grows: an id or an updated-at timestamp), {@code initialCursor} (where to start; needed, so a trigger never silently replays a whole table), and
 * {@code maxRows} (1-100). The cursor is the last row's {@code cursorColumn} as text; it is bound as a number when it looks like one, otherwise as
 * text (ISO timestamps work for most databases when the column is a timestamp, check with yours). At-least-once, in query order.
 */
final class JdbcPoller implements Poller {

    private final Jdbc jdbc;

    JdbcPoller(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Result poll(Map<String, Object> config, String cursor) throws Exception {
        jdbc.check(config);
        String sql = Jdbc.query(config, false);
        if (sql.chars().filter(c -> c == '?').count() != 1) {
            throw new ConnectorException("a jdbc trigger query needs exactly one ? (the cursor)", false);
        }
        if (!(config.get("cursorColumn") instanceof String col) || col.isBlank()) {
            throw new ConnectorException("cursorColumn is needed: a column that only grows", false);
        }
        String start = cursor != null ? cursor : config.get("initialCursor") == null ? null : String.valueOf(config.get("initialCursor"));
        if (start == null || start.isBlank()) {
            throw new ConnectorException("initialCursor is needed (for example 0 for an id column), so the trigger never replays a whole table", false);
        }
        int max = Jdbc.rows(config, 100);
        try (Connection c = jdbc.open(config, true); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(Jdbc.timeout(config));
            ps.setMaxRows(max);
            Jdbc.bind(ps, List.of(number(start)));
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = Jdbc.read(rs, max);
                if (rows.isEmpty()) {
                    return new Result(List.of(), null);
                }
                Object last = rows.get(rows.size() - 1).get(col.trim().toLowerCase(java.util.Locale.ROOT));
                if (last == null) {
                    throw new ConnectorException("cursorColumn '" + col.trim() + "' is not a column of the result, or is null", false);
                }
                return new Result(rows, String.valueOf(last));
            }
        } catch (SQLException e) {
            throw Jdbc.fail("polling the database failed", e);
        }
    }

    private static Object number(String s) {
        String t = s.trim();
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return t;
        }
    }
}
