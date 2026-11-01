package tech.kayys.wayang.dmn;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DmnTest {

    private static DecisionTable risk(HitPolicy hp) {
        return DecisionTable.builder("risk").hitPolicy(hp)
                .input("age").input("income").output("risk")
                .rule(List.of("< 25", "-"), List.of("\"high\""))
                .rule(List.of("[25..60]", "< 20000"), List.of("\"medium\""))
                .rule(List.of("[25..60]", ">= 20000"), List.of("\"low\""))
                .build();
    }

    @Test
    void uniqueHit() {
        var r = risk(HitPolicy.UNIQUE).evaluate(Map.of("age", 30, "income", 50000));
        assertEquals("low", r.value());
        assertEquals(List.of(3), r.matchedRules());
    }

    @Test
    void noMatchIsNullOrDefault() {
        assertNull(risk(HitPolicy.UNIQUE).evaluate(Map.of("age", 70, "income", 1)).value());
        var t = DecisionTable.builder("d").input("x").output("o").defaultOutput("\"none\"")
                .rule(List.of("1"), List.of("\"one\"")).build();
        assertEquals("none", t.evaluate(Map.of("x", 2)).value());
    }

    @Test
    void uniqueViolationThrows() {
        var t = DecisionTable.builder("d").input("x").output("o")
                .rule(List.of("> 0"), List.of("1")).rule(List.of("> 1"), List.of("2")).build();
        assertThrows(DmnException.class, () -> t.evaluate(Map.of("x", 5)));
    }

    @Test
    void firstPolicyStopsAtFirstMatch() {
        var t = DecisionTable.builder("d").hitPolicy(HitPolicy.FIRST).input("x").output("o")
                .rule(List.of("> 0"), List.of("\"a\"")).rule(List.of("> 1"), List.of("\"b\"")).build();
        var r = t.evaluate(Map.of("x", 5));
        assertEquals("a", r.value());
        assertEquals(List.of(1), r.matchedRules());
    }

    @Test
    void anyPolicyRequiresAgreement() {
        var ok = DecisionTable.builder("d").hitPolicy(HitPolicy.ANY).input("x").output("o")
                .rule(List.of("> 0"), List.of("1")).rule(List.of("> 1"), List.of("1")).build();
        assertEquals(new BigDecimal("1"), ok.evaluate(Map.of("x", 5)).value());
        var bad = DecisionTable.builder("d").hitPolicy(HitPolicy.ANY).input("x").output("o")
                .rule(List.of("> 0"), List.of("1")).rule(List.of("> 1"), List.of("2")).build();
        assertThrows(DmnException.class, () -> bad.evaluate(Map.of("x", 5)));
    }

    @Test
    void collectAndAggregate() {
        var t = DecisionTable.builder("d").hitPolicy(HitPolicy.COLLECT).aggregator(HitPolicy.Aggregator.SUM)
                .input("x").output("fee")
                .rule(List.of("> 0"), List.of("10")).rule(List.of("> 5"), List.of("5")).rule(List.of("> 100"), List.of("1"))
                .build();
        assertEquals(0, new BigDecimal("15").compareTo((BigDecimal) t.evaluate(Map.of("x", 6)).value()));
        var l = DecisionTable.builder("d").hitPolicy(HitPolicy.COLLECT).input("x").output("o")
                .rule(List.of("> 0"), List.of("\"a\"")).rule(List.of("> 5"), List.of("\"b\"")).build();
        assertEquals(List.of("a", "b"), l.evaluate(Map.of("x", 6)).value());
    }

    @Test
    void multipleOutputsYieldContext() {
        var t = DecisionTable.builder("d").input("x").output("a").output("b")
                .rule(List.of("1"), List.of("\"A\"", "x + 1")).build();
        Map<?, ?> m = (Map<?, ?>) t.evaluate(Map.of("x", 1)).value();
        assertEquals("A", m.get("a"));
        assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) m.get("b")));
    }

    @Test
    void invalidEntriesFailAtBuildTime() {
        assertThrows(DmnException.class, () -> DecisionTable.builder("d").input("x").output("o")
                .rule(List.of("<"), List.of("1")).build());
        assertThrows(DmnException.class, () -> DecisionTable.builder("d").input("x").output("o")
                .rule(List.of("1", "2"), List.of("1")).build());
    }

    @Test
    void modelOrdersDecisionsByDependency() {
        var m = DecisionModel.builder("loan")
                .literal("approved", "risk != \"high\" and amount < 1000")
                .table(DecisionTable.builder("risk").input("age").output("risk")
                        .rule(List.of("< 25"), List.of("\"high\"")).rule(List.of(">= 25"), List.of("\"low\"")).build())
                .build();
        assertEquals(List.of("risk", "approved"), m.evaluationOrder());
        assertEquals(Set.of("age", "amount"), m.requiredInputs());
        var res = m.evaluate(Map.of("age", 30, "amount", 500));
        assertEquals("low", res.get("risk"));
        assertEquals(true, res.get("approved"));
        assertEquals(Set.of("risk"), m.evaluate(Map.of("age", 30), Set.of("risk")).keySet());
    }

    @Test
    void cyclesAreRejected() {
        assertThrows(DmnException.class, () -> DecisionModel.builder("c").literal("a", "b + 1").literal("b", "a + 1").build());
    }

    @Test
    void loadsFromMaps() {
        Map<String, Object> doc = Map.of("id", "m", "decisions", List.of(
                Map.of("name", "tier", "type", "table", "hitPolicy", "FIRST",
                        "inputs", List.of("spend"), "outputs", List.of("tier"),
                        "rules", List.of(
                                Map.of("when", List.of(">= 1000"), "then", List.of("\"gold\"")),
                                Map.of("when", List.of("-"), "then", List.of("\"basic\"")))),
                Map.of("name", "label", "type", "literal", "expression", "upper case(tier)")));
        var model = DmnLoader.load(doc);
        assertEquals("GOLD", model.evaluate(Map.of("spend", 5000)).get("label"));
        assertEquals("BASIC", model.evaluate(Map.of("spend", 5)).get("label"));
    }
}
