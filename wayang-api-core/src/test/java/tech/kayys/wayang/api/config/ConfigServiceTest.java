package tech.kayys.wayang.api.config;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Permission;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.Role;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.api.testkit.ConfigStoreContract;

class ConfigServiceTest {

    private final ConfigStore store = new ConfigStore.InMemory();
    private final Map<String, String> processProject = Map.of("a|orders", "shop", "a|loose", "");
    private final ConfigService svc = new ConfigService(store, x -> x[1].equals("shop") || x[1].equals("other"),
            (t, pr) -> processProject.get(t + "|" + pr) == null || processProject.get(t + "|" + pr).isEmpty() ? null : processProject.get(t + "|" + pr),
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    private final Principal admin = new Principal("a", "ann", Set.of(Role.ADMIN));
    private final Principal viewer = new Principal("a", "vic", Set.of(Role.VIEWER));
    private final Principal shopDev = new Principal("a", "sam", Set.of(), Set.of(), Map.of("shop", Set.of(Permission.DEFINITION_READ, Permission.DEFINITION_WRITE)));

    @Test
    void inMemoryStoreSatisfiesTheContract() {
        ConfigStoreContract.verify(ConfigStore.InMemory::new);
    }

    @Test
    void putListDeleteAndVersions() {
        assertEquals(1, svc.put(admin, null, "BASE_URL", "https://x.test").version());
        assertEquals(2, svc.put(admin, "", "BASE_URL", "https://y.test").version());
        assertEquals("https://y.test", svc.list(admin, null).get(0).value());
        svc.delete(admin, null, "BASE_URL");
        assertTrue(svc.list(admin, null).isEmpty());
        assertEquals(ApiException.Code.NOT_FOUND, assertThrows(ApiException.class, () -> svc.delete(admin, null, "BASE_URL")).code());
    }

    @Test
    void aProjectValueWinsForItsProcessesAndTheTenantValueIsTheFallback() {
        svc.put(admin, null, "CHANNEL", "general");
        svc.put(admin, "shop", "CHANNEL", "orders");
        svc.put(admin, null, "ONLY_TENANT", "t");
        assertEquals("orders", svc.resolve("a", "orders", "CHANNEL"));
        assertEquals("general", svc.resolve("a", "loose", "CHANNEL"));
        assertEquals("general", svc.resolve("a", null, "CHANNEL"));
        assertEquals("t", svc.resolve("a", "orders", "ONLY_TENANT"));
        assertNull(svc.resolve("a", "orders", "NOPE"));
        assertNull(svc.resolve("b", "orders", "CHANNEL"), "other tenant");
        assertNull(svc.resolve(null, "orders", "CHANNEL"));
        assertNull(svc.resolve("a", "orders", "../x"));
    }

    @Test
    void permissionsFollowDefinitionsAndProjects() {
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> svc.put(viewer, null, "K", "v")).code());
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> svc.put(shopDev, null, "K", "v")).code(), "project-only grant is not tenant-wide");
        svc.put(shopDev, "shop", "K", "v");
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> svc.put(shopDev, "other", "K", "v")).code());
        assertEquals(1, svc.list(viewer, null).size() + svc.list(shopDev, "shop").size() - 0, "viewer reads tenant scope, project dev reads own project");
        assertEquals(ApiException.Code.NOT_FOUND, assertThrows(ApiException.class, () -> svc.put(admin, "ghost", "K", "v")).code());
        assertEquals(ApiException.Code.UNAUTHORIZED, assertThrows(ApiException.class, () -> svc.list(null, null)).code());
    }

    @Test
    void droppingAProjectTakesItsSettingsAndNeedsTenantWideWrite() {
        svc.put(admin, "shop", "A", "1");
        svc.put(admin, "other", "A", "2");
        svc.put(admin, null, "A", "t");
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> svc.dropProject(shopDev, "shop")).code());
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> svc.dropProject(viewer, "shop")).code());
        assertEquals(1, svc.dropProject(admin, "shop"));
        assertTrue(store.list("a", "shop").isEmpty());
        assertEquals(1, store.list("a", "other").size() + store.list("a", "").size() - 1, "other scopes untouched");
        assertThrows(ApiException.class, () -> svc.dropProject(admin, ""));
        assertThrows(ApiException.class, () -> svc.dropProject(null, "shop"));
    }

    @Test
    void credentialsAndTemplatesAreRefused() {
        for (String k : new String[] { "API_TOKEN", "db.password", "clientSecret", "private-key", "apiKey", "API_KEY" }) {
            assertEquals(ApiException.Code.BAD_REQUEST, assertThrows(ApiException.class, () -> svc.put(admin, null, k, "v")).code(), k);
        }
        for (String k : new String[] { "", "a b", "a/b", "x".repeat(101) }) {
            assertThrows(ApiException.class, () -> svc.put(admin, null, k, "v"), k);
        }
        assertThrows(ApiException.class, () -> svc.put(admin, null, "K", "${secret:X}"));
        assertThrows(ApiException.class, () -> svc.put(admin, null, "K", "a\u0000b"));
        assertThrows(ApiException.class, () -> svc.put(admin, null, "K", "a\rb"));
        assertThrows(ApiException.class, () -> svc.put(admin, null, "K", "x".repeat(4097)));
        assertThrows(ApiException.class, () -> svc.put(admin, null, "K", null));
        svc.put(admin, null, "K", "x".repeat(4096));
        svc.put(admin, null, "L", "line1\nline2\tTab");
    }

    @Test
    void aScopeHoldsAtMostFiveHundredSettings() {
        for (int i = 0; i < ConfigService.MAX_PER_SCOPE; i++) {
            svc.put(admin, null, "k" + i, "v");
        }
        assertThrows(ApiException.class, () -> svc.put(admin, null, "one-more", "v"));
        svc.put(admin, null, "k0", "changed"); // existing keys can still be updated
        svc.put(admin, "shop", "other-scope-is-separate", "v");
    }
}
