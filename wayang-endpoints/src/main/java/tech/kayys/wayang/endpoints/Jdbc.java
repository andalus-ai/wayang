package tech.kayys.wayang.endpoints;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * Shared JDBC access for the {@code sql} connector and the {@code jdbc} trigger source. The deployment supplies the driver on the class path; Wayang
 * adds no database library. Rules that make it safe to expose to workflows and agents:
 *
 * <ul>
 *   <li>Off until the operator lists the permitted databases in {@code WAYANG_ENDPOINTS_JDBC_URLS} (comma-separated URL prefixes).
 *   <li>The SQL text is node configuration written by the author; callers (and models, when it is an agent tool) pass only <em>values</em>, bound as
 *       parameters. There is never string concatenation.
 *   <li>Read-only by default: one statement that starts with SELECT or WITH, on a read-only connection. Writing needs {@code mode: write}.
 *   <li>Credentials are never in the URL; they are the {@code user} and {@code password} config values ({@code ${secret:NAME}}).
 *   <li>Bounded: rows (default 1000, max 10000), cell text (64 KiB), time (default 30 s, max 300 s). Errors report the SQLState, never the driver's message.
 * </ul>
 */
final class Jdbc {

    static final String ENV = "WAYANG_ENDPOINTS_JDBC_URLS";
    static final int DEFAULT_ROWS = 1000;
    static final int MAX_ROWS = 10_000;
    private static final int MAX_CELL = 64 * 1024;
    private static final Pattern READ = Pattern.compile("(?is)^\\s*(select|with)\\b.*");
    private static final Pattern URL_SECRET = Pattern.compile("(?i)(password|pwd|secret|token)\\s*=");

    private final List<String> allowed;

    Jdbc(List<String> allowedPrefixes) {
        this.allowed = allowedPrefixes;
    }

    static Jdbc fromEnvironment() {
        String v = System.getenv(ENV);
        List<String> l = new ArrayList<>();
        if (v != null) {
            for (String s : v.split(",")) {
                if (!s.isBlank()) {
                    l.add(s.trim());
                }
            }
        }
        return new Jdbc(l);
    }

    /** Fails closed before anything else happens: the operator permits the database, and the url holds no credentials. */
    void check(Map<String, Object> config) throws ConnectorException {
        if (allowed.isEmpty()) {
            throw new ConnectorException("SQL endpoints are off: the operator must set " + ENV + " to the permitted jdbc: URL prefixes", false);
        }
        if (!(config.get("url") instanceof String url) || !url.startsWith("jdbc:")) {
            throw new ConnectorException("url must be a jdbc: URL", false);
        }
        if (allowed.stream().noneMatch(url::startsWith)) {
            throw new ConnectorException("this database is not permitted by " + ENV, false);
        }
        if (URL_SECRET.matcher(url).find()) {
            throw new ConnectorException("put credentials in the user and password settings (as secrets), not in the url", false);
        }
    }

    /** Opens a connection to a permitted database. */
    Connection open(Map<String, Object> config, boolean readOnly) throws ConnectorException {
        check(config);
        String url = (String) config.get("url");
        java.util.Properties props = new java.util.Properties();
        if (config.get("user") instanceof String u && !u.isBlank()) {
            props.setProperty("user", u);
        }
        if (config.get("password") instanceof String p && !p.isEmpty()) {
            props.setProperty("password", p);
        }
        try {
            DriverManager.setLoginTimeout(10);
            Connection c = DriverManager.getConnection(url, props);
            c.setReadOnly(readOnly);
            if (!readOnly) {
                c.setAutoCommit(true);
            }
            return c;
        } catch (SQLException e) {
            throw fail("could not connect to the database", e);
        }
    }

    static String query(Map<String, Object> config, boolean write) throws ConnectorException {
        if (!(config.get("query") instanceof String q) || q.isBlank()) {
            throw new ConnectorException("query is needed (SQL with ? placeholders)", false);
        }
        String sql = q.trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.contains(";") || sql.length() > 20_000) {
            throw new ConnectorException("query must be a single statement", false);
        }
        if (!write && !READ.matcher(sql).matches()) {
            throw new ConnectorException("a read-only query must start with SELECT or WITH (set mode to write to change data)", false);
        }
        return sql;
    }

    static int rows(Map<String, Object> config, int cap) throws ConnectorException {
        Object v = config.get("maxRows");
        if (v == null || v instanceof String s && s.isBlank()) {
            return Math.min(DEFAULT_ROWS, cap);
        }
        try {
            int n = v instanceof Number x ? x.intValue() : Integer.parseInt(String.valueOf(v).trim());
            if (n < 1 || n > cap) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new ConnectorException("maxRows must be a whole number from 1 to " + cap, false);
        }
    }

    static int timeout(Map<String, Object> config) throws ConnectorException {
        Object v = config.get("queryTimeoutSeconds");
        if (v == null || v instanceof String s && s.isBlank()) {
            return 30;
        }
        try {
            int n = v instanceof Number x ? x.intValue() : Integer.parseInt(String.valueOf(v).trim());
            if (n < 1 || n > 300) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new ConnectorException("queryTimeoutSeconds must be a whole number from 1 to 300", false);
        }
    }

    /** Binds the caller's values as parameters; only scalars are accepted. */
    static void bind(PreparedStatement ps, List<?> params) throws SQLException, ConnectorException {
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v == null) {
                ps.setObject(i + 1, null);
            } else if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                ps.setObject(i + 1, v);
            } else {
                throw new ConnectorException("parameter " + (i + 1) + " must be text, a number, a boolean or null", false);
            }
        }
    }

    /** Rows as flat maps; values become JSON-safe (time as ISO text, bytes as base64, long text cut). */
    static List<Map<String, Object>> read(ResultSet rs, int max) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            String label = md.getColumnLabel(i + 1);
            names[i] = label == null || label.isBlank() ? "col" + (i + 1) : label.toLowerCase(Locale.ROOT);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        while (out.size() < max && rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                row.put(names[i], value(rs, i + 1, md.getColumnType(i + 1)));
            }
            out.add(row);
        }
        return out;
    }

    private static Object value(ResultSet rs, int col, int type) throws SQLException {
        switch (type) {
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                java.sql.Timestamp t = rs.getTimestamp(col);
                return t == null ? null : t.toInstant().toString();
            }
            case Types.DATE -> {
                java.sql.Date d = rs.getDate(col);
                return d == null ? null : d.toString();
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                byte[] b = rs.getBytes(col);
                return b == null ? null : b.length > MAX_CELL ? "(binary, " + b.length + " bytes)" : Base64.getEncoder().encodeToString(b);
            }
            default -> {
                Object o = rs.getObject(col);
                if (o == null || o instanceof Number || o instanceof Boolean) {
                    return o;
                }
                String s = o instanceof java.sql.Clob c ? c.getSubString(1, MAX_CELL) : String.valueOf(o);
                return s.length() > MAX_CELL ? s.substring(0, MAX_CELL) : s;
            }
        }
    }

    /** Never includes the driver's message (it can carry host names, user names or values). */
    static ConnectorException fail(String what, SQLException e) {
        String state = e.getSQLState();
        boolean retry = state != null && (state.startsWith("08") || state.startsWith("40") || state.startsWith("53") || state.startsWith("57"));
        return new ConnectorException(what + (state == null || state.isBlank() ? "" : " (SQLState " + state.replaceAll("[^A-Za-z0-9]", "") + ")"), retry);
    }

    static String iso(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }
}
