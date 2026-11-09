package tech.kayys.wayang.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import tech.kayys.wayang.expression.ExpressionException;
import tech.kayys.wayang.expression.Script;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Connector {@code script}: runs a short script over the node's inputs.
 *
 * <p>Config: <b>{@code code}</b> (fixed in the definition, never taken from inputs; at most 100,000 characters), {@code language}
 * ({@code ces} by default, {@code javascript} or {@code python}), {@code timeoutMs} (default 5000, 100 to 60000), {@code outputMap}
 * (variable to path in the result), {@code engine} (a JSR-223 engine name to use for javascript/python instead of the defaults). The
 * node's {@code inputs} are the data: {@code ces} sees each input as a variable, javascript and python see one object {@code input}
 * (a JSON-style copy). Output: {@code result} (JSON-style, at most 1 MiB serialized) and the outputMap variables.
 *
 * <p><b>ces</b> (Common Expression Script, see {@link Script}) is the bounded Wayang expression language with a few statements; it cannot
 * do I/O and is always available.
 *
 * <p><b>javascript / python</b> run through the standard JSR-223 interface ({@code javax.script}), so any engine on the class path works:
 * the OpenJDK Project Detroit engines (JavaScript on V8, Python on CPython), GraalJS, GraalPy, and so on. They are <b>off by default</b>:
 * the operator enables them with {@code WAYANG_SCRIPT_LANGUAGES=ces,javascript,python}. <b>They are not a sandbox.</b> A script in an
 * in-process engine runs with the privileges of the server, may reach the file system and network depending on the engine, and a runaway
 * script cannot be forced to stop (the timeout releases the caller and interrupts the thread, nothing more). At most 4 foreign scripts
 * run at once. Enable them only where every author of a definition is trusted, or run such work in a separate worker.
 */
public final class ScriptConnector implements ServiceHandler.Connector {

    public static final String ENV = "WAYANG_SCRIPT_LANGUAGES";
    static final int MAX_CODE = 100_000;
    static final int MAX_RESULT = 1 << 20;
    private static final Set<String> KNOWN = Set.of("ces", "javascript", "js", "python", "py");
    private static final Map<String, List<String>> ENGINES = Map.of("javascript", List.of("javascript", "js"), "python", List.of("python", "graalpy"));

    private final Set<String> allowed;
    private final Function<String, ScriptEngine> lookup;
    private final Semaphore slots = new Semaphore(4);
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "wayang-script");
        t.setDaemon(true);
        return t;
    });

    /** Languages from {@value #ENV} (default only {@code ces}), engines from the JDK's {@link ScriptEngineManager}. */
    public ScriptConnector() {
        this(parse(System.getenv(ENV)), name -> new ScriptEngineManager().getEngineByName(name));
    }

    public ScriptConnector(Set<String> allowedLanguages, Function<String, ScriptEngine> lookup) {
        this.allowed = allowedLanguages;
        this.lookup = lookup;
    }

    /** {@code ces,js,python} to canonical names; a typo is an error rather than a silently disabled language. */
    public static Set<String> parse(String list) {
        Set<String> out = new java.util.LinkedHashSet<>();
        out.add("ces");
        if (list != null) {
            for (String raw : list.split(",")) {
                String t = raw.strip().toLowerCase(Locale.ROOT);
                if (t.isEmpty()) {
                    continue;
                }
                if (!KNOWN.contains(t)) {
                    throw new IllegalArgumentException(ENV + " has an unknown language '" + t.replaceAll("[^a-z0-9]", "?") + "' (use ces, javascript, python)");
                }
                out.add(t.equals("js") ? "javascript" : t.equals("py") ? "python" : t);
            }
        }
        return Set.copyOf(out);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ServiceHandler.ConnectorException("script needs node configuration (code)", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        if (!(config.get("code") instanceof String code) || code.isBlank()) {
            throw new ServiceHandler.ConnectorException("script needs 'code' in the node configuration", false);
        }
        if (code.length() > MAX_CODE) {
            throw new ServiceHandler.ConnectorException("script code is longer than " + MAX_CODE + " characters", false);
        }
        String language = String.valueOf(config.getOrDefault("language", "ces")).strip().toLowerCase(Locale.ROOT);
        language = language.equals("js") ? "javascript" : language.equals("py") ? "python" : language;
        if (!language.equals("ces") && !language.equals("javascript") && !language.equals("python")) {
            throw new ServiceHandler.ConnectorException("Unsupported script language (use ces, javascript or python)", false);
        }
        if (!allowed.contains(language)) {
            throw new ServiceHandler.ConnectorException("Script language '" + language + "' is not enabled on this server (operator setting " + ENV + ")", false);
        }
        long timeout = config.get("timeoutMs") instanceof Number n ? n.longValue() : 5000;
        if (timeout < 100 || timeout > 60_000) {
            throw new ServiceHandler.ConnectorException("script timeoutMs must be 100..60000", false);
        }
        Map<String, Object> in = inputs == null ? Map.of() : inputs;
        Object result = language.equals("ces") ? runCes(code, in) : runForeign(language, code, config, in, timeout);
        Object plain = plain(result, 0);
        if (Json.write(plain).length() > MAX_RESULT) {
            throw new ServiceHandler.ConnectorException("The script result is larger than 1 MiB", false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", plain);
        Proto.outputMap(config, Map.of("result", plain == null ? "" : plain), out);
        return out;
    }

    private static Object runCes(String code, Map<String, Object> in) throws ServiceHandler.ConnectorException {
        try {
            return Script.evaluate(code, in);
        } catch (ExpressionException e) { // includes the step-limit error
            throw new ServiceHandler.ConnectorException("Script error: " + Proto.clip(e.getMessage(), 300), false);
        }
    }

    private Object runForeign(String language, String code, Map<String, Object> config, Map<String, Object> in, long timeoutMs) throws Exception {
        List<String> names = new ArrayList<>();
        if (config.get("engine") instanceof String e && !e.isBlank()) {
            if (!e.matches("[A-Za-z0-9._-]{1,40}")) {
                throw new ServiceHandler.ConnectorException("script engine is not a valid engine name", false);
            }
            names.add(e);
        } else {
            names.addAll(ENGINES.get(language));
        }
        ScriptEngine engine = null;
        for (String n : names) {
            engine = lookup.apply(n);
            if (engine != null) {
                break;
            }
        }
        if (engine == null) {
            throw new ServiceHandler.ConnectorException("No JSR-223 engine for '" + language + "' is installed on this server (for example OpenJDK Project Detroit, GraalJS or GraalPy)", false);
        }
        if (!slots.tryAcquire()) {
            throw new ServiceHandler.ConnectorException("Too many scripts are running; try again shortly", true);
        }
        ScriptEngine eng = engine;
        Object copy = plain(in, 0);
        Future<Object> f;
        try {
            f = pool.submit(() -> {
                try {
                    Bindings b = eng.createBindings();
                    b.put("input", copy);
                    return eng.eval(code, b);
                } finally {
                    slots.release(); // freed only when the thread really ends, even after a timeout
                }
            });
        } catch (RuntimeException e) {
            slots.release();
            throw new ServiceHandler.ConnectorException("The script could not be started", true);
        }
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true); // interrupts; an engine that ignores interrupts keeps its thread and slot until it ends
            throw new ServiceHandler.ConnectorException("The script ran longer than " + timeoutMs + " ms and was interrupted", false);
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new ServiceHandler.ConnectorException("Script error: " + Proto.clip(c.getClass().getSimpleName() + ": " + c.getMessage(), 300), false);
        }
    }

    /** A JSON-style copy: maps, lists, text, numbers, booleans; anything else becomes its text. Depth-limited. */
    static Object plain(Object v, int depth) throws ServiceHandler.ConnectorException {
        if (depth > 32) {
            throw new ServiceHandler.ConnectorException("The script data is nested deeper than 32 levels", false);
        }
        if (v == null || v instanceof Boolean || v instanceof String) {
            return v;
        }
        if (v instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0 && bd.abs().compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE)) < 0 ? (Object) bd.longValue() : (Object) bd.doubleValue();
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : v;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), plain(e.getValue(), depth + 1));
            }
            return out;
        }
        if (v instanceof Iterable<?> it) {
            List<Object> out = new ArrayList<>();
            for (Object o : it) {
                out.add(plain(o, depth + 1));
            }
            return out;
        }
        if (v instanceof Object[] arr) {
            return plain(java.util.Arrays.asList(arr), depth);
        }
        return String.valueOf(v);
    }
}
