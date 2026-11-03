package tech.kayys.wayang.api.secret;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.Role;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.api.testkit.SecretStoreContract;

class SecretsTest {

    private static byte[] key(int fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) fill);
        return k;
    }

    private final SecretCipher cipher = new SecretCipher(Map.of("k1", key(1), "k2", key(2)), "k1");
    private final InMemorySecretStore store = new InMemorySecretStore();
    private final SecretService svc = new SecretService(store, cipher,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    private final Principal adminA = new Principal("a", "admin", Set.of(Role.ADMIN));
    private final Principal adminB = new Principal("b", "admin", Set.of(Role.ADMIN));
    private final Principal designerA = new Principal("a", "des", Set.of(Role.DESIGNER));
    private final Principal operatorA = new Principal("a", "op", Set.of(Role.OPERATOR));

    @Test
    void storeContract() {
        SecretStoreContract.verify(InMemorySecretStore::new);
    }

    @Test
    void sealOpenRoundTripAndRandomIv() {
        String a = cipher.seal("a", "N", 1, "hunter2");
        String b = cipher.seal("a", "N", 1, "hunter2");
        assertTrue(!a.equals(b), "fresh IV each time");
        assertFalse(a.contains("hunter2"));
        assertEquals("hunter2", cipher.open("a", "N", 1, a));
    }

    @Test
    void contextAndTamperAreRejected() {
        String s = cipher.seal("a", "N", 1, "x");
        assertThrows(IllegalArgumentException.class, () -> cipher.open("b", "N", 1, s), "other tenant");
        assertThrows(IllegalArgumentException.class, () -> cipher.open("a", "M", 1, s), "other name");
        assertThrows(IllegalArgumentException.class, () -> cipher.open("a", "N", 2, s), "other version");
        String[] p = s.split(":", 3);
        byte[] raw = java.util.Base64.getDecoder().decode(p[2]);
        raw[raw.length - 1] ^= 1;
        String tampered = p[0] + ":" + p[1] + ":" + java.util.Base64.getEncoder().encodeToString(raw);
        assertThrows(IllegalArgumentException.class, () -> cipher.open("a", "N", 1, tampered), "bit flip");
        assertThrows(IllegalArgumentException.class, () -> cipher.open("a", "N", 1, "garbage"));
        assertThrows(IllegalArgumentException.class, () -> new SecretCipher(Map.of("k", new byte[16]), "k"));
    }

    @Test
    void putIsWriteOnlyVersionedAndTenantScoped() {
        assertEquals(1, svc.put(adminA, "API_KEY", "s3cr3t").version());
        assertEquals(2, svc.put(adminA, "API_KEY", "rotated").version());
        assertEquals("rotated", svc.resolve("a", "API_KEY"));
        assertNull(svc.resolve("b", "API_KEY"), "other tenant");
        assertNull(svc.resolve("a", "NOPE"));
        assertNull(svc.resolve(null, "API_KEY"));
        assertFalse(svc.list(adminA).toString().contains("rotated"), "metadata only");
        assertTrue(svc.list(adminB).isEmpty());
    }

    @Test
    void rbacAndValidation() {
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> svc.put(designerA, "X", "v")).code());
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> svc.list(operatorA)).code());
        assertDoesNotThrow(() -> svc.list(designerA));
        assertEquals(ApiException.Code.BAD_REQUEST, assertThrows(ApiException.class, () -> svc.put(adminA, "bad name!", "v")).code());
        assertEquals(ApiException.Code.BAD_REQUEST, assertThrows(ApiException.class, () -> svc.put(adminA, "X", "")).code());
        assertEquals(ApiException.Code.NOT_FOUND, assertThrows(ApiException.class, () -> svc.delete(adminA, "X")).code());
        svc.put(adminA, "X", "v");
        svc.delete(adminA, "X");
        assertNull(svc.resolve("a", "X"));
    }

    @Test
    void rotationReSealsUnderActiveKeyKeepingVersion() {
        SecretService old = new SecretService(store, new SecretCipher(Map.of("k1", key(1)), "k1"),
                Clock.systemUTC());
        old.put(adminA, "K", "val");
        SecretService rotated = new SecretService(store, cipher, Clock.systemUTC()); // active is k1 here
        SecretCipher c2 = new SecretCipher(Map.of("k1", key(1), "k2", key(2)), "k2");
        SecretService s2 = new SecretService(store, c2, Clock.systemUTC());
        assertEquals(1, s2.rotate(adminA));
        assertEquals(0, s2.rotate(adminA), "idempotent");
        var m = s2.list(adminA).get(0);
        assertEquals("k2", m.keyId());
        assertEquals(1, m.version());
        assertEquals("val", s2.resolve("a", "K"));
        // a service holding only the old key now fails closed instead of returning garbage
        assertNull(old.resolve("a", "K"));
        assertNotNull(rotated);
    }
}
