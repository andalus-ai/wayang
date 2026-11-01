package tech.kayys.wayang.eip;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tech.kayys.wayang.expression.Expression;
import tech.kayys.wayang.expression.ExpressionException;
import tech.kayys.wayang.expression.Scope;

/**
 * The rest of the Enterprise Integration Patterns catalog as pipeline steps: validation, envelope, claim check, loop,
 * routing slip, switch/normalizer, load balancer, retry, circuit breaker, throttle, delay, batch, correlation, lookup,
 * tokenizer and a durable aggregator. See {@code docs/EIP.md} for what each does and what is per-process only.
 */
public final class Patterns {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Patterns() {
    }

    // ------------------------------------------------------------------ helpers

    /** Reads {@code a.b.c} from nested maps; null when any step is missing. */
    static Object path(Object root, String path) {
        Object cur = root;
        for (String p : path.split("\\.", -1)) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(p);
        }
        return cur;
    }

    /** Stable text for a value used as a key: 1, 1.0 and 1.00 are the same key. */
    static String keyOf(Object v) {
        if (v instanceof BigDecimal b) {
            return b.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapBody(Message in, String what) {
        if (!(in.body() instanceof Map<?, ?> m)) {
            throw new EipException(what + " needs an object body");
        }
        return (Map<String, Object>) m;
    }

    private static Message append(Message m, String header, Object entry) {
        List<Object> l = new ArrayList<>();
        if (m.headers().get(header) instanceof List<?> old) {
            l.addAll(old);
        }
        if (l.size() < 100) {
            l.add(entry);
        }
        return m.withHeader(header, l);
    }

    private static boolean failure(RuntimeException e) {
        return e instanceof EipException || e instanceof ExpressionException;
    }

    // ------------------------------------------------------------------ validate

    /** Message Validator / Invalid Message Channel. */
    public record Validate(List<Rule> rules, List<String> required, Map<String, String> types, Mode mode, String deadLetter)
            implements Step {
        public enum Mode {
            FAIL, DROP, ROUTE
        }

        public record Rule(String when, String message, Expression compiled) {
        }

        @Override
        public List<Message> apply(Message in, Scope scope) {
            List<String> problems = new ArrayList<>();
            Scope s = in.scope(scope);
            for (String r : required) {
                Object v = path(in.body(), r);
                if (v == null) {
                    problems.add("'" + r + "' is required");
                }
            }
            for (Map.Entry<String, String> t : types.entrySet()) {
                Object v = path(in.body(), t.getKey());
                if (v != null && !typeOk(t.getValue(), v)) {
                    problems.add("'" + t.getKey() + "' must be " + t.getValue());
                }
            }
            for (Rule r : rules) {
                if (!r.compiled().test(s)) {
                    problems.add(r.message());
                }
            }
            if (problems.isEmpty()) {
                return List.of(in);
            }
            String msg = "Invalid message: " + String.join("; ", problems);
            return switch (mode) {
                case DROP -> List.of();
                case ROUTE -> List.of(in.withHeader("error", msg).withHeader("routeTo", deadLetter));
                case FAIL -> throw new EipException(msg, null, true);
            };
        }

        static boolean typeOk(String type, Object v) {
            return switch (type) {
                case "number" -> v instanceof Number;
                case "string" -> v instanceof String;
                case "boolean" -> v instanceof Boolean;
                case "object" -> v instanceof Map;
                case "array" -> v instanceof List;
                default -> false;
            };
        }
    }

    // ------------------------------------------------------------------ content filter / envelope

    /** Content Filter: keep only some paths of the body, and/or remove others. */
    public record ContentFilter(List<String> keep, List<String> remove) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            Map<String, Object> body = mapBody(in, "contentFilter");
            Object out = body;
            if (!keep.isEmpty()) {
                Map<String, Object> kept = new LinkedHashMap<>();
                for (String k : keep) {
                    Object v = path(body, k);
                    if (v != null) {
                        Mapper.setPath(kept, k, v);
                    }
                }
                out = kept;
            }
            for (String r : remove) {
                out = Mapper.removePath(out, r);
            }
            return List.of(in.withBody(out));
        }
    }

    /** Envelope Wrapper: put the body inside a wrapper object, or take it out again. */
    public record Envelope(String wrap, Mapper extra, String unwrap) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            if (unwrap != null) {
                Object inner = path(mapBody(in, "envelope unwrap"), unwrap);
                if (inner == null) {
                    throw new EipException("envelope: '" + unwrap + "' not found in the body");
                }
                return List.of(in.withBody(inner));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            if (extra != null) {
                Object e = extra.apply(in.scope(scope));
                if (!(e instanceof Map<?, ?> em)) {
                    throw new EipException("envelope 'with' must produce an object");
                }
                em.forEach((k, v) -> out.put(String.valueOf(k), v));
            }
            if (out.containsKey(wrap)) {
                throw new EipException("envelope 'with' must not set '" + wrap + "'");
            }
            out.put(wrap, in.body());
            return List.of(in.withBody(out));
        }
    }

    // ------------------------------------------------------------------ claim check

    /** Claim Check: park the body in a store and carry a token; later get it back. */
    public record ClaimCheck(boolean store, long ttlSeconds, String header, boolean consume, boolean dropIfMissing, long maxBytes,
            Services svc) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            ClaimStore claims = svc.claims();
            if (store) {
                String json = Formats.serialize("json", in.body(), Map.of());
                if (json.length() > maxBytes) {
                    throw new EipException("claimCheck: body larger than " + maxBytes + " bytes", null, true);
                }
                byte[] raw = new byte[16];
                RANDOM.nextBytes(raw);
                StringBuilder tok = new StringBuilder();
                for (byte b : raw) {
                    tok.append(String.format("%02x", b));
                }
                claims.put(svc.namespace(), tok.toString(), in.body(), svc.clock().instant().plus(Duration.ofSeconds(ttlSeconds)));
                return List.of(in.withBody(null).withHeader(header, tok.toString()));
            }
            Object t = in.headers().get(header);
            if (!(t instanceof String token) || !token.matches("[0-9a-f]{32}")) {
                throw new EipException("claimCheck: header '" + header + "' does not hold a claim token", null, true);
            }
            Optional<Object> body = claims.get(svc.namespace(), token, svc.clock().instant());
            if (body.isEmpty()) {
                if (dropIfMissing) {
                    return List.of();
                }
                throw new EipException("claimCheck: unknown or expired claim", null, true);
            }
            if (consume) {
                claims.delete(svc.namespace(), token);
            }
            return List.of(in.withBody(body.get()));
        }
    }

    // ------------------------------------------------------------------ control flow

    /** Loop: run a sub-pipeline repeatedly on one message ({@code times}, {@code while}, {@code until}). */
    public record Loop(Integer times, Expression whileExpr, Expression untilExpr, int max, Pipeline body) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            Message cur = in;
            for (int i = 0; i < max; i++) {
                if (times != null && i >= times) {
                    break;
                }
                if (whileExpr != null && !whileExpr.test(cur.withHeader("iteration", BigDecimal.valueOf(i)).scope(scope))) {
                    break;
                }
                List<Message> out = body.run(cur.withHeader("iteration", BigDecimal.valueOf(i)), scope);
                if (out.isEmpty()) {
                    return List.of();
                }
                if (out.size() > 1) {
                    throw new EipException("loop body must produce at most one message");
                }
                cur = out.get(0);
                if (untilExpr != null && untilExpr.test(cur.scope(scope))) {
                    return List.of(cur);
                }
                if (i == max - 1 && (whileExpr != null || untilExpr != null)) {
                    throw new EipException("loop reached maxIterations (" + max + ") without finishing");
                }
            }
            return List.of(cur);
        }
    }

    /** Routing Slip: the message itself says which named stages to visit, in order. */
    public record RoutingSlip(Expression slip, Map<String, Pipeline> stages, boolean skipUnknown) implements Step {
        static final int MAX_HOPS = 20;

        @Override
        public List<Message> apply(Message in, Scope scope) {
            Object v = slip.evaluate(in.scope(scope));
            List<String> names = new ArrayList<>();
            if (v instanceof List<?> l) {
                l.forEach(o -> names.add(String.valueOf(o)));
            } else if (v instanceof String s && !s.isBlank()) {
                for (String p : s.split(",")) {
                    names.add(p.trim());
                }
            } else if (v != null) {
                throw new EipException("routingSlip: the slip must be a list or a comma-separated string");
            }
            if (names.size() > MAX_HOPS) {
                throw new EipException("routingSlip: more than " + MAX_HOPS + " stages");
            }
            List<Message> cur = List.of(in);
            for (String n : names) {
                Pipeline p = stages.get(n);
                if (p == null) {
                    if (skipUnknown) {
                        continue;
                    }
                    throw new EipException("routingSlip: unknown stage '" + n + "'", null, true);
                }
                cur = p.runBatch(cur, scope);
                if (cur.size() > MAX_FANOUT) {
                    throw new EipException("routingSlip produced more than " + MAX_FANOUT + " messages");
                }
            }
            return cur;
        }
    }

    /** Switch / Normalizer: pick a sub-pipeline by the value of an expression. */
    public record Switch(Expression on, Map<String, Pipeline> cases, Pipeline otherwise, Step.NoMatch onNoMatch) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            String k = keyOf(on.evaluate(in.scope(scope)));
            Pipeline p = cases.get(k);
            if (p == null) {
                p = otherwise;
            }
            if (p != null) {
                return p.run(in, scope);
            }
            return switch (onNoMatch) {
                case PASS -> List.of(in);
                case DROP -> List.of();
                case FAIL -> throw new EipException("switch: no case for '" + k + "' and no default");
            };
        }
    }

    /** Load Balancer: spread messages over equivalent targets (round robin, hash of a key, or random), optional failover. */
    public record LoadBalance(String strategy, Expression key, List<String> names, List<Pipeline> targets, boolean failover,
            String stateKey, EipState state) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            int n = targets.size();
            int first = switch (strategy) {
                case "roundRobin" -> (int) (state.<EipState.Counter>get(stateKey, EipState.Counter::new).next() % n);
                case "hash" -> Math.floorMod(keyOf(key.evaluate(in.scope(scope))).hashCode(), n);
                default -> java.util.concurrent.ThreadLocalRandom.current().nextInt(n);
            };
            RuntimeException last = null;
            for (int i = 0; i < (failover ? n : 1); i++) {
                int idx = (first + i) % n;
                try {
                    List<Message> out = targets.get(idx).run(in, scope);
                    List<Message> tagged = new ArrayList<>();
                    out.forEach(m -> tagged.add(m.withHeader("target", names.get(idx))));
                    return tagged;
                } catch (RuntimeException e) {
                    if (!failure(e)) {
                        throw e;
                    }
                    last = e;
                }
            }
            throw new EipException("loadBalance: " + (failover ? "every target failed: " : "") + last.getMessage(), last);
        }
    }

    // ------------------------------------------------------------------ resilience

    /** Retry the body a few times; permanent failures are not retried. */
    public record Retry(Pipeline body, Pipeline handler, int attempts, long backoffMs, String deadLetter, Sleeper sleeper)
            implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            RuntimeException last = null;
            for (int i = 0; i < attempts; i++) {
                try {
                    return body.run(in, scope);
                } catch (RuntimeException e) {
                    if (!failure(e)) {
                        throw e;
                    }
                    last = e;
                    if (e instanceof EipException ee && ee.permanent()) {
                        break;
                    }
                    if (i < attempts - 1 && backoffMs > 0) {
                        try {
                            sleeper.sleep(Math.min(2000, backoffMs << i));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new EipException("retry interrupted", ie);
                        }
                    }
                }
            }
            if (handler == null) {
                throw new EipException(last.getMessage(), last, last instanceof EipException ee && ee.permanent());
            }
            return handler.run(in.withHeader("error", String.valueOf(last.getMessage())).withHeader("routeTo", deadLetter), scope);
        }
    }

    /** Circuit Breaker: after {@code threshold} consecutive failures stop calling for {@code openMs}, then probe once. */
    public record CircuitBreaker(String name, int threshold, long openMs, Pipeline body, Pipeline fallback, EipState state,
            String namespace, Clock clock) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            EipState.Breaker b = state.<EipState.Breaker>get(namespace + "|cb|" + name, EipState.Breaker::new);
            Instant now = clock.instant();
            if (!b.tryAcquire(now)) {
                if (fallback != null) {
                    return fallback.run(in.withHeader("error", "circuit '" + name + "' is open"), scope);
                }
                throw new EipException("circuit '" + name + "' is open");
            }
            try {
                List<Message> out = body.run(in, scope);
                b.success();
                return out;
            } catch (RuntimeException e) {
                if (failure(e)) {
                    b.failure(clock.instant(), threshold, openMs);
                } else {
                    b.success(); // a bug in the pipeline is not the dependency's fault; do not wedge a probe
                }
                if (failure(e) && fallback != null) {
                    return fallback.run(in.withHeader("error", String.valueOf(e.getMessage())), scope);
                }
                throw e;
            }
        }
    }

    /** Throttler: token bucket of {@code perSecond}, {@code burst} deep; excess is rejected, dropped or briefly waited for. */
    public record Throttle(String name, double perSecond, double burst, Mode onExceed, long maxWaitMs, EipState state,
            String namespace, Clock clock, Sleeper sleeper) implements Step {
        public enum Mode {
            FAIL, DROP, WAIT
        }

        @Override
        public List<Message> apply(Message in, Scope scope) {
            EipState.Bucket b = state.<EipState.Bucket>get(namespace + "|th|" + name, EipState.Bucket::new);
            long wait = b.take(clock, perSecond, burst);
            if (wait == 0) {
                return List.of(in);
            }
            switch (onExceed) {
                case DROP -> {
                    return List.of();
                }
                case WAIT -> {
                    if (wait <= maxWaitMs) {
                        try {
                            sleeper.sleep(wait);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new EipException("throttle interrupted", ie);
                        }
                        if (b.take(clock, perSecond, burst) == 0) {
                            return List.of(in);
                        }
                    }
                    throw new EipException("throttle '" + name + "': rate limit exceeded");
                }
                default -> throw new EipException("throttle '" + name + "': rate limit exceeded");
            }
        }
    }

    /** Delayer: a short pause, or just stamp {@code notBefore} for a durable timer downstream. */
    public record Delay(long ms, Expression notBefore, Sleeper sleeper) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            return applyAll(List.of(in), scope);
        }

        @Override
        public List<Message> applyAll(List<Message> batch, Scope scope) {
            if (notBefore != null) {
                List<Message> out = new ArrayList<>();
                for (Message m : batch) {
                    out.add(m.withHeader("notBefore", notBefore.evaluate(m.scope(scope))));
                }
                return out;
            }
            if (ms > 0 && !batch.isEmpty()) {
                try {
                    sleeper.sleep(ms);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new EipException("delay interrupted", ie);
                }
            }
            return batch;
        }
    }

    // ------------------------------------------------------------------ batching, correlation, enrichment

    /** Batch: chunk a batch of messages into lists of at most {@code size} bodies. */
    public record Batch(int size) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            return applyAll(List.of(in), scope);
        }

        @Override
        public List<Message> applyAll(List<Message> batch, Scope scope) {
            List<Message> out = new ArrayList<>();
            for (int i = 0; i < batch.size(); i += size) {
                List<Object> bodies = new ArrayList<>();
                for (Message m : batch.subList(i, Math.min(batch.size(), i + size))) {
                    bodies.add(m.body());
                }
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("batchIndex", BigDecimal.valueOf(i / size));
                h.put("batchSize", BigDecimal.valueOf(bodies.size()));
                out.add(new Message(h, bodies));
            }
            return out;
        }
    }

    /** Correlation Identifier: make sure the message carries one (from a key, else random). */
    public record Correlate(Expression key, boolean force) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            if (in.headers().get("correlationId") != null && !force) {
                return List.of(in);
            }
            Object id = key == null ? UUID.randomUUID().toString() : keyOf(key.evaluate(in.scope(scope)));
            return List.of(in.withHeader("correlationId", id));
        }
    }

    /** Message History: append a label to the {@code history} header (max 100 entries). */
    public record Label(String name) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            return List.of(append(in, "history", name));
        }
    }

    /** Content Enricher from a reference table held in the step. */
    public record Lookup(Map<String, Object> table, Expression key, String into, boolean failIfMissing, Object dflt) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            String k = keyOf(key.evaluate(in.scope(scope)));
            Object v = table.containsKey(k) ? table.get(k) : dflt;
            if (!table.containsKey(k) && dflt == null) {
                if (failIfMissing) {
                    throw new EipException("lookup: no entry for '" + k + "'", null, true);
                }
                return List.of(in);
            }
            Map<String, Object> body = new LinkedHashMap<>(mapBody(in, "lookup"));
            Mapper.setPath(body, into, v);
            return List.of(in.withBody(body));
        }
    }

    /** Splitter for text: cut a string body (or expression) at a literal separator. */
    public record Tokenize(Expression from, String separator, boolean trim, boolean skipEmpty, int limit) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            Object v = from == null ? in.body() : from.evaluate(in.scope(scope));
            if (!(v instanceof String s)) {
                throw new EipException("tokenize needs a text value");
            }
            List<Message> out = new ArrayList<>();
            int idx = 0;
            while (idx <= s.length()) {
                int next = s.indexOf(separator, idx);
                String part = next < 0 ? s.substring(idx) : s.substring(idx, next);
                if (trim) {
                    part = part.strip();
                }
                if (!(skipEmpty && part.isEmpty())) {
                    if (out.size() >= limit) {
                        throw new EipException("tokenize produced more than " + limit + " parts");
                    }
                    out.add(in.withBody(part).withHeader("part", BigDecimal.valueOf(out.size())));
                }
                if (next < 0) {
                    break;
                }
                idx = next + separator.length();
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ durable aggregator

    /**
     * Aggregator across separate runs: each message is added to its group in the {@link AggregationStore}; when the group is
     * complete ({@code size} reached or {@code completeWhen} true) exactly one run emits the aggregate and the rest emit nothing.
     * A group that has been waiting longer than {@code timeoutMs} is closed (emitted with {@code incomplete=true}, or dropped)
     * <b>when the next message of the same key arrives</b>. With {@code sweepTo} a {@link CollectSweeper} also closes it on a schedule.
     */
    public record Collect(Expression key, Integer size, Expression completeWhen, long timeoutMs, boolean emitOnTimeout, int maxGroup,
            Services svc, String sweepTo) implements Step {
        @Override
        @SuppressWarnings("unchecked")
        public List<Message> apply(Message in, Scope scope) {
            String k = keyOf(key.evaluate(in.scope(scope)));
            if (k.isEmpty() || k.length() > 200) {
                throw new EipException("collect: the key must be 1 to 200 characters");
            }
            long now = svc.clock().millis();
            List<AggregationStore.Entry> g = svc.aggregation().append(svc.namespace(), k,
                    Formats.serialize("json", in.toMap(), Map.of()), now, maxGroup);
            List<Object> bodies = new ArrayList<>();
            for (AggregationStore.Entry e : g) {
                Object m = Formats.parse("json", e.json(), Map.of());
                bodies.add(m instanceof Map<?, ?> mm ? mm.get("body") : null);
            }
            boolean complete = size != null && g.size() >= size;
            if (!complete && completeWhen != null) {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("count", BigDecimal.valueOf(g.size()));
                vars.put("bodies", bodies);
                vars.put("last", bodies.get(bodies.size() - 1));
                complete = completeWhen.test(in.scope(scope).child(vars));
            }
            boolean timedOut = !complete && timeoutMs > 0 && now - g.get(0).createdMs() >= timeoutMs;
            if (!complete && !timedOut) {
                return List.of();
            }
            if (!svc.aggregation().claim(svc.namespace(), k, g.get(g.size() - 1).seq())) {
                return List.of(); // another replica closed this group
            }
            if (timedOut && !emitOnTimeout) {
                return List.of();
            }
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("correlationId", k);
            h.put("aggregateSize", BigDecimal.valueOf(bodies.size()));
            if (timedOut) {
                h.put("incomplete", Boolean.TRUE);
            }
            return List.of(new Message(h, bodies));
        }
    }
}
