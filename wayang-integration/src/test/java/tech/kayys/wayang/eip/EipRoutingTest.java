package tech.kayys.wayang.eip;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.expression.Scope;

class EipRoutingTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Pipeline load(Object steps, Invoker inv) {
        return EipLoader.load(map("steps", steps), new IdempotencyStore.InMemory(100), inv);
    }

    private static Pipeline load(Object steps) {
        return load(steps, null);
    }

    @Test
    void fullTranslatorFlowParseMapSerialize() {
        var p = load(List.of(
                map("type", "parse", "format", "json"),
                map("type", "map", "mapping", map("id", "body.order.id", "items", map("$each", "body.order.lines",
                        "$as", "l", "$map", map("sku", "l.sku", "qty", "l.qty"))), "headers", map("kind", map("$const", "order"))),
                map("type", "splitter", "items", "body.items"),
                map("type", "map", "mapping", map("sku", "body.sku", "qty", "body.qty")),
                map("type", "aggregate", "correlation", "1", "size", 2),
                map("type", "serialize", "format", "csv", "from", "body")));
        var out = p.run(Message.of("{\"order\":{\"id\":\"o1\",\"lines\":[{\"sku\":\"a\",\"qty\":1},{\"sku\":\"b\",\"qty\":2}]}}"), Scope.empty());
        assertEquals(1, out.size());
        assertEquals("sku,qty\r\na,1\r\nb,2\r\n", out.get(0).body());
        assertEquals("text/csv", out.get(0).headers().get("contentType"));
    }

    @Test
    void mapMergeKeepsExistingFieldsAndRemoveDropsPaths() {
        var p = load(List.of(map("type", "map", "merge", true, "mapping", map("meta.seen", "true"), "remove", List.of("secret", "a.b"))));
        var out = p.run(Message.of(map("keep", 1, "secret", "s", "a", map("b", 1, "c", 2))), Scope.empty()).get(0);
        Map<?, ?> b = (Map<?, ?>) out.body();
        assertEquals(true, ((Map<?, ?>) b.get("meta")).get("seen"));
        assertFalse(b.containsKey("secret"));
        assertEquals(Map.of("c", java.math.BigDecimal.valueOf(2)).size(), ((Map<?, ?>) b.get("a")).size());
        assertTrue(b.containsKey("keep"));
        var bad = load(List.of(map("type", "map", "merge", true, "mapping", map("x", "1"))));
        assertThrows(EipException.class, () -> bad.run(Message.of("text"), Scope.empty()));
    }

    @Test
    void choiceRunsFirstMatchingBranch() {
        var p = load(List.of(map("type", "choice", "branches", List.of(
                map("when", "body.vip", "steps", List.of(map("type", "map", "headers", map("lane", map("$const", "fast"))))),
                map("when", "body.n > 5", "steps", List.of(map("type", "filter", "when", "false")))),
                "otherwise", List.of(map("type", "map", "headers", map("lane", map("$const", "slow")))))));
        assertEquals("fast", p.run(Message.of(map("vip", true, "n", 9)), Scope.empty()).get(0).headers().get("lane"));
        assertEquals(0, p.run(Message.of(map("vip", false, "n", 9)), Scope.empty()).size());
        assertEquals("slow", p.run(Message.of(map("vip", false, "n", 1)), Scope.empty()).get(0).headers().get("lane"));
    }

    @Test
    void choiceNoMatchPolicy() {
        var br = List.of(map("when", "false", "steps", List.of()));
        assertThrows(EipException.class, () -> load(List.of(map("type", "choice", "branches", br))).run(Message.of(1), Scope.empty()));
        assertEquals(1, load(List.of(map("type", "choice", "branches", br, "onNoMatch", "pass"))).run(Message.of(1), Scope.empty()).size());
        assertEquals(0, load(List.of(map("type", "choice", "branches", br, "onNoMatch", "drop"))).run(Message.of(1), Scope.empty()).size());
        assertThrows(EipException.class, () -> load(List.of(map("type", "choice", "branches", br, "onNoMatch", "x"))));
    }

    @Test
    void multicastEmitsOrGathers() {
        var branches = map("a", List.of(map("type", "map", "mapping", "body * 2")), "b", List.of(map("type", "map", "mapping", "body + 1")));
        var split = load(List.of(map("type", "multicast", "branches", branches))).run(Message.of(10), Scope.empty());
        assertEquals(2, split.size());
        assertEquals("b", split.get(1).headers().get("branch"));
        var g = load(List.of(map("type", "multicast", "gather", true, "branches", branches))).run(Message.of(10), Scope.empty());
        assertEquals(1, g.size());
        assertEquals(2, ((Map<?, ?>) g.get(0).body()).size());
        assertEquals("20", String.valueOf(((List<?>) ((Map<?, ?>) g.get(0).body()).get("a")).get(0)));
    }

    @Test
    void guardSendsFailuresToDeadLetterWithOriginalBody() {
        var p = load(List.of(map("type", "guard", "deadLetter", "dlq",
                "steps", List.of(map("type", "parse", "format", "json")))));
        var ok = p.run(Message.of("{\"a\":1}"), Scope.empty()).get(0);
        assertNull(ok.headers().get("error"));
        var bad = p.run(Message.of("{nope"), Scope.empty()).get(0);
        assertEquals("dlq", bad.headers().get("routeTo"));
        assertEquals("{nope", bad.body());
        assertTrue(String.valueOf(bad.headers().get("error")).contains("Invalid JSON"));
        var handled = load(List.of(map("type", "guard", "steps", List.of(map("type", "parse", "format", "json")),
                "handler", List.of(map("type", "map", "mapping", map("failed", "body")))))).run(Message.of("{"), Scope.empty()).get(0);
        assertEquals("{", ((Map<?, ?>) handled.body()).get("failed"));
        assertEquals("dead-letter", handled.headers().get("routeTo"));
    }

    @Test
    void callEnrichesWithMappedInputAndOutput() {
        List<Object> seen = new ArrayList<>();
        Invoker inv = (name, cfg, in) -> {
            seen.add(name + cfg + in);
            return map("status", 200, "data", map("tier", "gold"));
        };
        var p = load(List.of(map("type", "call", "connector", "crm", "config", map("op", "get"),
                "input", map("id", "body.id"), "output", map("tier", "result.data.tier"), "into", "customer")), inv);
        var out = p.run(Message.of(map("id", "c1")), Scope.empty()).get(0);
        assertEquals("gold", ((Map<?, ?>) ((Map<?, ?>) out.body()).get("customer")).get("tier"));
        assertEquals("c1", ((Map<?, ?>) out.body()).get("id"));
        assertEquals("crm{op=get}{id=c1}", seen.get(0));
    }

    @Test
    void callTapLeavesMessageAloneAndFailuresAreEipErrors() {
        List<String> calls = new ArrayList<>();
        var tap = load(List.of(map("type", "call", "connector", "audit", "tap", true, "input", map("x", "body"))),
                (n, c, i) -> {
                    calls.add(n);
                    return Map.of("ignored", true);
                });
        assertEquals(7, ((Number) tap.run(Message.of(7), Scope.empty()).get(0).body()).intValue());
        assertEquals(List.of("audit"), calls);
        var boom = load(List.of(map("type", "call", "connector", "x")), (n, c, i) -> {
            throw new java.io.IOException("down");
        });
        assertThrows(EipException.class, () -> boom.run(Message.of(1), Scope.empty()));
        assertThrows(EipException.class, () -> load(List.of(map("type", "call", "connector", "x"))));
        var guarded = load(List.of(map("type", "guard", "steps", List.of(map("type", "call", "connector", "x")))), (n, c, i) -> {
            throw new java.io.IOException("down");
        });
        assertEquals("dead-letter", guarded.run(Message.of(1), Scope.empty()).get(0).headers().get("routeTo"));
    }

    @Test
    void aggregateIncompletePolicies() {
        var in = List.of(Message.of(map("k", "a")), Message.of(map("k", "b")), Message.of(map("k", "a")));
        Pipeline fail = load(List.of(map("type", "aggregate", "correlation", "body.k", "size", 2)));
        assertThrows(EipException.class, () -> fail.runBatch(in, Scope.empty()));
        assertEquals(1, load(List.of(map("type", "aggregate", "correlation", "body.k", "size", 2, "incomplete", "drop")))
                .runBatch(in, Scope.empty()).size());
        var emit = load(List.of(map("type", "aggregate", "correlation", "body.k", "size", 2, "incomplete", "emit")))
                .runBatch(in, Scope.empty());
        assertEquals(2, emit.size());
        assertEquals(true, emit.get(1).headers().get("incomplete"));
    }

    @Test
    void resequenceWorksAsPipelineStep() {
        var out = load(List.of(map("type", "resequence", "by", "body"))).runBatch(
                List.of(Message.of(3), Message.of(1), Message.of(2)), Scope.empty());
        assertEquals("1", String.valueOf(out.get(0).body()));
        assertEquals("3", String.valueOf(out.get(2).body()));
    }

    @Test
    void nestingAndSizeLimits() {
        Object steps = List.of();
        for (int i = 0; i < 10; i++) {
            steps = List.of(map("type", "guard", "steps", steps));
        }
        Object s = steps;
        assertThrows(EipException.class, () -> load(s));
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(map("type", "filter", "when", "true"));
        }
        assertThrows(EipException.class, () -> load(many));
    }

    @Test
    void loaderRejectsMalformedNewSteps() {
        for (Object bad : List.of(
                map("type", "map"),
                map("type", "map", "mapping", map("a", "b ==")),
                map("type", "parse", "format", "yaml"),
                map("type", "choice", "branches", List.of()),
                map("type", "multicast", "branches", map()),
                map("type", "aggregate", "correlation", "body", "size", "x"),
                map("type", "aggregate", "correlation", "body", "size", 0),
                map("type", "map", "mapping", "1", "remove", "a"))) {
            assertThrows(EipException.class, () -> load(List.of(bad)), String.valueOf(bad));
        }
    }
}
