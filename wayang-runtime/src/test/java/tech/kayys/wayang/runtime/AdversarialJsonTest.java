package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.json.Json;

/** Hostile JSON (webhook bodies, API requests, stored documents) must fail as an ordinary exception, not crash a thread with an Error. */
class AdversarialJsonTest {

    private static void survives(String json) {
        try {
            Json.read(json);
        } catch (RuntimeException expected) {
            // good: refused
        }
    }

    @Test
    void deepNestingIsRefusedNotAStackOverflow() {
        survives("[".repeat(100_000) + "]".repeat(100_000));
        survives("{\"a\":".repeat(100_000) + "1" + "}".repeat(100_000));
        survives("[".repeat(1_000_000));
    }

    @Test
    void oddButBoundedInputIsRefusedCleanly() {
        for (String s : new String[] {"", "{", "[1,", "{\"a\"", "nul", "\"\\u12", "\"\\ud800\"", "1e999999999", "-", "{\"a\":1,}", "[1 2]", "\u0000", "\"" + "x".repeat(5_000_000)}) {
            survives(s);
        }
    }

    @Test
    void aHugeNumberDoesNotHangTheParser() {
        long t0 = System.nanoTime();
        survives("1e999999999");
        survives("0." + "9".repeat(1_000_000));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5000, "parsing hostile numbers must be quick");
    }

    @Test
    void writingDeepStructuresIsBoundedToo() {
        Object v = new Object[0];
        java.util.List<Object> cur = new java.util.ArrayList<>();
        java.util.List<Object> root = cur;
        for (int i = 0; i < 100_000; i++) {
            java.util.List<Object> next = new java.util.ArrayList<>();
            cur.add(next);
            cur = next;
        }
        try {
            Json.write(root);
        } catch (RuntimeException expected) {
            // good
        }
    }
}
