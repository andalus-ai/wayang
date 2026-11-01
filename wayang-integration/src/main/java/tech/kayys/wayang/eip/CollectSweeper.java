package tech.kayys.wayang.eip;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Hard deadlines for {@code collect}. Without it a group is only closed when another message of its key arrives. A collect step
 * with {@code sweepTo: <processId>} and {@code timeoutSeconds} is swept on a schedule instead: a group whose oldest message is
 * older than the timeout is closed (one atomic {@link AggregationStore#claim}, so with several replicas exactly one wins) and the
 * process {@code sweepTo} is <b>started</b> with {@code {aggregate: {key, bodies, size, incomplete: true}}}.
 *
 * <p>It starts a new run rather than resuming the one that was waiting at the collect node: those runs finished when they added
 * their message, so there is nothing to resume. If starting fails the group is put back, so it is retried on the next sweep and
 * never lost. Delivery is therefore at-least-once: a crash between the claim and the start loses the group (an in-memory store loses
 * it on any restart anyway).
 */
public final class CollectSweeper {

    private static final Pattern PROCESS_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int BATCH = 100;

    /** A collect node that is swept. */
    public record Target(String tenant, String process, String node, long timeoutMs, String sweepTo) {
        public String namespace() {
            return tenant + "|" + process + "|" + node;
        }
    }

    /** Finds the swept collect of a node, or null when it has none (or the process no longer exists). */
    public interface Resolver {
        Target resolve(String tenant, String process, String node);
    }

    /** Starts a run of {@code processId} for {@code tenant}; throws when it cannot. */
    public interface Starter {
        void start(String tenant, String processId, Map<String, Object> input);
    }

    private final AggregationStore store;
    private final Resolver resolver;
    private final Starter starter;
    private final Clock clock;
    private final Consumer<String> onError;

    public CollectSweeper(AggregationStore store, Resolver resolver, Starter starter, Clock clock, Consumer<String> onError) {
        this.store = store;
        this.resolver = resolver;
        this.starter = starter;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.onError = onError == null ? m -> { } : onError;
    }

    /** @return how many groups were closed and handed to their process */
    public int sweep() {
        int swept = 0;
        for (String ns : store.namespaces()) {
            int last = ns.lastIndexOf('|');
            int prev = last <= 0 ? -1 : ns.lastIndexOf('|', last - 1);
            if (prev < 0) {
                continue; // not a tenant|process|node namespace
            }
            Target t;
            try {
                t = resolver.resolve(ns.substring(0, prev), ns.substring(prev + 1, last), ns.substring(last + 1));
            } catch (RuntimeException e) {
                onError.accept("collect sweep: cannot resolve " + ns + ": " + e.getClass().getSimpleName());
                continue;
            }
            if (t == null || !t.namespace().equals(ns)) {
                continue;
            }
            for (AggregationStore.Group g : store.expired(ns, clock.millis() - t.timeoutMs(), BATCH)) {
                swept += close(t, g) ? 1 : 0;
            }
        }
        return swept;
    }

    private boolean close(Target t, AggregationStore.Group g) {
        List<AggregationStore.Entry> es = g.entries();
        if (!store.claim(t.namespace(), g.key(), es.get(es.size() - 1).seq())) {
            return false; // another replica got it
        }
        try {
            List<Object> bodies = new ArrayList<>();
            for (AggregationStore.Entry e : es) {
                Object m = Formats.parse("json", e.json(), Map.of());
                bodies.add(m instanceof Map<?, ?> mm ? mm.get("body") : null);
            }
            Map<String, Object> agg = new LinkedHashMap<>();
            agg.put("key", g.key());
            agg.put("bodies", bodies);
            agg.put("size", (long) bodies.size());
            agg.put("incomplete", Boolean.TRUE);
            starter.start(t.tenant(), t.sweepTo(), Map.of("aggregate", agg));
            return true;
        } catch (RuntimeException e) {
            for (AggregationStore.Entry en : es) {
                store.append(t.namespace(), g.key(), en.json(), en.createdMs(), 10_000); // put it back: retried next sweep
            }
            onError.accept("collect sweep: " + t.sweepTo() + " did not start for group of " + t.node() + ": " + e.getClass().getSimpleName());
            return false;
        }
    }

    // ------------------------------------------------------------------ reading the definition

    /** The validated {@code sweepTo} of a collect step, or null. */
    public static String sweepTo(Map<?, ?> step) {
        Object v = step.get("sweepTo");
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s) || !PROCESS_ID.matcher(s).matches()) {
            throw new EipException("collect 'sweepTo' must be a process id");
        }
        return s;
    }

    /** Every {@code sweepTo} named by a collect step anywhere inside {@code o} (a process document, an element, a pipeline). */
    public static java.util.Set<String> sweepTargets(Object o) {
        return sweepTargets(o, new java.util.TreeSet<>());
    }

    private static java.util.Set<String> sweepTargets(Object o, java.util.Set<String> into) {
        if (o instanceof Map<?, ?> m) {
            if ("collect".equals(m.get("type")) && m.get("sweepTo") instanceof String t) {
                into.add(t);
            }
            m.values().forEach(v -> sweepTargets(v, into));
        } else if (o instanceof List<?> l) {
            l.forEach(v -> sweepTargets(v, into));
        }
        return into;
    }

    /** The swept collect step of {@code node} in a process document ({@code elements[].config.pipeline}), or null. */
    public static Target find(String tenant, Map<?, ?> processDoc, String node) {
        if (processDoc == null || !(processDoc.get("elements") instanceof List<?> els) || !(processDoc.get("id") instanceof String pid)) {
            return null;
        }
        for (Object o : els) {
            if (o instanceof Map<?, ?> e && node.equals(e.get("id")) && e.get("config") instanceof Map<?, ?> cfg && cfg.get("pipeline") != null) {
                return step(cfg.get("pipeline"), tenant, pid, node);
            }
        }
        return null;
    }

    private static Target step(Object o, String tenant, String pid, String node) {
        if (o instanceof Map<?, ?> m) {
            if ("collect".equals(m.get("type")) && m.get("sweepTo") instanceof String s && PROCESS_ID.matcher(s).matches()
                    && m.get("timeoutSeconds") instanceof Number n && n.longValue() > 0) {
                return new Target(tenant, pid, node, n.longValue() * 1000, s);
            }
            for (Object v : m.values()) {
                Target t = step(v, tenant, pid, node);
                if (t != null) {
                    return t;
                }
            }
        } else if (o instanceof List<?> l) {
            for (Object v : l) {
                Target t = step(v, tenant, pid, node);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }
}
