package tech.kayys.wayang.endpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/** The JDBC steps against a fake driver (no database library is needed or added): what is sent, bound, bounded and hidden. */
class SqlTest {

    /** What the fake database saw and what it answers. */
    static final class Db {
        final List<String> sql = new ArrayList<>();
        final List<List<Object>> params = new ArrayList<>();
        final List<Boolean> readOnly = new ArrayList<>();
        final List<Properties> connects = new ArrayList<>();
        List<Object[]> rows = List.of();
        String[] columns = {"id"};
        int[] types = {Types.BIGINT};
        int updateCount = 1;
        SQLException failOnConnect;
    }

    private final Db db = new Db();
    private Driver driver;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(SqlTest.class.getClassLoader(), new Class<?>[] {type}, h);
    }

    @BeforeEach
    void register() throws Exception {
        driver = new Driver() {
            @Override public Connection connect(String url, Properties info) throws SQLException {
                if (!url.startsWith("jdbc:fake:")) {
                    return null;
                }
                if (db.failOnConnect != null) {
                    throw db.failOnConnect;
                }
                db.connects.add(info);
                return proxy(Connection.class, (p, m, a) -> switch (m.getName()) {
                    case "setReadOnly" -> { db.readOnly.add((Boolean) a[0]); yield null; }
                    case "prepareStatement" -> { db.sql.add((String) a[0]); db.params.add(new ArrayList<>()); yield statement(); }
                    case "close", "setAutoCommit" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
            }
            @Override public boolean acceptsURL(String url) { return url.startsWith("jdbc:fake:"); }
            @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
            @Override public int getMajorVersion() { return 1; }
            @Override public int getMinorVersion() { return 0; }
            @Override public boolean jdbcCompliant() { return false; }
            @Override public Logger getParentLogger() { return Logger.getAnonymousLogger(); }
        };
        DriverManager.registerDriver(driver);
    }

    @AfterEach
    void unregister() throws Exception {
        DriverManager.deregisterDriver(driver);
    }

    private PreparedStatement statement() {
        return proxy(PreparedStatement.class, (p, m, a) -> switch (m.getName()) {
            case "setObject" -> { db.params.get(db.params.size() - 1).add(a[1]); yield null; }
            case "setQueryTimeout", "setMaxRows", "close" -> null;
            case "executeUpdate" -> db.updateCount;
            case "executeQuery" -> resultSet();
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private ResultSet resultSet() {
        int[] pos = {-1};
        ResultSetMetaData md = proxy(ResultSetMetaData.class, (p, m, a) -> switch (m.getName()) {
            case "getColumnCount" -> db.columns.length;
            case "getColumnLabel" -> db.columns[(Integer) a[0] - 1];
            case "getColumnType" -> db.types[(Integer) a[0] - 1];
            default -> throw new UnsupportedOperationException(m.getName());
        });
        return proxy(ResultSet.class, (p, m, a) -> switch (m.getName()) {
            case "getMetaData" -> md;
            case "next" -> ++pos[0] < db.rows.size();
            case "getObject" -> db.rows.get(pos[0])[(Integer) a[0] - 1];
            case "getTimestamp" -> (java.sql.Timestamp) db.rows.get(pos[0])[(Integer) a[0] - 1];
            case "getBytes" -> (byte[]) db.rows.get(pos[0])[(Integer) a[0] - 1];
            case "close" -> null;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private SqlConnector sql() {
        return new SqlConnector(new Jdbc(List.of("jdbc:fake:")));
    }

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("url", "jdbc:fake:orders", "query", "SELECT id FROM orders WHERE total > ?"));
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void offUntilTheOperatorPermitsDatabasesAndOnlyThose() throws Exception {
        ConnectorException off = assertThrows(ConnectorException.class, () -> new SqlConnector(new Jdbc(List.of())).call(cfg(), Map.of()));
        assertTrue(off.getMessage().contains(Jdbc.ENV));
        assertThrows(ConnectorException.class, () -> sql().call(cfg("url", "jdbc:other:db"), Map.of()));
        assertThrows(ConnectorException.class, () -> sql().call(cfg("url", "http://x"), Map.of()));
        ConnectorException cred = assertThrows(ConnectorException.class, () -> sql().call(cfg("url", "jdbc:fake:db?user=a&password=hunter2"), Map.of()));
        assertFalse(cred.getMessage().contains("hunter2"));
        assertTrue(db.connects.isEmpty(), "nothing was opened");
    }

    @Test
    void readsBindValuesAndNeverConcatenates() throws Exception {
        db.rows = List.<Object[]>of(new Object[] {1L}, new Object[] {2L});
        Map<String, Object> out = sql().call(cfg("user", "svc", "password", "pw"), Map.of("params", List.of("5; DROP TABLE orders")));
        assertEquals(2, out.get("count"));
        assertEquals(false, out.get("truncated"));
        assertEquals(List.of("SELECT id FROM orders WHERE total > ?"), db.sql);
        assertEquals(List.of(List.of("5; DROP TABLE orders")), db.params, "the value is a parameter, not SQL");
        assertEquals(List.of(true), db.readOnly);
        assertEquals("svc", db.connects.get(0).getProperty("user"));
    }

    @Test
    void readOnlyMeansOneSelect() {
        for (String bad : new String[] {"DELETE FROM orders", "UPDATE orders SET a = 1", "SELECT 1; DROP TABLE orders", "INSERT INTO t VALUES (?)", "  call x()"}) {
            assertThrows(ConnectorException.class, () -> sql().call(cfg("query", bad), Map.of("params", List.of())), bad);
        }
        assertTrue(db.sql.isEmpty());
    }

    @Test
    void writeModeIsExplicitAndReportsTheCount() throws Exception {
        db.updateCount = 3;
        Map<String, Object> out = sql().call(cfg("mode", "write", "query", "UPDATE orders SET seen = ? WHERE id < ?"), Map.of("params", List.of(true, 10)));
        assertEquals(3, out.get("updated"));
        assertEquals(List.of(false), db.readOnly);
        assertThrows(ConnectorException.class, () -> sql().call(cfg("mode", "admin"), Map.of("params", List.of(1))));
    }

    @Test
    void parameterMistakesGiveAReasonNotACrashAndOnlyScalarsAreBound() throws Exception {
        Map<String, Object> out = sql().call(cfg(), Map.of());
        assertEquals(0, out.get("count"));
        assertTrue(String.valueOf(out.get("reason")).contains("1 placeholders but 0"));
        assertThrows(ConnectorException.class, () -> sql().call(cfg(), Map.of("params", List.of(Map.of("a", 1)))));
        assertThrows(ConnectorException.class, () -> sql().call(cfg(), Map.of("params", java.util.Collections.nCopies(101, 1))));
    }

    @Test
    void rowsAreBoundedAndValuesAreJsonSafe() throws Exception {
        db.columns = new String[] {"ID", "At", "Blob", "Note"};
        db.types = new int[] {Types.BIGINT, Types.TIMESTAMP, Types.VARBINARY, Types.VARCHAR};
        db.rows = List.<Object[]>of(new Object[] {1L, java.sql.Timestamp.from(java.time.Instant.parse("2026-01-02T03:04:05Z")), new byte[] {1, 2, 3}, "x".repeat(70_000)},
                new Object[] {2L, null, null, null}, new Object[] {3L, null, null, null});
        Map<String, Object> out = sql().call(cfg("maxRows", 2), Map.of("params", List.of(1)));
        assertEquals(true, out.get("truncated"));
        assertEquals(2, out.get("count"));
        Map<?, ?> first = (Map<?, ?>) ((List<?>) out.get("rows")).get(0);
        assertEquals("2026-01-02T03:04:05Z", first.get("at"));
        assertEquals("AQID", first.get("blob"));
        assertEquals(64 * 1024, ((String) first.get("note")).length());
        assertTrue(first.containsKey("id"), "column names are lower-case");
        assertThrows(ConnectorException.class, () -> sql().call(cfg("maxRows", 10_001), Map.of("params", List.of(1))));
        assertThrows(ConnectorException.class, () -> sql().call(cfg("queryTimeoutSeconds", 301), Map.of("params", List.of(1))));
    }

    @Test
    void databaseErrorsShowTheSqlStateNeverTheDriversMessage() {
        db.failOnConnect = new SQLException("FATAL: password authentication failed for user svc at 10.0.0.5 with secret hunter2", "08001");
        ConnectorException e = assertThrows(ConnectorException.class, () -> sql().call(cfg("password", "hunter2"), Map.of("params", List.of(1))));
        assertTrue(e.getMessage().contains("08001"));
        assertFalse(e.getMessage().contains("hunter2") || e.getMessage().contains("10.0.0.5"), e.getMessage());
        assertTrue(e.retryable(), "a connection failure may be retried");
        db.failOnConnect = new SQLException("syntax error near x", "42601");
        assertFalse(assertThrows(ConnectorException.class, () -> sql().call(cfg(), Map.of("params", List.of(1)))).retryable());
    }

    @Test
    void theTriggerSourceFollowsACursorInOrder() throws Exception {
        JdbcPoller poller = new JdbcPoller(new Jdbc(List.of("jdbc:fake:")));
        Map<String, Object> c = cfg("query", "SELECT id FROM orders WHERE id > ? ORDER BY id", "cursorColumn", "id", "initialCursor", "0");
        db.rows = List.<Object[]>of(new Object[] {7L}, new Object[] {9L});
        Poller.Result r = poller.poll(c, null);
        assertEquals(2, r.events().size());
        assertEquals("9", r.nextCursor());
        assertEquals(List.of(0L), db.params.get(0), "the initial cursor is bound as a number");
        db.rows = List.of();
        assertEquals(List.of(), poller.poll(c, "9").events());
        assertEquals(List.of(9L), db.params.get(1));
        assertEquals(List.of(true, true), db.readOnly, "a trigger can never write");
    }

    @Test
    void theTriggerSourceRefusesUnsafeConfiguration() {
        JdbcPoller poller = new JdbcPoller(new Jdbc(List.of("jdbc:fake:")));
        String q = "SELECT id FROM orders WHERE id > ?";
        assertThrows(ConnectorException.class, () -> poller.poll(cfg("query", q, "cursorColumn", "id"), null), "initialCursor is required");
        assertThrows(ConnectorException.class, () -> poller.poll(cfg("query", q, "initialCursor", "0"), null), "cursorColumn is required");
        assertThrows(ConnectorException.class, () -> poller.poll(cfg("query", "SELECT id FROM orders", "cursorColumn", "id", "initialCursor", "0"), null));
        assertThrows(ConnectorException.class, () -> poller.poll(cfg("query", "DELETE FROM orders WHERE id > ?", "cursorColumn", "id", "initialCursor", "0"), null));
        assertThrows(ConnectorException.class, () -> poller.poll(cfg("query", q, "cursorColumn", "id", "initialCursor", "0", "maxRows", 101), null));
        db.rows = List.<Object[]>of(new Object[] {1L});
        assertThrows(ConnectorException.class, () -> poller.poll(cfg("query", q, "cursorColumn", "nope", "initialCursor", "0"), null), "cursor column must be in the result");
    }

    @Test
    void theExtensionOffersTheSqlStepAndSource() {
        EndpointsExtension e = new EndpointsExtension(new FsRoot(null), Clock.systemUTC(), new Jdbc(List.of()));
        assertTrue(e.connectors().containsKey("sql"));
        assertTrue(e.pollers().containsKey("jdbc"));
        assertTrue(e.components().stream().anyMatch(c -> c.id().equals("wayang.endpoints.sql")));
    }
}
