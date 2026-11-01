package tech.kayys.wayang.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.json.Json;

class SecretArchiveTest {

    private static final String PASS = "correct horse battery staple";
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final Principal admin = new Principal("acme", "ann", Set.of(Role.ADMIN));
    private final Principal designer = new Principal("acme", "dee", Set.of(Role.DESIGNER));

    private SecretService service(byte fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, fill);
        return new SecretService(new InMemorySecretStore(), new SecretCipher(Map.of("k" + fill, k), "k" + fill), clock);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> viaJson(Map<String, Object> m) {
        return (Map<String, Object>) Json.read(Json.write(m));
    }

    @Test
    void roundTripNeedsThePassphraseAndHidesTheValues() {
        var a = SecretArchive.seal(PASS, Map.of("DB", "p4ss", "TOKEN", "tok"), 100_000);
        String text = Json.write(a);
        assertFalse(text.contains("p4ss") || text.contains("TOKEN"), "neither values nor names are visible");
        assertEquals(Map.of("DB", "p4ss", "TOKEN", "tok"), SecretArchive.open(PASS, viaJson(a)));
        assertThrows(IllegalArgumentException.class, () -> SecretArchive.open(PASS + "x", a));
        assertThrows(IllegalArgumentException.class, () -> SecretArchive.open(null, a));
    }

    @Test
    void everyPartOfTheFileIsAuthenticated() {
        var a = SecretArchive.seal(PASS, Map.of("DB", "p4ss"), 100_000);
        for (String field : new String[] { "salt", "iv", "ciphertext" }) {
            Map<String, Object> t = new LinkedHashMap<>(a);
            String v = (String) t.get(field);
            t.put(field, (v.charAt(0) == 'A' ? "B" : "A") + v.substring(1));
            assertThrows(IllegalArgumentException.class, () -> SecretArchive.open(PASS, t), field);
        }
        Map<String, Object> weaker = new LinkedHashMap<>(a);
        weaker.put("iterations", 100_001);
        assertThrows(IllegalArgumentException.class, () -> SecretArchive.open(PASS, weaker), "a changed cost does not open");
        Map<String, Object> low = new LinkedHashMap<>(a);
        low.put("iterations", 1);
        assertThrows(IllegalArgumentException.class, () -> SecretArchive.open(PASS, low), "too cheap to try");
        Map<String, Object> huge = new LinkedHashMap<>(a);
        huge.put("iterations", 999_999_999);
        assertThrows(IllegalArgumentException.class, () -> SecretArchive.open(PASS, huge), "too expensive to try");
        Map<String, Object> other = new LinkedHashMap<>(a);
        other.put("format", "x");
        assertThrows(IllegalArgumentException.class, () -> SecretArchive.open(PASS, other));
        Map<String, Object> noCt = new LinkedHashMap<>(a);
        noCt.remove("ciphertext");
        assertThrows(IllegalArgumentException.class, () -> SecretArchive.open(PASS, noCt));
    }

    @Test
    void anArchiveSealedWithAWeakCostIsNotOpened() {
        var cheap = SecretArchive.seal(PASS, Map.of("A", "1"), 1_000);
        assertThrows(IllegalArgumentException.class, () -> SecretArchive.open(PASS, cheap), "genuinely sealed that cheaply, still refused");
        assertEquals(Map.of("A", "1"), SecretArchive.open(PASS, SecretArchive.seal(PASS, Map.of("A", "1"), 100_000)));
    }

    @Test
    void twoSealsOfTheSameDataDiffer() {
        var a = SecretArchive.seal(PASS, Map.of("A", "1"), 100_000);
        var b = SecretArchive.seal(PASS, Map.of("A", "1"), 100_000);
        assertNotEquals(a.get("ciphertext"), b.get("ciphertext"));
        assertNotEquals(a.get("salt"), b.get("salt"));
        assertNotEquals(a.get("iv"), b.get("iv"));
    }

    @Test
    void weakPassphrasesAreRefused() {
        for (String bad : new String[] { null, "", "short", "aaaaaaaaaaaaaaaa", "            ", "x".repeat(257), "abababababab" }) {
            assertThrows(ApiException.class, () -> SecretArchive.checkPassphrase(bad), String.valueOf(bad));
        }
        SecretArchive.checkPassphrase(PASS);
    }

    @Test
    void anotherServerCanImportWhatOneExported() {
        var from = service((byte) 1);
        var to = service((byte) 2);
        from.put(admin, "DB", "p4ss");
        from.put(admin, "TOKEN", "tok");
        var archive = viaJson(from.exportArchive(admin, PASS));
        var r = to.importArchive(admin, PASS, archive, false);
        assertEquals(2, r.created());
        assertEquals("p4ss", to.resolve("acme", "DB"));
        assertEquals("k2", to.list(admin).get(0).keyId(), "sealed under the new server's own key");
        var again = to.importArchive(admin, PASS, archive, false);
        assertEquals(0, again.created() + again.updated());
        assertEquals(2, again.skipped());
        from.put(admin, "DB", "changed");
        var over = to.importArchive(admin, PASS, viaJson(from.exportArchive(admin, PASS)), true);
        assertEquals(2, over.updated());
        assertEquals("changed", to.resolve("acme", "DB"));
        assertEquals(2, to.list(admin).get(0).version(), "an overwrite is a new version");
    }

    @Test
    void importLandsInTheCallersTenantWhateverTheSourceWas() {
        var from = service((byte) 1);
        var to = service((byte) 2);
        from.put(admin, "DB", "p4ss");
        var archive = from.exportArchive(admin, PASS);
        to.importArchive(new Principal("globex", "gus", Set.of(Role.ADMIN)), PASS, archive, false);
        assertEquals("p4ss", to.resolve("globex", "DB"));
        assertNull(to.resolve("acme", "DB"));
    }

    @Test
    void exportNeedsTheAdminPermissionAndLeavesOutReservedNames() {
        var from = service((byte) 1);
        from.put(admin, "DB", "p4ss");
        from.putSystem("acme", "oauth-refresh.work", "refresh-token", "system");
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> from.exportArchive(designer, PASS)).code());
        assertEquals(ApiException.Code.UNAUTHORIZED, assertThrows(ApiException.class, () -> from.exportArchive(null, PASS)).code());
        var opened = SecretArchive.open(PASS, from.exportArchive(admin, PASS));
        assertEquals(Set.of("DB"), opened.keySet(), "connected-account tokens do not travel");
        assertThrows(ApiException.class, () -> from.exportArchive(admin, "short"));
    }

    @Test
    void managingSecretsIsNotEnoughToTakeThePlaintextOut() {
        var svc = service((byte) 1);
        svc.put(admin, "DB", "p4ss");
        Principal manager = new Principal("acme", "mia", Set.of(), Set.of(Permission.SECRET_MANAGE, Permission.SECRET_LIST));
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> svc.exportArchive(manager, PASS)).code());
        Principal lister = new Principal("acme", "lou", Set.of(), Set.of(Permission.SECRET_LIST));
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> svc.keyStatus(lister)).code());
        assertEquals(1, svc.keyStatus(manager).size() - 2, "a manager can see how far rotation has got");
    }

    @Test
    void importIsAllOrNothingAndRefusesReservedNames() {
        var to = service((byte) 2);
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("GOOD", "v");
        bad.put("oauth.hijack", "v");
        var e = assertThrows(ApiException.class, () -> to.importArchive(admin, PASS, SecretArchive.seal(PASS, bad, 100_000), false));
        assertTrue(e.getMessage().contains("reserved"));
        assertTrue(to.list(admin).isEmpty(), "GOOD was not written either");
        assertThrows(ApiException.class, () -> to.importArchive(admin, PASS, SecretArchive.seal(PASS, Map.of("vault.x", "v"), 100_000), false));
        assertThrows(ApiException.class, () -> to.importArchive(admin, PASS, SecretArchive.seal(PASS, Map.of("has space", "v"), 100_000), false));
        assertThrows(ApiException.class, () -> to.importArchive(admin, PASS, SecretArchive.seal(PASS, Map.of("EMPTY", ""), 100_000), false));
        var wrong = assertThrows(ApiException.class, () -> to.importArchive(admin, PASS + "!", SecretArchive.seal(PASS, Map.of("A", "v"), 100_000), false));
        assertTrue(wrong.getMessage().contains("wrong passphrase"));
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class,
                () -> to.importArchive(new Principal("acme", "v", Set.of(Role.VIEWER)), PASS, SecretArchive.seal(PASS, Map.of("A", "v"), 100_000), false)).code());
    }

    @Test
    void keyStatusShowsHowFarARotationHasGot() {
        var svc = service((byte) 1);
        svc.put(admin, "A", "1");
        svc.put(admin, "B", "2");
        svc.putSystem("acme", "oauth-refresh.x", "r", "s");
        Map<String, Object> st = svc.keyStatus(admin);
        assertEquals("k1", st.get("activeKeyId"));
        assertEquals(Map.of("k1", 2), st.get("secretsByKey"));
        assertEquals(0, st.get("needRotation"));
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> svc.keyStatus(new Principal("acme", "v", Set.of(Role.VIEWER)))).code());
        assertEquals(List.of(), List.of());
    }
}
