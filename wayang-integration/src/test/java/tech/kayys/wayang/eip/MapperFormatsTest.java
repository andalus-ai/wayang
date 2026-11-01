package tech.kayys.wayang.eip;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.expression.Scope;

class MapperFormatsTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Object run(Object spec, Object body) {
        return Mapper.compile(spec).apply(Message.of(body).scope(Scope.empty()));
    }

    @Test
    void mapsFieldsAndNestedPaths() {
        Object out = run(map("customer.name", "body.first + \" \" + body.last", "customer.id", "body.id", "total", "body.p * body.q"),
                map("first", "Ada", "last", "L", "id", "c1", "p", 3, "q", 4));
        Map<?, ?> m = (Map<?, ?>) out;
        assertEquals("Ada L", ((Map<?, ?>) m.get("customer")).get("name"));
        assertEquals("c1", ((Map<?, ?>) m.get("customer")).get("id"));
        assertEquals(0, new BigDecimal(12).compareTo((BigDecimal) m.get("total")));
    }

    @Test
    void constIsTheOnlyWayToEmitLiteralStrings() {
        Map<?, ?> m = (Map<?, ?>) run(map("kind", map("$const", "order"), "n", 5, "ok", true, "z", null), Map.of());
        assertEquals("order", m.get("kind"));
        assertEquals(0, new BigDecimal(5).compareTo((BigDecimal) m.get("n")));
        assertEquals(true, m.get("ok"));
        assertNull(m.get("z"));
    }

    @Test
    void templateInterpolatesAndEscapes() {
        assertEquals("Hi Ada, you owe 7 ${x}",
                run(map("$template", "Hi ${body.name}, you owe ${body.a + 2} $${x}"), map("name", "Ada", "a", 5)));
        assertEquals("v=1", run(map("$template", "v=${ {a: 1}.a }"), Map.of()));
        assertThrows(EipException.class, () -> Mapper.compile(map("$template", "x ${body.a")));
    }

    @Test
    void eachMapsFiltersAndBindsIndex() {
        Object out = run(map("$each", "body.lines", "$as", "l", "$where", "l.qty > 0",
                "$map", map("sku", "l.code", "i", "index")),
                map("lines", List.of(map("code", "a", "qty", 1), map("code", "b", "qty", 0), map("code", "c", "qty", 2))));
        List<?> l = (List<?>) out;
        assertEquals(2, l.size());
        assertEquals("c", ((Map<?, ?>) l.get(1)).get("sku"));
        assertEquals(0, new BigDecimal(2).compareTo((BigDecimal) ((Map<?, ?>) l.get(1)).get("i")));
    }

    @Test
    void eachWithoutMapReturnsItemsAndNullSourceGivesEmpty() {
        assertEquals(List.of(1, 2).size(), ((List<?>) run(map("$each", "body.x"), map("x", List.of(1, 2)))).size());
        assertEquals(List.of(), run(map("$each", "body.missing"), Map.of()));
        assertThrows(EipException.class, () -> run(map("$each", "body.x"), map("x", 1)));
    }

    @Test
    void ifThenElse() {
        Object spec = map("$if", "body.vip", "$then", map("$const", "gold"), "$else", map("$const", "std"));
        assertEquals("gold", run(spec, map("vip", true)));
        assertEquals("std", run(spec, map("vip", false)));
    }

    @Test
    void omitNullDropsNulls() {
        Map<?, ?> m = (Map<?, ?>) Mapper.compile(map("a", "body.a", "b", "body.b"), true)
                .apply(Message.of(map("a", 1)).scope(Scope.empty()));
        assertTrue(m.containsKey("a"));
        assertFalse(m.containsKey("b"));
    }

    @Test
    void badSpecsFailAtCompileTime() {
        assertThrows(EipException.class, () -> Mapper.compile(map("a", "body.")));
        assertThrows(EipException.class, () -> Mapper.compile(map("$wat", "x")));
        assertThrows(EipException.class, () -> Mapper.compile(map("$const", 1, "extra", 2)));
        assertThrows(EipException.class, () -> Mapper.compile(map("$each", "body.x", "$as", "bad name")));
        assertThrows(EipException.class, () -> run(map("a", "1", "a.b", "2"), Map.of()));
        Object deep = "body";
        for (int i = 0; i < 40; i++) {
            deep = List.of(deep);
        }
        Object d = deep;
        assertThrows(EipException.class, () -> Mapper.compile(d));
    }

    @Test
    void eachFanoutIsBounded() {
        List<Object> big = new ArrayList<>();
        for (int i = 0; i < Step.MAX_FANOUT + 1; i++) {
            big.add(i);
        }
        assertThrows(EipException.class, () -> run(map("$each", "body"), big));
    }

    // ---- formats ----

    @Test
    void jsonRoundTripAndStrictness() {
        Object v = Formats.parse("json", "{\"a\":[1,2.5,\"x\\n\\u00e9\",true,null],\"b\":{}}", Map.of());
        assertEquals("{\"a\":[1,2.5,\"x\\né\",true,null],\"b\":{}}", Formats.serialize("json", v, Map.of()));
        for (String bad : List.of("{\"a\":1,}", "[1 2]", "{a:1}", "1 2", "\"abc", "{\"a\":01x}", "[", ""))
            assertThrows(EipException.class, () -> Formats.parse("json", bad, Map.of()), bad);
        assertThrows(EipException.class, () -> Formats.parse("json", "[".repeat(100) + "]".repeat(100), Map.of()));
    }

    @Test
    void csvParsesQuotesHeadersAndRejectsRaggedRows() {
        List<?> rows = (List<?>) Formats.parse("csv", "a,b\r\n1,\"x,\"\"y\"\"\"\r\n2,\"multi\nline\"\r\n", Map.of());
        assertEquals(2, rows.size());
        assertEquals("x,\"y\"", ((Map<?, ?>) rows.get(0)).get("b"));
        assertEquals("multi\nline", ((Map<?, ?>) rows.get(1)).get("b"));
        assertThrows(EipException.class, () -> Formats.parse("csv", "a,b\n1\n", Map.of()));
        assertThrows(EipException.class, () -> Formats.parse("csv", "a,a\n1,2\n", Map.of()));
        assertThrows(EipException.class, () -> Formats.parse("csv", "a\n\"open\n", Map.of()));
        assertEquals(List.of("1", "2"), ((List<?>) ((List<?>) Formats.parse("csv", "1;2", Map.of("delimiter", ";", "header", false))).get(0)));
    }

    @Test
    void csvWriteQuotesAndGuardsFormulas() {
        String out = Formats.serialize("csv", List.of(map("n", "=SUM(A1)", "t", "a,b", "v", new BigDecimal("1.50"))), Map.of());
        assertEquals("n,t,v\r\n'=SUM(A1),\"a,b\",1.5\r\n", out);
        assertTrue(Formats.serialize("csv", List.of(map("n", "=1")), Map.of("escapeFormulas", false)).contains("\r\n=1"));
        assertEquals("n\r\n-5\r\n", Formats.serialize("csv", List.of(map("n", new BigDecimal(-5))), Map.of()));
        assertThrows(EipException.class, () -> Formats.serialize("csv", List.of(map("n", List.of())), Map.of()));
        assertThrows(EipException.class, () -> Formats.serialize("csv", map(), Map.of()));
    }

    @Test
    void xmlParseAttributesRepeatsAndSerialize() {
        Object v = Formats.parse("xml", "<o id=\"7\"><i>a</i><i>b</i><n>x &amp; y</n><e/></o>", Map.of());
        Map<?, ?> o = (Map<?, ?>) ((Map<?, ?>) v).get("o");
        assertEquals("7", o.get("@id"));
        assertEquals(List.of("a", "b"), o.get("i"));
        assertEquals("x & y", o.get("n"));
        assertEquals("", o.get("e"));
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><o id=\"7\"><i>a</i><i>b</i><n>x &amp; y</n><e></e></o>",
                Formats.serialize("xml", v, Map.of()));
    }

    @Test
    void xmlRejectsDoctypeEntitiesAndBadNames() {
        assertThrows(EipException.class, () -> Formats.parse("xml",
                "<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><a>&x;</a>", Map.of()));
        assertThrows(EipException.class, () -> Formats.parse("xml", "<a><b></a>", Map.of()));
        assertThrows(EipException.class, () -> Formats.serialize("xml", map("a b", "x"), Map.of()));
        assertThrows(EipException.class, () -> Formats.serialize("xml", map("a", map("@x\"y", "1")), Map.of()));
        assertThrows(EipException.class, () -> Formats.serialize("xml", map("a", "1", "b", "2"), Map.of()));
    }

    @Test
    void oversizedInputRefused() {
        assertThrows(EipException.class, () -> Formats.parse("json", "1".repeat(Formats.MAX_CHARS + 1), Map.of()));
    }
}
