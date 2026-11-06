package tech.kayys.wayang.eip;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Hostile payloads for the format converters: refused with an ordinary exception, quickly, never an Error. */
class AdversarialFormatsTest {

    private static long run(String format, String text) {
        long t0 = System.nanoTime();
        try {
            Formats.parse(format, text, Map.of());
        } catch (RuntimeException expected) {
            // refused: good
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Test
    void xmlBombsAndDeepXmlAreRefused() {
        String lol = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">"
                + "<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">]><lolz>&lol3;</lolz>";
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>";
        String xxeHttp = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"http://127.0.0.1:9/x\">]><r>&x;</r>";
        for (String x : new String[] {lol, xxe, xxeHttp}) {
            assertThrows(RuntimeException.class, () -> Formats.parse("xml", x, Map.of()), x.substring(0, 60));
        }
        assertTrue(run("xml", "<a>".repeat(100_000) + "</a>".repeat(100_000)) < 5000);
        assertTrue(run("xml", "<a " + "b=\"1\" ".repeat(100_000) + "/>") < 5000);
    }

    @Test
    void jsonAndCsvBombsAreCutOff() {
        assertTrue(run("json", "[".repeat(200_000) + "]".repeat(200_000)) < 5000);
        assertTrue(run("json", "{\"a\":1," .repeat(100_000)) < 5000);
        assertTrue(run("csv", "\"" + "x,\n".repeat(2_000_000)) < 10000);
        assertTrue(run("csv", ("a,".repeat(100_000) + "\n").repeat(100)) < 10000);
        assertTrue(run("csv", "\"".repeat(3_000_000)) < 10000);
    }

    @Test
    void csvInjectionIsNeutralisedOnWrite() {
        String out = Formats.serialize("csv", java.util.List.of(Map.of("a", "=HYPERLINK(\"http://evil\")", "b", "+1", "c", "-2", "d", "@x", "e", "ok")), Map.of());
        assertFalse(out.contains(",=HYPERLINK") && !out.contains("'=HYPERLINK") && !out.contains("\"'=") , out);
    }
}
