package tech.kayys.wayang.eip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.expression.Expression;
import tech.kayys.wayang.expression.ExpressionException;
import tech.kayys.wayang.expression.Expressions;
import tech.kayys.wayang.expression.Scope;

/**
 * A single Enterprise Integration Pattern applied to one message, producing
 * zero (filter), one (transform/route) or many (splitter) messages. Steps are
 * pure functions of message + scope, except {@link Idempotent} which consults
 * its store.
 */
public sealed interface Step permits Step.Filter, Step.Transform, Step.Splitter, Step.Router, Step.RecipientList, Step.Idempotent, Step.Resequence, Step.MapBody, Step.Parse, Step.Serialize, Step.Choice, Step.Multicast, Step.Guard, Step.Call, Step.Aggregate, Patterns.Validate, Patterns.ContentFilter, Patterns.Envelope, Patterns.ClaimCheck, Patterns.Loop, Patterns.RoutingSlip, Patterns.Switch, Patterns.LoadBalance, Patterns.Retry, Patterns.CircuitBreaker, Patterns.Throttle, Patterns.Delay, Patterns.Batch, Patterns.Correlate, Patterns.Label, Patterns.Lookup, Patterns.Tokenize, Patterns.Collect {

    /** Hard cap on fan-out, so an expression cannot explode memory. */
    int MAX_FANOUT = 10_000;

    List<Message> apply(Message in, Scope scope);

    /** Applies the step to a whole batch; batch-level steps (aggregate, resequence) override this. */
    default List<Message> applyAll(List<Message> batch, Scope scope) {
        List<Message> out = new ArrayList<>();
        for (Message m : batch) {
            out.addAll(apply(m, scope));
            if (out.size() > MAX_FANOUT) {
                throw new EipException("Step produced more than " + MAX_FANOUT + " messages");
            }
        }
        return out;
    }

    private static Expression compile(String what, String src) {
        try {
            return Expressions.parse(src);
        } catch (ExpressionException e) {
            throw new EipException(what + " '" + src + "': " + e.getMessage(), e);
        }
    }

    /** Message Filter: keeps the message only when the predicate is true. */
    record Filter(String predicate, Expression compiled) implements Step {
        public Filter(String predicate) {
            this(predicate, compile("filter", predicate));
        }

        @Override
        public List<Message> apply(Message in, Scope scope) {
            return compiled.test(in.scope(scope)) ? List.of(in) : List.of();
        }
    }

    /** Message Translator / Content Enricher: replaces the body and/or sets headers using expressions. */
    record Transform(String body, Map<String, String> headers, Expression bodyExpr, Map<String, Expression> headerExprs)
            implements Step {
        public Transform(String body, Map<String, String> headers) {
            this(body, headers, body == null ? null : compile("transform body", body), compileAll(headers));
        }

        private static Map<String, Expression> compileAll(Map<String, String> h) {
            Map<String, Expression> out = new LinkedHashMap<>();
            h.forEach((k, v) -> out.put(k, compile("header " + k, v)));
            return out;
        }

        @Override
        public List<Message> apply(Message in, Scope scope) {
            Scope s = in.scope(scope);
            Message m = in;
            for (Map.Entry<String, Expression> e : headerExprs.entrySet()) {
                m = m.withHeader(e.getKey(), e.getValue().evaluate(s));
            }
            if (bodyExpr != null) {
                m = m.withBody(bodyExpr.evaluate(s));
            }
            return List.of(m);
        }
    }

    /** Splitter: one message per element of the list the expression yields. */
    record Splitter(String items, Expression compiled) implements Step {
        public Splitter(String items) {
            this(items, compile("splitter", items));
        }

        @Override
        public List<Message> apply(Message in, Scope scope) {
            Object v = compiled.evaluate(in.scope(scope));
            if (!(v instanceof List<?> l)) {
                throw new EipException("Splitter expression '" + items + "' must yield a list but yielded "
                        + (v == null ? "null" : v.getClass().getSimpleName()));
            }
            if (l.size() > MAX_FANOUT) {
                throw new EipException("Splitter produced " + l.size() + " messages, limit is " + MAX_FANOUT);
            }
            List<Message> out = new ArrayList<>();
            int i = 0;
            for (Object o : l) {
                out.add(in.withBody(o).withHeader("sequenceNumber", java.math.BigDecimal.valueOf(i++))
                        .withHeader("sequenceSize", java.math.BigDecimal.valueOf(l.size())));
            }
            return out;
        }
    }

    /**
     * Content-Based Router: sets header {@code routeTo} to the channel of the
     * first matching route (or {@code otherwise}); fails when nothing matches
     * and there is no {@code otherwise}.
     */
    record Router(List<Route> routes, String otherwise) implements Step {
        public record Route(String when, String to, Expression compiled) {
            public Route(String when, String to) {
                this(when, to, compile("route", when));
            }
        }

        @Override
        public List<Message> apply(Message in, Scope scope) {
            Scope s = in.scope(scope);
            for (Route r : routes) {
                if (r.compiled().test(s)) {
                    return List.of(in.withHeader("routeTo", r.to()));
                }
            }
            if (otherwise == null) {
                throw new EipException("Router: no route matched and no 'otherwise' channel configured");
            }
            return List.of(in.withHeader("routeTo", otherwise));
        }
    }

    /** Recipient List: header {@code recipients} lists every channel whose predicate holds (message is not duplicated). */
    record RecipientList(List<Router.Route> routes) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            Scope s = in.scope(scope);
            List<Object> to = new ArrayList<>();
            for (Router.Route r : routes) {
                if (r.compiled().test(s)) {
                    to.add(r.to());
                }
            }
            return List.of(in.withHeader("recipients", to));
        }
    }

    /** Idempotent Receiver: drops messages whose key was already processed. */
    record Idempotent(String key, Expression compiled, IdempotencyStore store) implements Step {
        public Idempotent(String key, IdempotencyStore store) {
            this(key, compile("idempotency key", key), store);
        }

        @Override
        public List<Message> apply(Message in, Scope scope) {
            Object k = compiled.evaluate(in.scope(scope));
            if (k == null) {
                throw new EipException("Idempotency key '" + key + "' evaluated to null");
            }
            return store.markIfNew(String.valueOf(k)) ? List.of(in) : List.of();
        }
    }

    /** Resequencer (batch form): sorts a batch by the expression. Applied via {@link Pipeline#runBatch}. */
    record Resequence(String by, Expression compiled) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            return List.of(in);
        }

        @Override
        public List<Message> applyAll(List<Message> batch, Scope scope) {
            return sort(batch, scope);
        }

        public Resequence(String by) {
            this(by, compile("resequence", by));
        }

        public List<Message> sort(List<Message> batch, Scope scope) {
            List<Message> out = new ArrayList<>(batch);
            try {
                out.sort((a, b) -> {
                    Integer c = tech.kayys.wayang.expression.Values.compare(
                            compiled.evaluate(a.scope(scope)), compiled.evaluate(b.scope(scope)));
                    if (c == null) {
                        throw new EipException("Resequencer keys are not comparable");
                    }
                    return c;
                });
            } catch (IllegalArgumentException e) {
                throw new EipException("Resequencer keys are inconsistent: " + e.getMessage(), e);
            }
            return out;
        }
    }

    /**
     * Mapper step: builds a new body (and optionally headers) from a declarative {@link Mapper} spec.
     * {@code merge} deep-merges the mapped object into the existing object body instead of replacing it.
     */
    record MapBody(Mapper body, boolean merge, Map<String, Mapper> headers, List<String> remove) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            Scope s = in.scope(scope);
            Message m = in;
            for (Map.Entry<String, Mapper> e : headers.entrySet()) {
                m = m.withHeader(e.getKey(), e.getValue().apply(s));
            }
            if (body != null) {
                Object v = body.apply(s);
                if (merge) {
                    if (!(in.body() instanceof Map) || !(v instanceof Map)) {
                        throw new EipException("map with merge needs an object body and an object mapping");
                    }
                    v = Mapper.deepMerge(in.body(), v);
                }
                m = m.withBody(v);
            }
            Object b = m.body();
            for (String r : remove) {
                b = Mapper.removePath(b, r);
            }
            return List.of(m.withBody(b));
        }
    }

    /** Parses a text body (JSON, CSV, XML) into structured data. */
    record Parse(String format, Expression from, Map<String, Object> opts) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            Object src = from == null ? in.body() : from.evaluate(in.scope(scope));
            if (!(src instanceof String t)) {
                throw new EipException("parse " + format + " needs a text body but got "
                        + (src == null ? "null" : src.getClass().getSimpleName()));
            }
            return List.of(in.withBody(Formats.parse(format, t, opts)));
        }
    }

    /** Serializes structured data to text (JSON, CSV, XML) and sets header {@code contentType}. */
    record Serialize(String format, Expression from, Map<String, Object> opts) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            Object src = from == null ? in.body() : from.evaluate(in.scope(scope));
            return List.of(in.withBody(Formats.serialize(format, src, opts)).withHeader("contentType",
                    Formats.contentType(format)));
        }
    }

    /** What a {@link Choice} does when no branch matches. */
    enum NoMatch {
        FAIL, PASS, DROP
    }

    /** Content-based router with inline branches: the first matching branch's sub-pipeline processes the message. */
    record Choice(List<Branch> branches, Pipeline otherwise, NoMatch onNoMatch) implements Step {
        public record Branch(String when, Expression compiled, Pipeline steps) {
            public Branch(String when, Pipeline steps) {
                this(when, compile("choice", when), steps);
            }
        }

        @Override
        public List<Message> apply(Message in, Scope scope) {
            Scope s = in.scope(scope);
            for (Branch b : branches) {
                if (b.compiled().test(s)) {
                    return b.steps().run(in, scope);
                }
            }
            if (otherwise != null) {
                return otherwise.run(in, scope);
            }
            return switch (onNoMatch) {
                case PASS -> List.of(in);
                case DROP -> List.of();
                case FAIL -> throw new EipException("Choice: no branch matched and no 'otherwise' configured");
            };
        }
    }

    /**
     * Multicast / scatter-gather. Every branch receives the same message. {@code gather=false} emits all branch
     * outputs; {@code gather=true} emits one message whose body maps branch name to its output bodies.
     * Branches run in order; durable parallelism is the engine's job.
     */
    record Multicast(Map<String, Pipeline> branches, boolean gather, boolean parallel, long timeoutMs) implements Step {
        /** Branches run concurrently at most this many at a time. */
        public static final int MAX_PARALLEL = 16;

        public Multicast(Map<String, Pipeline> branches, boolean gather) {
            this(branches, gather, false, 0);
        }

        @Override
        public List<Message> apply(Message in, Scope scope) {
            List<Message> flat = new ArrayList<>();
            Map<String, Object> gathered = new LinkedHashMap<>();
            Map<String, List<Message>> results = parallel ? runParallel(in, scope) : null;
            for (Map.Entry<String, Pipeline> b : branches.entrySet()) {
                List<Message> out = results != null ? results.get(b.getKey()) : b.getValue().run(in, scope);
                if (gather) {
                    List<Object> bodies = new ArrayList<>();
                    out.forEach(m -> bodies.add(m.body()));
                    gathered.put(b.getKey(), bodies);
                } else {
                    for (Message m : out) {
                        flat.add(m.withHeader("branch", b.getKey()));
                    }
                }
                if (flat.size() > MAX_FANOUT) {
                    throw new EipException("Multicast produced more than " + MAX_FANOUT + " messages");
                }
            }
            return gather ? List.of(in.withBody(gathered)) : flat;
        }

        /**
         * Runs every branch on its own virtual thread and waits for all of them. Results are returned in declaration order, so the
         * output is the same as a sequential run. If any branch fails the others are interrupted and the first failure (in
         * declaration order) is thrown; a branch that does not finish within {@code timeoutMs} fails the step.
         */
        private Map<String, List<Message>> runParallel(Message in, Scope scope) {
            if (branches.size() > MAX_PARALLEL) {
                throw new EipException("parallel multicast allows at most " + MAX_PARALLEL + " branches");
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs > 0 ? timeoutMs : 30_000);
            Map<String, java.util.concurrent.Future<List<Message>>> futures = new LinkedHashMap<>();
            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                branches.forEach((k, p) -> futures.put(k, pool.submit(() -> p.run(in, scope))));
                Map<String, List<Message>> out = new LinkedHashMap<>();
                RuntimeException failure = null;
                for (var e : futures.entrySet()) {
                    try {
                        long left = Math.max(1, deadline - System.nanoTime());
                        out.put(e.getKey(), e.getValue().get(left, java.util.concurrent.TimeUnit.NANOSECONDS));
                    } catch (java.util.concurrent.TimeoutException t) {
                        failure = failure != null ? failure : new EipException("multicast branch '" + e.getKey() + "' timed out");
                        futures.values().forEach(f -> f.cancel(true));
                    } catch (java.util.concurrent.ExecutionException x) {
                        Throwable c = x.getCause();
                        failure = failure != null ? failure : c instanceof RuntimeException r ? r : new EipException("multicast branch '" + e.getKey() + "' failed");
                        futures.values().forEach(f -> f.cancel(true));
                    } catch (InterruptedException i) {
                        Thread.currentThread().interrupt();
                        futures.values().forEach(f -> f.cancel(true));
                        throw new EipException("multicast interrupted");
                    } catch (java.util.concurrent.CancellationException c) {
                        // cancelled because another branch already failed
                    }
                }
                if (failure != null) {
                    throw failure;
                }
                return out;
            }
        }
    }

    /**
     * Error handling / dead-letter channel. Runs {@code body}; if it fails the original message is emitted
     * (or run through {@code handler}) with headers {@code error}, {@code errorStep} and {@code routeTo}.
     */
    record Guard(Pipeline body, Pipeline handler, String deadLetter) implements Step {
        @Override
        public List<Message> apply(Message in, Scope scope) {
            try {
                return body.run(in, scope);
            } catch (EipException | ExpressionException e) {
                Message failed = in.withHeader("error", String.valueOf(e.getMessage())).withHeader("routeTo", deadLetter);
                return handler == null ? List.of(failed) : handler.run(failed, scope);
            }
        }
    }

    /**
     * Outbound call through the runtime {@link Invoker} (connector, MCP tool): content enricher or, with
     * {@code tap}, wire tap. Input and output go through mappers.
     */
    record Call(String target, Map<String, Object> config, Mapper input, Mapper output, String into, boolean tap,
            Invoker invoker) implements Step {
        @Override
        @SuppressWarnings("unchecked")
        public List<Message> apply(Message in, Scope scope) {
            Scope s = in.scope(scope);
            Map<String, Object> args = new LinkedHashMap<>();
            if (input != null) {
                Object v = input.apply(s);
                if (!(v instanceof Map<?, ?> m)) {
                    throw new EipException("call '" + target + "': input mapping must produce an object");
                }
                m.forEach((k, x) -> args.put(String.valueOf(k), x));
            }
            Map<String, Object> res;
            try {
                res = invoker.invoke(target, config, args);
            } catch (Exception e) {
                throw new EipException("call '" + target + "' failed: " + e.getMessage(), e);
            }
            if (res == null) {
                res = Map.of();
            }
            if (tap) {
                return List.of(in);
            }
            Object v = res;
            if (output != null) {
                v = output.apply(s.child("result", res));
            }
            if (into == null) {
                return List.of(in.withBody(v));
            }
            if (in.body() != null && !(in.body() instanceof Map)) {
                throw new EipException("call 'into' needs an object body");
            }
            Map<String, Object> b = new LinkedHashMap<>();
            if (in.body() instanceof Map<?, ?> om) {
                om.forEach((k, x) -> b.put(String.valueOf(k), x));
            }
            Mapper.setPath(b, into, v);
            return List.of(in.withBody(b));
        }
    }

    /** Aggregator as a pipeline step: groups the whole batch by correlation key. */
    record Aggregate(Aggregator aggregator, Incomplete incomplete) implements Step {
        public enum Incomplete {
            FAIL, DROP, EMIT
        }

        @Override
        public List<Message> apply(Message in, Scope scope) {
            return applyAll(List.of(in), scope);
        }

        @Override
        public List<Message> applyAll(List<Message> batch, Scope scope) {
            Aggregator.Result r = aggregator.aggregate(batch, scope);
            List<Message> out = new ArrayList<>(r.complete());
            if (!r.incomplete().isEmpty()) {
                switch (incomplete) {
                    case FAIL -> throw new EipException(r.incomplete().size() + " aggregate group(s) incomplete");
                    case DROP -> {
                    }
                    case EMIT -> {
                        for (List<Message> g : r.incomplete()) {
                            List<Object> bodies = new ArrayList<>();
                            g.forEach(m -> bodies.add(m.body()));
                            out.add(new Message(Map.of("aggregateSize", java.math.BigDecimal.valueOf(bodies.size()),
                                    "incomplete", Boolean.TRUE), bodies));
                        }
                    }
                }
            }
            return out;
        }
    }
}
