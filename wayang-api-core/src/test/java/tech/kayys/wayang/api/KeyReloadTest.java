package tech.kayys.wayang.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class KeyReloadTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final Principal admin = new Principal("acme", "ann", Set.of(Role.ADMIN));
    private final Principal manager = new Principal("acme", "mia", Set.of(), Set.of(Permission.SECRET_MANAGE));

    private static byte[] key(int fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) fill);
        return k;
    }

    private static SecretCipher ring(String active, int... fills) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int f : fills) {
            m.put("k" + f, key(f));
        }
        return new SecretCipher(m, active);
    }

    private final SecretService svc = new SecretService(new InMemorySecretStore(), ring("k1", 1), clock);

    @Test
    void aReloadedRingSealsNewSecretsUnderTheNewKeyAndStillReadsTheOldOnes() {
        svc.put(admin, "OLD", "before");
        var st = svc.reloadKeys(admin, () -> ring("k2", 1, 2));
        assertEquals(true, st.get("reloaded"));
        assertEquals("k2", st.get("activeKeyId"));
        assertEquals(1, st.get("needRotation"), "the old secret is still under k1");
        svc.put(admin, "NEW", "after");
        assertEquals("before", svc.resolve("acme", "OLD"));
        assertEquals("after", svc.resolve("acme", "NEW"));
        var keyOf = svc.list(admin).stream().collect(java.util.stream.Collectors.toMap(SecretService.Meta::name, SecretService.Meta::keyId));
        assertEquals("k1", keyOf.get("OLD"));
        assertEquals("k2", keyOf.get("NEW"));
        assertEquals(1, svc.rotate(admin));
        assertEquals(0, svc.keyStatus(admin).get("needRotation"));
        assertEquals("before", svc.resolve("acme", "OLD"));
    }

    @Test
    void aRingThatDropsAKeyInUseIsRefusedAndNothingChanges() {
        svc.put(admin, "OLD", "before");
        svc.reloadKeys(admin, () -> ring("k2", 1, 2));
        var e = assertThrows(ApiException.class, () -> svc.reloadKeys(admin, () -> ring("k2", 2)));
        assertTrue(e.getMessage().contains("[k1]"), e.getMessage());
        assertEquals("before", svc.resolve("acme", "OLD"), "still readable");
        assertEquals("k2", svc.keyStatus(admin).get("activeKeyId"));
    }

    @Test
    void aFailingOrMissingSourceLeavesTheCurrentKeysAlone() {
        svc.put(admin, "S", "v");
        assertThrows(ApiException.class, () -> svc.reloadKeys(admin, () -> {
            throw new IllegalStateException("vault down /secret/path");
        }));
        assertThrows(ApiException.class, () -> svc.reloadKeys(admin, () -> null));
        assertEquals("v", svc.resolve("acme", "S"));
        assertEquals("k1", svc.keyStatus(admin).get("activeKeyId"));
        try {
            svc.reloadKeys(admin, () -> {
                throw new IllegalStateException("token=abc123");
            });
        } catch (ApiException e) {
            assertFalse(e.getMessage().contains("abc123"), "the cause's message is not echoed");
        }
    }

    @Test
    void reloadingTheSameRingIsHarmlessAndNeedsAdmin() {
        svc.put(admin, "S", "v");
        assertEquals("k1", svc.reloadKeys(admin, () -> ring("k1", 1)).get("activeKeyId"));
        assertEquals("v", svc.resolve("acme", "S"));
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> svc.reloadKeys(manager, () -> ring("k1", 1))).code());
        assertEquals(ApiException.Code.UNAUTHORIZED, assertThrows(ApiException.class, () -> svc.reloadKeys(null, () -> ring("k1", 1))).code());
    }

    @Test
    void aSwapDuringWritesNeverRecordsTheWrongKeyId() throws Exception {
        var errors = new java.util.concurrent.atomic.AtomicInteger();
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread writer = new Thread(() -> {
            int i = 0;
            while (!stop.get() && i < 3000) {
                try {
                    svc.put(admin, "S" + (i++ % 20), "v" + i);
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                }
            }
        });
        writer.start();
        for (int i = 0; i < 200; i++) {
            String active = i % 2 == 0 ? "k2" : "k1";
            svc.reloadKeys(admin, () -> ring(active, 1, 2));
        }
        stop.set(true);
        writer.join();
        assertEquals(0, errors.get());
        for (var m : svc.list(admin)) {
            assertNotNull(svc.resolve("acme", m.name()), "every secret opens");
            assertTrue(m.keyId().equals("k1") || m.keyId().equals("k2"));
        }
        // the recorded key id is the one that really sealed it
        svc.reloadKeys(admin, () -> ring("k2", 1, 2));
        svc.rotate(admin);
        assertEquals(0, svc.keyStatus(admin).get("needRotation"));
    }
}
