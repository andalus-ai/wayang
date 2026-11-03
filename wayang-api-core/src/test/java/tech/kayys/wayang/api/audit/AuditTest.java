package tech.kayys.wayang.api.audit;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Permission;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.Role;
import tech.kayys.wayang.api.TenantGate;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.api.testkit.AuditStoreContract;

class AuditTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final AuditStore store = new AuditStore.InMemory(100);
    private final AuditService audit = new AuditService(store, clock);
    private final Principal admin = new Principal("acme", "ann", Set.of(Role.ADMIN));
    private final Principal viewer = new Principal("acme", "vic", Set.of(Role.VIEWER));

    @Test
    void mutationsAndDenialsAreRecordedReadsAreNot() {
        audit.record(admin, "PUT", "/api/v1/definitions/{kind}/{id}", Map.of("kind", "process", "id", "loan"), 200);
        audit.record(admin, "DELETE", "/api/v1/projects/{id}", Map.of("id", "p1"), 404);
        audit.record(viewer, "GET", "/api/v1/runs", Map.of(), 200);
        audit.record(viewer, "GET", "/api/v1/secrets", Map.of(), 403);
        audit.record(viewer, "POST", "/api/v1/runs", Map.of(), 403);
        List<AuditEntry> l = audit.list(admin, 50, null);
        assertEquals(4, l.size());
        assertEquals(1, l.stream().filter(e -> e.outcome().equals("OK")).count());
        assertEquals(1, l.stream().filter(e -> e.outcome().equals("FAILED")).count());
        assertEquals(2, l.stream().filter(e -> e.outcome().equals("DENIED")).count());
        assertTrue(l.stream().anyMatch(e -> e.actor().equals("vic") && e.method().equals("GET") && e.status() == 403));
    }

    @Test
    void secretReadsByRunsAreRecordedWithTheNameAndNoValueAndStayInTheirTenant() {
        audit.recordSecretRead("acme", "orders", "run-1", "post", "SLACK", true);
        audit.recordSecretRead("acme", "orders", "run-2", "post", "GONE", false);
        audit.recordSecretRead("globex", "x", "run-3", "n", "OTHER", true);
        audit.recordSecretRead(null, "x", "r", "n", "S", true);
        audit.recordSecretRead("acme", "x", "r", "n", null, true);
        List<AuditEntry> l = audit.list(admin, 50, null);
        assertEquals(2, l.size());
        AuditEntry ok = l.stream().filter(e -> e.status() == 200).findFirst().orElseThrow();
        assertEquals("run:orders", ok.actor());
        assertEquals("RESOLVE", ok.method());
        assertEquals("secret", ok.route());
        assertEquals(Map.of("secret", "SLACK", "node", "post", "run", "run-1"), ok.ids(), "the name, never a value, is in the entry");
        assertEquals("OK", ok.outcome());
        AuditEntry missing = l.stream().filter(e -> e.status() == 404).findFirst().orElseThrow();
        assertEquals("FAILED", missing.outcome());
        assertTrue(audit.list(new Principal("globex", "gus", Set.of(Role.ADMIN)), 50, null).stream().noneMatch(e -> e.ids().containsValue("SLACK")));
        assertEquals(0, audit.failures());
    }

    @Test
    void aFailingStoreDoesNotBreakSecretReadRecording() {
        AuditStore broken = new AuditStore() {
            @Override
            public void append(AuditEntry entry) {
                throw new IllegalStateException("down");
            }

            @Override
            public List<AuditEntry> list(String tenant, int limit, Instant before) {
                return List.of();
            }
        };
        var a = new AuditService(broken, clock);
        a.recordSecretRead("acme", "p", "r", "n", "S", true);
        assertEquals(1, a.failures());
    }

    @Test
    void unauthenticatedRequestsAreNotRecordedAndNeverFailTheCaller() {
        audit.record(null, "POST", "/api/v1/runs", Map.of(), 401);
        assertTrue(store.list("acme", 10, null).isEmpty());
        AuditService broken = new AuditService(new AuditStore() {
            public void append(AuditEntry e) {
                throw new IllegalStateException("db down");
            }

            public List<AuditEntry> list(String t, int l, Instant b) {
                return List.of();
            }
        }, clock);
        assertDoesNotThrow(() -> broken.record(admin, "POST", "/x", Map.of(), 200));
        assertEquals(1, broken.failures());
    }

    @Test
    void credentialLikePathParametersAreMaskedAndLongOnesClipped() {
        audit.record(admin, "POST", "/hooks/{tenant}/{id}/{token}", Map.of("tenant", "acme", "id", "t1", "token", "s3cr3t", "oauthState", "xyz"), 200);
        audit.record(admin, "DELETE", "/api/v1/projects/{id}", Map.of("id", "x".repeat(500)), 200);
        List<AuditEntry> l = audit.list(admin, 10, null);
        AuditEntry hook = l.stream().filter(e -> e.route().startsWith("/hooks")).findFirst().orElseThrow();
        assertEquals("***", hook.ids().get("token"));
        assertEquals("***", hook.ids().get("oauthState"));
        assertEquals("t1", hook.ids().get("id"));
        assertFalse(hook.toMap().toString().contains("s3cr3t"));
        assertEquals(200, l.stream().filter(e -> e.route().contains("projects")).findFirst().orElseThrow().ids().get("id").length());
    }

    @Test
    void onlyTenantWideAdminsReadTheirOwnTenantsLog() {
        audit.record(admin, "POST", "/a", Map.of(), 200);
        audit.record(new Principal("globex", "gus", Set.of(Role.ADMIN)), "POST", "/b", Map.of(), 200);
        assertEquals(1, audit.list(admin, 10, null).size());
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> audit.list(viewer, 10, null)).code());
        Principal projectAdmin = new Principal("acme", "pa", Set.of(), Set.of(), Map.of("p1", Set.of(Permission.ADMIN)));
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> audit.list(projectAdmin, 10, null)).code());
        assertThrows(ApiException.class, () -> audit.list(null, 10, null));
    }

    @Test
    void storeContract() {
        AuditStoreContract.verify(() -> new AuditStore.InMemory(1000));
    }

    @Test
    void inMemoryStoreIsBounded() {
        AuditStore s = new AuditStore.InMemory(3);
        for (int i = 0; i < 10; i++) {
            s.append(new AuditEntry("t", Instant.EPOCH.plusSeconds(i), "u", "POST", "/r" + i, Map.of(), 200, "OK"));
        }
        assertEquals(List.of("/r9", "/r8", "/r7"), s.list("t", 10, null).stream().map(AuditEntry::route).toList());
    }

    @Test
    void tenantGate() {
        TenantGate g = TenantGate.suspended(Set.of("bad"));
        assertTrue(g.active("acme"));
        assertFalse(g.active("bad"));
        assertDoesNotThrow(() -> g.require("acme"));
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class, () -> g.require("bad")).code());
        assertTrue(TenantGate.ALLOW_ALL.active("anything"));
    }
}
