package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.SimpleBindings;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.ServiceHandler;

class ScriptConnectorTest {

    /** A stand-in for Detroit / GraalJS: "return-input" answers the bound input, "sleep" blocks, "boom" throws, "object" answers a non-JSON object. */
    static final class FakeEngine extends AbstractScriptEngine {
        final AtomicReference<Bindings> seen = new AtomicReference<>();

        @Override
        public Object eval(String script, ScriptContext context) throws javax.script.ScriptException {
            return run(script, context.getBindings(ScriptContext.ENGINE_SCOPE));
        }

        @Override
        public Object eval(String script, Bindings b) {
            return run(script, b);
        }

        private Object run(String script, Bindings b) {
            seen.set(b);
            switch (script) {
                case "return-input":
                    return b.get("input");
                case "sleep":
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                case "boom":
                    throw new IllegalStateException("kaboom with detail");
                case "object":
                    return new StringBuilder("not json");
                default:
                    return script;
            }
        }

        @Override
        public Object eval(Reader reader, ScriptContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Bindings createBindings() {
            return new SimpleBindings();
        }

        @Override
        public ScriptEngineFactory getFactory() {
            return null;
        }
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static ScriptConnector with(Set<String> langs, FakeEngine e) {
        Function<String, ScriptEngine> lookup = name -> name.equals("javascript") || name.equals("wayang-fake") ? e : null;
        return new ScriptConnector(langs, lookup);
    }

    @Test
    void cesRunsWithInputsAsVariablesAndGivesAJsonStyleResult() throws Exception {
        var s = with(ScriptConnector.parse(null), new FakeEngine());
        var out = s.call(m("code", "total := sum(items.price)\n{ total: total, big: total > 20, tags: [\"a\", \"b\"] }", "outputMap", m("t", "result.total")),
                m("items", List.of(m("price", 10), m("price", 30))));
        assertEquals(m("total", 40L, "big", true, "tags", List.of("a", "b")), out.get("result"));
        assertEquals(40L, out.get("t"));
        assertEquals(2.5, s.call(m("code", "5 / 2"), Map.of()).get("result"));
    }

    @Test
    void cesErrorsAreReportedAndNeverRetried() {
        var s = with(ScriptConnector.parse(null), new FakeEngine());
        for (String bad : new String[] {"a := (1 +", "", "x := 1\nreturn"}) {
            var e = assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(m("code", bad), Map.of()));
            assertFalse(e.retryable());
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(m(), Map.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(m("code", "1", "timeoutMs", 5), Map.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(m("code", "1", "language", "ruby"), Map.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(m("code", "x".repeat(100_001)), Map.of()));
    }

    @Test
    void javascriptAndPythonAreOffUnlessTheOperatorEnablesThem() {
        var e = new FakeEngine();
        var off = with(ScriptConnector.parse(null), e);
        var err = assertThrows(ServiceHandler.ConnectorException.class, () -> off.call(m("language", "javascript", "code", "return-input"), Map.of()));
        assertTrue(err.getMessage().contains("not enabled") && err.getMessage().contains(ScriptConnector.ENV), err.getMessage());
        assertNull(e.seen.get(), "the engine was never touched");
        assertEquals(Set.of("ces", "javascript", "python"), ScriptConnector.parse("js, python ,CES,"));
        assertThrows(IllegalArgumentException.class, () -> ScriptConnector.parse("ces,pyhton"));
    }

    @Test
    void anEnabledEngineGetsOnlyAJsonStyleCopyOfTheInputsAndItsResultIsNormalised() throws Exception {
        var e = new FakeEngine();
        var s = with(ScriptConnector.parse("javascript"), e);
        var in = m("name", "Budi", "n", new java.math.BigDecimal("3"), "list", List.of(1, 2), "when", java.time.LocalDate.of(2026, 1, 2));
        var out = s.call(m("language", "js", "code", "return-input"), in);
        assertEquals(m("name", "Budi", "n", 3L, "list", List.of(1, 2), "when", "2026-01-02"), out.get("result"));
        assertEquals("not json", s.call(m("language", "javascript", "code", "object"), Map.of()).get("result"));
        assertTrue(in != e.seen.get().get("input"), "the engine gets a copy, not the live map");
    }

    @Test
    void missingEnginesFailClearlyAndTheEngineNameIsValidated() {
        var s = new ScriptConnector(ScriptConnector.parse("python"), name -> null);
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(m("language", "python", "code", "1"), Map.of()));
        assertTrue(e.getMessage().contains("No JSR-223 engine"), e.getMessage());
        assertThrows(ServiceHandler.ConnectorException.class, () -> with(ScriptConnector.parse("python"), new FakeEngine()).call(m("language", "python", "engine", "a b;c", "code", "1"), Map.of()));
    }

    @Test
    void aRunawayScriptIsInterruptedAtTheTimeoutAndErrorsDoNotLeakDetails() throws Exception {
        var s = with(ScriptConnector.parse("js"), new FakeEngine());
        long t0 = System.nanoTime();
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(m("language", "js", "code", "sleep", "timeoutMs", 300), Map.of()));
        assertTrue(e.getMessage().contains("300 ms") && !e.retryable(), e.getMessage());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000, "returned near the timeout, not after the 10 s sleep");
        var b = assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(m("language", "js", "code", "boom"), Map.of()));
        assertTrue(b.getMessage().startsWith("Script error: IllegalStateException") && !b.retryable(), b.getMessage());
    }

    @Test
    void atMostFourForeignScriptsRunAtOnce() throws Exception {
        var s = with(ScriptConnector.parse("js"), new FakeEngine());
        List<Thread> ts = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(() -> {
                try {
                    s.call(m("language", "js", "code", "sleep", "timeoutMs", 2000), Map.of());
                } catch (Exception ignored) {
                    // times out
                }
            });
            t.start();
            ts.add(t);
        }
        Thread.sleep(300);
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(m("language", "js", "code", "return-input"), Map.of()));
        assertTrue(e.retryable() && e.getMessage().contains("Too many"), e.getMessage());
        for (Thread t : ts) {
            t.join();
        }
        Thread.sleep(300); // interrupted threads end and give their slots back
        assertNotNull(s.call(m("language", "js", "code", "return-input"), m("a", 1)));
    }

    @Test
    void thereIsAScriptConnectorByDefaultButOnlyCesIsOn() {
        assertTrue(Connectors.defaults(new UrlGuard(true, Set.of())).containsKey("script"));
    }
}
