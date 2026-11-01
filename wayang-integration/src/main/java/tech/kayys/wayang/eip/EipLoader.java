package tech.kayys.wayang.eip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.expression.Expression;
import tech.kayys.wayang.expression.Expressions;

/**
 * Builds a {@link Pipeline} from Map/List configuration (what JSON/YAML parse to).
 *
 * <pre>
 * delivery: AT_LEAST_ONCE
 * steps:
 *   - {type: parse, format: json}                                  # text body -> data (json|csv|xml)
 *   - {type: filter, when: "body.amount > 0"}
 *   - {type: map, mapping: {"customer.name": "body.first", total: "body.price * body.qty"},
 *      headers: {kind: {$const: order}}, merge: false, remove: [secret], omitNull: true}
 *   - {type: splitter, items: "body.lines"}
 *   - {type: router, routes: [{when: "body.vip", to: "priority"}], otherwise: "standard"}
 *   - {type: recipientList, routes: [...]}
 *   - {type: choice, branches: [{when: "body.vip", steps: [...]}], otherwise: [...], onNoMatch: fail|pass|drop}
 *   - {type: multicast, gather: true, branches: {a: [...steps], b: [...steps]}}
 *   - {type: guard, steps: [...], handler: [...], deadLetter: "dlq"}
 *   - {type: call, connector: http, config: {...}, input: {url: "..."}, output: {...}, into: "extra", tap: false}
 *   - {type: aggregate, correlation: "body.order", size: 2, incomplete: fail|drop|emit}
 *   - {type: resequence, by: "body.n"}
 *   - {type: idempotent, key: "body.id"}
 *   - {type: serialize, format: csv, from: "body.rows"}
 * </pre>
 */
public final class EipLoader {

    private static final int MAX_NESTING = 8;
    private static final int MAX_STEPS = 500;

    private EipLoader() {
    }

    public static Pipeline load(Map<String, ?> cfg, IdempotencyStore store) {
        return load(cfg, store, null);
    }

    public static Pipeline load(Map<String, ?> cfg, IdempotencyStore store, Invoker invoker) {
        return build(cfg, Services.basic(store, invoker));
    }

    public static Pipeline build(Map<String, ?> cfg, Services svc) {
        Pipeline.Delivery d;
        try {
            d = Pipeline.Delivery.valueOf(String.valueOf(cfg.get("delivery") == null ? "AT_LEAST_ONCE" : cfg.get("delivery")).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new EipException("Unknown delivery guarantee: " + cfg.get("delivery"), e);
        }
        int[] count = {0};
        return new Pipeline(steps(cfg.get("steps"), svc, 0, count), d);
    }

    private static List<Step> steps(Object raw, Services svc, int depth, int[] count) {
        if (depth > MAX_NESTING) {
            throw new EipException("Pipelines nested deeper than " + MAX_NESTING);
        }
        if (raw != null && !(raw instanceof List)) {
            throw new EipException("'steps' must be a list");
        }
        List<Step> steps = new ArrayList<>();
        for (Object o : raw == null ? List.of() : (List<?>) raw) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new EipException("Each step must be an object but got " + o);
            }
            if (++count[0] > MAX_STEPS) {
                throw new EipException("Pipeline has more than " + MAX_STEPS + " steps");
            }
            steps.add(step(m, svc, depth, count));
        }
        return steps;
    }

    private static Pipeline sub(Object raw, Services svc, int depth, int[] count) {
        return new Pipeline(steps(raw, svc, depth + 1, count), Pipeline.Delivery.AT_LEAST_ONCE);
    }

    private static Step step(Map<?, ?> m, Services svc, int depth, int[] count) {
        String type = str(m, "type");
        switch (type) {
            case "filter":
                return new Step.Filter(str(m, "when"));
            case "transform": {
                Map<String, String> headers = new LinkedHashMap<>();
                if (m.get("headers") instanceof Map<?, ?> h) {
                    h.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
                }
                Object body = m.get("body");
                return new Step.Transform(body == null ? null : String.valueOf(body), headers);
            }
            case "map": {
                boolean omit = bool(m, "omitNull");
                Map<String, Mapper> headers = new LinkedHashMap<>();
                if (m.get("headers") instanceof Map<?, ?> h) {
                    h.forEach((k, v) -> headers.put(String.valueOf(k), Mapper.compile(v, omit)));
                } else if (m.get("headers") != null) {
                    throw new EipException("'headers' must be an object");
                }
                List<String> remove = new ArrayList<>();
                if (m.get("remove") instanceof List<?> r) {
                    r.forEach(x -> remove.add(String.valueOf(x)));
                } else if (m.get("remove") != null) {
                    throw new EipException("'remove' must be a list of paths");
                }
                Mapper body = m.get("mapping") == null ? null : Mapper.compile(m.get("mapping"), omit);
                if (body == null && headers.isEmpty() && remove.isEmpty()) {
                    throw new EipException("map step needs 'mapping', 'headers' or 'remove'");
                }
                return new Step.MapBody(body, bool(m, "merge"), headers, remove);
            }
            case "parse":
            case "serialize": {
                String format = str(m, "format");
                if (!List.of("json", "csv", "xml").contains(format)) {
                    throw new EipException("Unknown format '" + format + "' (json, csv, xml)");
                }
                Map<String, Object> opts = new LinkedHashMap<>();
                for (String k : List.of("delimiter", "header", "escapeFormulas")) {
                    if (m.get(k) != null) {
                        opts.put(k, m.get(k));
                    }
                }
                var from = m.get("from") == null ? null : Expressions.parse(String.valueOf(m.get("from")));
                return type.equals("parse") ? new Step.Parse(format, from, opts) : new Step.Serialize(format, from, opts);
            }
            case "splitter":
                return new Step.Splitter(str(m, "items"));
            case "router":
                return new Step.Router(routes(m), m.get("otherwise") == null ? null : String.valueOf(m.get("otherwise")));
            case "recipientList":
                return new Step.RecipientList(routes(m));
            case "choice": {
                if (!(m.get("branches") instanceof List<?> bl) || bl.isEmpty()) {
                    throw new EipException("choice needs a non-empty 'branches' list");
                }
                List<Step.Choice.Branch> bs = new ArrayList<>();
                for (Object o : bl) {
                    if (!(o instanceof Map<?, ?> b)) {
                        throw new EipException("Each branch must be an object");
                    }
                    bs.add(new Step.Choice.Branch(str(b, "when"), sub(b.get("steps"), svc, depth, count)));
                }
                Step.NoMatch nm;
                try {
                    nm = Step.NoMatch.valueOf(String.valueOf(m.get("onNoMatch") == null ? "fail" : m.get("onNoMatch")).toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new EipException("onNoMatch must be fail, pass or drop");
                }
                return new Step.Choice(bs, m.get("otherwise") == null ? null : sub(m.get("otherwise"), svc, depth, count), nm);
            }
            case "multicast": {
                if (!(m.get("branches") instanceof Map<?, ?> bm) || bm.isEmpty()) {
                    throw new EipException("multicast needs a non-empty 'branches' object");
                }
                Map<String, Pipeline> bs = new LinkedHashMap<>();
                bm.forEach((k, v) -> bs.put(String.valueOf(k), sub(v, svc, depth, count)));
                boolean par = bool(m, "parallel");
                if (par && bs.size() > Step.Multicast.MAX_PARALLEL) {
                    throw new EipException("parallel multicast allows at most " + Step.Multicast.MAX_PARALLEL + " branches");
                }
                return new Step.Multicast(bs, bool(m, "gather"), par, bounded(m, "timeoutSeconds", 0, 0, 120) * 1000L);
            }
            case "guard":
                return new Step.Guard(sub(m.get("steps"), svc, depth, count),
                        m.get("handler") == null ? null : sub(m.get("handler"), svc, depth, count),
                        m.get("deadLetter") == null ? "dead-letter" : String.valueOf(m.get("deadLetter")));
            case "call": {
                if (svc.invoker() == null) {
                    throw new EipException("A call step needs an Invoker (connectors) in this runtime");
                }
                boolean omit = bool(m, "omitNull");
                Map<String, Object> cfg = new LinkedHashMap<>();
                if (m.get("config") instanceof Map<?, ?> c) {
                    c.forEach((k, v) -> cfg.put(String.valueOf(k), v));
                }
                return new Step.Call(str(m, "connector"), cfg, m.get("input") == null ? null : Mapper.compile(m.get("input"), omit),
                        m.get("output") == null ? null : Mapper.compile(m.get("output"), omit),
                        m.get("into") == null ? null : String.valueOf(m.get("into")), bool(m, "tap"), svc.invoker());
            }
            case "aggregate": {
                int size;
                try {
                    size = Integer.parseInt(String.valueOf(m.get("size") == null ? "1" : m.get("size")));
                } catch (NumberFormatException e) {
                    throw new EipException("aggregate 'size' must be an integer");
                }
                Step.Aggregate.Incomplete inc;
                try {
                    inc = Step.Aggregate.Incomplete.valueOf(String.valueOf(m.get("incomplete") == null ? "fail" : m.get("incomplete")).toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new EipException("aggregate 'incomplete' must be fail, drop or emit");
                }
                return new Step.Aggregate(new Aggregator(str(m, "correlation"), size), inc);
            }
            case "resequence":
                return new Step.Resequence(str(m, "by"));
            case "idempotent":
                if (svc.idempotency() == null) {
                    throw new EipException("An idempotent step needs an IdempotencyStore");
                }
                return new Step.Idempotent(str(m, "key"), svc.idempotency());
            case "validate": {
                List<Patterns.Validate.Rule> rules = new ArrayList<>();
                if (m.get("rules") instanceof List<?> rl) {
                    for (Object o : rl) {
                        if (!(o instanceof Map<?, ?> r)) {
                            throw new EipException("Each validate rule must be an object");
                        }
                        String when = str(r, "when");
                        rules.add(new Patterns.Validate.Rule(when, r.get("message") == null ? "rule failed: " + when : String.valueOf(r.get("message")),
                                expr("validate rule", when)));
                    }
                } else if (m.get("rules") != null) {
                    throw new EipException("validate 'rules' must be a list");
                }
                Map<String, String> types = new LinkedHashMap<>();
                if (m.get("types") instanceof Map<?, ?> tm) {
                    tm.forEach((k, v) -> {
                        if (!List.of("number", "string", "boolean", "object", "array").contains(String.valueOf(v))) {
                            throw new EipException("validate type for '" + k + "' must be number, string, boolean, object or array");
                        }
                        types.put(String.valueOf(k), String.valueOf(v));
                    });
                } else if (m.get("types") != null) {
                    throw new EipException("validate 'types' must be an object");
                }
                List<String> req = strings(m, "required");
                if (rules.isEmpty() && req.isEmpty() && types.isEmpty()) {
                    throw new EipException("validate needs 'rules', 'required' or 'types'");
                }
                Patterns.Validate.Mode mode = enumOf(Patterns.Validate.Mode.class, m.get("onInvalid"), "fail", "onInvalid");
                return new Patterns.Validate(rules, req, types, mode, m.get("deadLetter") == null ? "invalid" : String.valueOf(m.get("deadLetter")));
            }
            case "contentFilter": {
                List<String> keep = strings(m, "keep");
                List<String> remove = strings(m, "remove");
                if (keep.isEmpty() && remove.isEmpty()) {
                    throw new EipException("contentFilter needs 'keep' or 'remove'");
                }
                return new Patterns.ContentFilter(keep, remove);
            }
            case "envelope": {
                if ((m.get("wrap") == null) == (m.get("unwrap") == null)) {
                    throw new EipException("envelope needs exactly one of 'wrap' or 'unwrap'");
                }
                return new Patterns.Envelope(m.get("wrap") == null ? null : str(m, "wrap"),
                        m.get("with") == null ? null : Mapper.compile(m.get("with"), bool(m, "omitNull")),
                        m.get("unwrap") == null ? null : str(m, "unwrap"));
            }
            case "claimCheck": {
                String action = str(m, "action");
                if (!action.equals("store") && !action.equals("retrieve")) {
                    throw new EipException("claimCheck 'action' must be store or retrieve");
                }
                if (svc.claims() == null) {
                    throw new EipException("A claimCheck step needs a ClaimStore in this runtime");
                }
                return new Patterns.ClaimCheck(action.equals("store"), bounded(m, "ttlSeconds", 3600, 1, 7 * 86400),
                        m.get("header") == null ? "claimCheck" : str(m, "header"), bool(m, "consume"), bool(m, "dropIfMissing"),
                        bounded(m, "maxBytes", 1_000_000, 1, 5_000_000), svc);
            }
            case "loop": {
                Integer times = m.get("times") == null ? null : (int) bounded(m, "times", 1, 0, 1000);
                Expression w = m.get("while") == null ? null : expr("loop while", str(m, "while"));
                Expression u = m.get("until") == null ? null : expr("loop until", str(m, "until"));
                if (times == null && w == null && u == null) {
                    throw new EipException("loop needs 'times', 'while' or 'until'");
                }
                return new Patterns.Loop(times, w, u, (int) bounded(m, "maxIterations", 100, 1, 1000), sub(m.get("steps"), svc, depth, count));
            }
            case "routingSlip": {
                if (!(m.get("stages") instanceof Map<?, ?> sm) || sm.isEmpty()) {
                    throw new EipException("routingSlip needs a non-empty 'stages' object");
                }
                Map<String, Pipeline> stages = new LinkedHashMap<>();
                sm.forEach((k, v) -> stages.put(String.valueOf(k), sub(v, svc, depth, count)));
                return new Patterns.RoutingSlip(expr("routingSlip", str(m, "slip")), stages, bool(m, "skipUnknown"));
            }
            case "switch": {
                if (!(m.get("cases") instanceof Map<?, ?> cm) || cm.isEmpty()) {
                    throw new EipException("switch needs a non-empty 'cases' object");
                }
                Map<String, Pipeline> cases = new LinkedHashMap<>();
                cm.forEach((k, v) -> cases.put(String.valueOf(k), sub(v, svc, depth, count)));
                return new Patterns.Switch(expr("switch", str(m, "on")), cases,
                        m.get("default") == null ? null : sub(m.get("default"), svc, depth, count),
                        enumOf(Step.NoMatch.class, m.get("onNoMatch"), "fail", "onNoMatch"));
            }
            case "loadBalance": {
                String strategy = m.get("strategy") == null ? "roundRobin" : str(m, "strategy");
                if (!List.of("roundRobin", "hash", "random").contains(strategy)) {
                    throw new EipException("loadBalance 'strategy' must be roundRobin, hash or random");
                }
                if (!(m.get("targets") instanceof Map<?, ?> tm) || tm.isEmpty()) {
                    throw new EipException("loadBalance needs a non-empty 'targets' object");
                }
                if (strategy.equals("hash") && m.get("key") == null) {
                    throw new EipException("loadBalance 'hash' needs a 'key'");
                }
                List<String> names = new ArrayList<>();
                List<Pipeline> targets = new ArrayList<>();
                tm.forEach((k, v) -> {
                    names.add(String.valueOf(k));
                    targets.add(sub(v, svc, depth, count));
                });
                String name = m.get("name") == null ? "default" : str(m, "name");
                return new Patterns.LoadBalance(strategy, m.get("key") == null ? null : expr("loadBalance key", str(m, "key")), names, targets,
                        bool(m, "failover"), svc.namespace() + "|lb|" + name, svc.state());
            }
            case "retry":
                return new Patterns.Retry(sub(m.get("steps"), svc, depth, count),
                        m.get("handler") == null ? null : sub(m.get("handler"), svc, depth, count),
                        (int) bounded(m, "attempts", 3, 1, 10), bounded(m, "backoffMs", 0, 0, 2000),
                        m.get("deadLetter") == null ? "dead-letter" : String.valueOf(m.get("deadLetter")), svc.sleeper());
            case "circuitBreaker":
                return new Patterns.CircuitBreaker(str(m, "name"), (int) bounded(m, "failureThreshold", 5, 1, 1000),
                        bounded(m, "openSeconds", 30, 1, 3600) * 1000, sub(m.get("steps"), svc, depth, count),
                        m.get("fallback") == null ? null : sub(m.get("fallback"), svc, depth, count), svc.state(), svc.namespace(), svc.clock());
            case "throttle": {
                double per = m.get("perSecond") == null ? 10 : Double.parseDouble(String.valueOf(m.get("perSecond")));
                if (!(per > 0) || per > 10_000) {
                    throw new EipException("throttle 'perSecond' must be above 0 and at most 10000");
                }
                double burst = m.get("burst") == null ? Math.max(1, per) : Double.parseDouble(String.valueOf(m.get("burst")));
                if (!(burst >= 1) || burst > 100_000) {
                    throw new EipException("throttle 'burst' must be between 1 and 100000");
                }
                return new Patterns.Throttle(str(m, "name"), per, burst, enumOf(Patterns.Throttle.Mode.class, m.get("onExceed"), "fail", "onExceed"),
                        bounded(m, "maxWaitMs", 1000, 0, 5000), svc.state(), svc.namespace(), svc.clock(), svc.sleeper());
            }
            case "delay": {
                if ((m.get("ms") == null) == (m.get("notBefore") == null)) {
                    throw new EipException("delay needs exactly one of 'ms' (max 10000) or 'notBefore' (an expression stamped into a header)");
                }
                return new Patterns.Delay(m.get("ms") == null ? 0 : bounded(m, "ms", 0, 0, 10_000),
                        m.get("notBefore") == null ? null : expr("delay notBefore", str(m, "notBefore")), svc.sleeper());
            }
            case "batch":
                return new Patterns.Batch((int) bounded(m, "size", 10, 1, 10_000));
            case "correlate":
                return new Patterns.Correlate(m.get("key") == null ? null : expr("correlate", str(m, "key")), bool(m, "force"));
            case "label":
                return new Patterns.Label(str(m, "name"));
            case "lookup": {
                if (!(m.get("table") instanceof Map<?, ?> tm) || tm.isEmpty() || tm.size() > 10_000) {
                    throw new EipException("lookup needs a 'table' object with 1 to 10000 entries");
                }
                Map<String, Object> table = new LinkedHashMap<>();
                tm.forEach((k, v) -> table.put(String.valueOf(k), v));
                return new Patterns.Lookup(table, expr("lookup", str(m, "key")), str(m, "into"), bool(m, "failIfMissing"), m.get("default"));
            }
            case "tokenize": {
                String sep = str(m, "separator");
                if (sep.isEmpty()) {
                    throw new EipException("tokenize 'separator' must not be empty");
                }
                return new Patterns.Tokenize(m.get("from") == null ? null : expr("tokenize", str(m, "from")), sep, bool(m, "trim"),
                        bool(m, "skipEmpty"), (int) bounded(m, "limit", 1000, 1, Step.MAX_FANOUT));
            }
            case "collect": {
                if (svc.aggregation() == null) {
                    throw new EipException("A collect step needs an AggregationStore in this runtime");
                }
                Integer size = m.get("size") == null ? null : (int) bounded(m, "size", 1, 1, 10_000);
                Expression cw = m.get("completeWhen") == null ? null : expr("collect completeWhen", str(m, "completeWhen"));
                if (size == null && cw == null) {
                    throw new EipException("collect needs 'size' or 'completeWhen'");
                }
                String onTimeout = m.get("onTimeout") == null ? "emit" : str(m, "onTimeout");
                if (!onTimeout.equals("emit") && !onTimeout.equals("drop")) {
                    throw new EipException("collect 'onTimeout' must be emit or drop");
                }
                long timeoutMs = bounded(m, "timeoutSeconds", 0, 0, 30L * 86400) * 1000;
                String sweepTo = CollectSweeper.sweepTo(m);
                if (sweepTo != null && (timeoutMs == 0 || !onTimeout.equals("emit"))) {
                    throw new EipException("collect 'sweepTo' needs 'timeoutSeconds' and onTimeout 'emit' (the swept group is what starts that process)");
                }
                return new Patterns.Collect(expr("collect key", str(m, "key")), size, cw, timeoutMs,
                        onTimeout.equals("emit"), (int) bounded(m, "maxGroup", 1000, 1, 10_000), svc, sweepTo);
            }
            default:
                throw new EipException("Unknown EIP step type '" + type + "'");
        }
    }

    private static tech.kayys.wayang.expression.Expression expr(String what, String src) {
        try {
            return Expressions.parse(src);
        } catch (tech.kayys.wayang.expression.ExpressionException e) {
            throw new EipException(what + " '" + src + "': " + e.getMessage(), e);
        }
    }

    private static long bounded(Map<?, ?> m, String key, long dflt, long min, long max) {
        Object v = m.get(key);
        if (v == null) {
            return dflt;
        }
        long n;
        try {
            n = new java.math.BigDecimal(String.valueOf(v)).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new EipException("'" + key + "' must be an integer");
        }
        if (n < min || n > max) {
            throw new EipException("'" + key + "' must be between " + min + " and " + max);
        }
        return n;
    }

    private static List<String> strings(Map<?, ?> m, String key) {
        List<String> out = new ArrayList<>();
        Object v = m.get(key);
        if (v == null) {
            return out;
        }
        if (!(v instanceof List<?> l)) {
            throw new EipException("'" + key + "' must be a list of paths");
        }
        l.forEach(x -> out.add(String.valueOf(x)));
        return out;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, Object v, String dflt, String what) {
        try {
            return Enum.valueOf(type, String.valueOf(v == null ? dflt : v).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new EipException("'" + what + "' must be one of " + java.util.Arrays.stream(type.getEnumConstants()).map(x -> x.name().toLowerCase(java.util.Locale.ROOT)).toList());
        }
    }

    private static boolean bool(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    private static List<Step.Router.Route> routes(Map<?, ?> m) {
        List<Step.Router.Route> out = new ArrayList<>();
        if (!(m.get("routes") instanceof List<?> l)) {
            throw new EipException("'routes' must be a list");
        }
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> r)) {
                throw new EipException("Each route must be an object");
            }
            out.add(new Step.Router.Route(str(r, "when"), str(r, "to")));
        }
        return out;
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new EipException("Missing required field '" + key + "'");
        }
        return String.valueOf(v);
    }
}
