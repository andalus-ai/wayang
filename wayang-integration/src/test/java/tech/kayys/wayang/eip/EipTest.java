package tech.kayys.wayang.eip;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.expression.Scope;

class EipTest {

    @Test
    void filterDropsNonMatching() {
        var p = new Pipeline(List.of(new Step.Filter("body.amount > 10")), Pipeline.Delivery.AT_LEAST_ONCE);
        assertEquals(1, p.run(Message.of(Map.of("amount", 50)), Scope.empty()).size());
        assertEquals(0, p.run(Message.of(Map.of("amount", 5)), Scope.empty()).size());
    }

    @Test
    void splitterFansOutWithSequenceHeaders() {
        var p = new Pipeline(List.of(new Step.Splitter("body.lines")), Pipeline.Delivery.AT_LEAST_ONCE);
        var out = p.run(Message.of(Map.of("lines", List.of("a", "b", "c"))), Scope.empty());
        assertEquals(3, out.size());
        assertEquals("b", out.get(1).body());
        assertEquals(new BigDecimal(3), out.get(1).headers().get("sequenceSize"));
    }

    @Test
    void splitterRejectsNonList() {
        var p = new Pipeline(List.of(new Step.Splitter("body.x")), Pipeline.Delivery.AT_LEAST_ONCE);
        assertThrows(EipException.class, () -> p.run(Message.of(Map.of("x", 1)), Scope.empty()));
    }

    @Test
    void routerPicksFirstMatchOrOtherwise() {
        var r = new Step.Router(List.of(new Step.Router.Route("body.vip", "priority")), "standard");
        assertEquals("priority", r.apply(Message.of(Map.of("vip", true)), Scope.empty()).get(0).headers().get("routeTo"));
        assertEquals("standard", r.apply(Message.of(Map.of("vip", false)), Scope.empty()).get(0).headers().get("routeTo"));
        var strict = new Step.Router(List.of(new Step.Router.Route("body.vip", "p")), null);
        assertThrows(EipException.class, () -> strict.apply(Message.of(Map.of("vip", false)), Scope.empty()));
    }

    @Test
    void recipientListCollectsAll() {
        var r = new Step.RecipientList(List.of(new Step.Router.Route("body.a", "x"), new Step.Router.Route("body.b", "y"),
                new Step.Router.Route("body.c", "z")));
        var out = r.apply(Message.of(Map.of("a", true, "b", true, "c", false)), Scope.empty()).get(0);
        assertEquals(List.of("x", "y"), out.headers().get("recipients"));
    }

    @Test
    void transformSetsBodyAndHeaders() {
        var t = new Step.Transform("{total: body.price * body.qty}", Map.of("kind", "\"order\""));
        var m = t.apply(Message.of(Map.of("price", 3, "qty", 4)), Scope.empty()).get(0);
        assertEquals("order", m.headers().get("kind"));
        assertEquals(0, new BigDecimal(12).compareTo((BigDecimal) ((Map<?, ?>) m.body()).get("total")));
    }

    @Test
    void idempotentReceiverDropsDuplicates() {
        var p = new Pipeline(List.of(new Step.Idempotent("body.id", new IdempotencyStore.InMemory(100))),
                Pipeline.Delivery.EFFECTIVELY_ONCE);
        assertEquals(1, p.run(Message.of(Map.of("id", "m1")), Scope.empty()).size());
        assertEquals(0, p.run(Message.of(Map.of("id", "m1")), Scope.empty()).size());
        assertEquals(1, p.run(Message.of(Map.of("id", "m2")), Scope.empty()).size());
    }

    @Test
    void effectivelyOnceWithoutIdempotentStepIsRejected() {
        assertThrows(EipException.class, () -> new Pipeline(List.of(), Pipeline.Delivery.EFFECTIVELY_ONCE));
    }

    @Test
    void aggregatorGroupsAndReportsIncomplete() {
        var agg = new Aggregator("body.order", 2);
        var batch = List.of(Message.of(Map.of("order", "A", "n", 1)), Message.of(Map.of("order", "B", "n", 1)),
                Message.of(Map.of("order", "A", "n", 2)));
        var res = agg.aggregate(batch, Scope.empty());
        assertEquals(1, res.complete().size());
        assertEquals("A", res.complete().get(0).headers().get("correlationId"));
        assertEquals(2, ((List<?>) res.complete().get(0).body()).size());
        assertEquals(1, res.incomplete().size());
    }

    @Test
    void resequencerSorts() {
        var r = new Step.Resequence("body.n");
        var out = r.sort(List.of(Message.of(Map.of("n", 3)), Message.of(Map.of("n", 1)), Message.of(Map.of("n", 2))), Scope.empty());
        assertEquals("1", String.valueOf(((Map<?, ?>) out.get(0).body()).get("n")));
        assertEquals("3", String.valueOf(((Map<?, ?>) out.get(2).body()).get("n")));
    }

    @Test
    void loadsFromConfig() {
        var cfg = Map.<String, Object>of("delivery", "AT_LEAST_ONCE", "steps", List.of(
                Map.of("type", "filter", "when", "body.amount > 0"),
                Map.of("type", "splitter", "items", "body.lines"),
                Map.of("type", "router", "routes", List.of(Map.of("when", "body > 1", "to", "big")), "otherwise", "small")));
        var p = EipLoader.load(cfg, null);
        var out = p.run(Message.of(Map.of("amount", 1, "lines", List.of(1, 5))), Scope.empty());
        assertEquals("small", out.get(0).headers().get("routeTo"));
        assertEquals("big", out.get(1).headers().get("routeTo"));
        assertThrows(EipException.class, () -> EipLoader.load(Map.of("steps", List.of(Map.of("type", "nope"))), null));
    }

    @Test
    void invalidExpressionFailsAtConstruction() {
        assertThrows(EipException.class, () -> new Step.Filter("body >"));
    }
}
