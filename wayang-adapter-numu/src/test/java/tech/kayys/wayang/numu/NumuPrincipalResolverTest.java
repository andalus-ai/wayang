package tech.kayys.wayang.numu;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.DefinitionKind;
import tech.kayys.wayang.api.DefinitionStore;
import tech.kayys.wayang.api.EventBus;
import tech.kayys.wayang.api.Permission;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.ReferenceRunEngine;
import tech.kayys.wayang.api.RunEngine;
import tech.kayys.wayang.api.WayangApi;
import tech.kayys.wayang.runtime.WaitRegistry;

class NumuPrincipalResolverTest {

    private final FakeNumu numu = new FakeNumu();
    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private Instant now = t0;
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private final NumuScheme scheme = new NumuScheme();
    private final NumuPrincipalResolver resolver = new NumuPrincipalResolver(numu, scheme, clock, Duration.ofSeconds(30));

    private static final String[] ALL = {"wayang.definition.read", "wayang.definition.write", "wayang.run.read", "wayang.run.start",
            "wayang.run.cancel", "wayang.task.complete", "wayang.message.send", "wayang.secret.list", "wayang.secret.manage", "wayang.admin"};

    private ApiException denied(Runnable r) {
        return assertThrows(ApiException.class, r::run);
    }

    @Test
    void roleGrantedAtTheTenantRootIsInheritedByWayang() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme", "wayang.definition.read", "wayang.run.read");
        Principal p = resolver.resolve("tok-ann", "acme");
        assertEquals("acme", p.tenantId());
        assertTrue(p.userId().startsWith("u-"));
        assertTrue(p.can(Permission.DEFINITION_READ));
        assertTrue(p.can(Permission.RUN_READ));
        assertFalse(p.can(Permission.RUN_START), "only what was granted");
        assertFalse(p.can(Permission.DEFINITION_WRITE));
    }

    @Test
    void grantAtTheWayangScopeAlsoWorks() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme.wayang", "wayang.run.read");
        assertTrue(resolver.resolve("tok-ann", "acme").can(Permission.RUN_READ));
    }

    @Test
    void anotherTenantIsNotGranted() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme", ALL);
        assertEquals(ApiException.Code.FORBIDDEN, denied(() -> resolver.resolve("tok-ann", "globex")).code());
    }

    @Test
    void nonexistentAndNotAMemberLookTheSame() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme", ALL);
        ApiException a = denied(() -> resolver.resolve("tok-ann", "globex"));
        ApiException b = denied(() -> resolver.resolve("tok-ann", "no-such-tenant"));
        assertEquals(a.code(), b.code());
        assertEquals(a.getMessage(), b.getMessage());
    }

    @Test
    void scopePathInjectionThroughTheTenantIdIsRefused() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme", ALL);
        for (String evil : new String[] {"acme.wayang", "acme.", ".acme", "acme.project.x", "a b", "", "acme/../x", "acme.*"}) {
            assertEquals(ApiException.Code.FORBIDDEN, denied(() -> resolver.resolve("tok-ann", evil)).code(), evil);
        }
        assertThrows(IllegalArgumentException.class, () -> scheme.projectPath("acme", "p.q"));
    }

    @Test
    void adminImpliesEverything() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme", "wayang.admin");
        Principal p = resolver.resolve("tok-ann", "acme");
        for (Permission perm : Permission.values()) {
            assertTrue(p.can(perm), perm.name());
        }
    }

    @Test
    void invalidAndBlankCredentialsAreUnauthorized() {
        assertEquals(ApiException.Code.UNAUTHORIZED, denied(() -> resolver.resolve("nope", "acme")).code());
        assertEquals(ApiException.Code.UNAUTHORIZED, denied(() -> resolver.resolve("", "acme")).code());
        assertEquals(ApiException.Code.UNAUTHORIZED, denied(() -> resolver.resolve(null, "acme")).code());
    }

    @Test
    void serviceAccountsGetADistinctUserId() {
        var svc = numu.service("pat_abc");
        numu.grant(svc, "acme", "wayang.run.start");
        Principal p = resolver.resolve("pat_abc", "acme");
        assertTrue(p.userId().startsWith("svc-"));
        assertTrue(p.can(Permission.RUN_START));
    }

    @Test
    void failsClosedWhenIdentityIsDownOrAnswersNonsense() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme", ALL);
        numu.down = true;
        assertEquals(ApiException.Code.UNAVAILABLE, denied(() -> resolver.resolve("tok-ann", "acme")).code());
        numu.down = false;
        numu.malformed = true;
        assertEquals(ApiException.Code.UNAVAILABLE, denied(() -> resolver.resolve("tok-ann", "acme")).code());
    }

    @Test
    void identityOutageAfterAWarmCacheIsServedOnlyWithinTheTtl() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme", ALL);
        resolver.resolve("tok-ann", "acme");
        numu.down = true;
        assertDoesNotThrow(() -> resolver.resolve("tok-ann", "acme"));
        now = t0.plusSeconds(31);
        assertEquals(ApiException.Code.UNAVAILABLE, denied(() -> resolver.resolve("tok-ann", "acme")).code());
    }

    @Test
    void cachingAvoidsRemoteCallsAndRevocationTakesEffectAfterTheTtl() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme", ALL);
        resolver.resolve("tok-ann", "acme");
        resolver.resolve("tok-ann", "acme");
        assertEquals(1, numu.resolveCalls);
        assertEquals(1, numu.checkCalls);

        numu.assignments.clear(); // revoked
        assertDoesNotThrow(() -> resolver.resolve("tok-ann", "acme")); // still cached within the TTL (documented)
        now = t0.plusSeconds(31);
        assertEquals(ApiException.Code.FORBIDDEN, denied(() -> resolver.resolve("tok-ann", "acme")).code());
    }

    @Test
    void zeroTtlDisablesCaching() {
        var strict = new NumuPrincipalResolver(numu, scheme, clock, Duration.ZERO);
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme", ALL);
        strict.resolve("tok-ann", "acme");
        numu.assignments.clear();
        assertEquals(ApiException.Code.FORBIDDEN, denied(() -> strict.resolve("tok-ann", "acme")).code());
    }

    @Test
    void anInvalidCredentialIsNotCachedSoANewTokenWorksImmediately() {
        assertEquals(ApiException.Code.UNAUTHORIZED, denied(() -> resolver.resolve("tok-new", "acme")).code());
        var ann = numu.user("tok-new");
        numu.grant(ann, "acme", "wayang.run.read");
        assertDoesNotThrow(() -> resolver.resolve("tok-new", "acme"));
    }

    @Test
    void permissionCodesAreStable() {
        assertEquals("wayang.definition.read", scheme.code(Permission.DEFINITION_READ));
        assertEquals("wayang.secret.manage", scheme.code(Permission.SECRET_MANAGE));
        assertEquals("wayang.admin", scheme.code(Permission.ADMIN));
        assertEquals("acme.wayang.project.p1", scheme.projectPath("acme", "p1"));
    }

    @Test
    void theResolvedPrincipalDrivesTheRealApiPermissionChecks() {
        var store = new DefinitionStore.InMemory();
        var waits = new WaitRegistry.InMemory();
        var bus = new EventBus();
        RunEngine engine = new ReferenceRunEngine(store, waits, Map.of(), (spec, in) -> Map.of(), bus, clock);
        var api = new WayangApi(store, engine, waits, bus, clock);

        var viewer = numu.user("tok-viewer");
        numu.grant(viewer, "acme", "wayang.definition.read");
        var designer = numu.user("tok-designer");
        numu.grant(designer, "acme", "wayang.definition.read", "wayang.definition.write");

        Principal v = resolver.resolve("tok-viewer", "acme");
        Principal d = resolver.resolve("tok-designer", "acme");
        Map<String, Object> decision = Map.of("id", "x", "decisions", java.util.List.of(Map.of("name", "o", "type", "table",
                "hitPolicy", "FIRST", "inputs", java.util.List.of("a"), "outputs", java.util.List.of("o"),
                "rules", java.util.List.of(Map.of("when", java.util.List.of("-"), "then", java.util.List.of("\"y\""))))));

        assertEquals(ApiException.Code.FORBIDDEN, denied(() -> api.saveDraft(v, DefinitionKind.DECISION, "x", decision, null)).code());
        assertDoesNotThrow(() -> api.saveDraft(d, DefinitionKind.DECISION, "x", decision, null));
        // the viewer, in the same tenant, can now read it; a principal of another tenant cannot see it
        assertEquals(1, api.list(v, DefinitionKind.DECISION, null).size());
    }

    // ---- project-scoped grants ----

    private NumuPrincipalResolver withProjects(java.util.List<String> ids) {
        return new NumuPrincipalResolver(numu, scheme, clock, Duration.ofSeconds(30), t -> t.equals("acme") ? ids : java.util.List.of());
    }

    @Test
    void aGrantOnlyOnOneProjectGivesProjectAccessAndNoTenantWideAccess() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme.wayang.project.p1", "wayang.definition.read", "wayang.run.start");
        Principal p = withProjects(java.util.List.of("p1", "p2")).resolve("tok-ann", "acme");
        assertFalse(p.can(Permission.DEFINITION_READ), "not tenant-wide");
        assertTrue(p.can(Permission.DEFINITION_READ, "p1"));
        assertTrue(p.can(Permission.RUN_START, "p1"));
        assertFalse(p.can(Permission.DEFINITION_READ, "p2"));
        assertFalse(p.can(Permission.DEFINITION_WRITE, "p1"));
        assertTrue(p.canAnywhere(Permission.RUN_START));
    }

    @Test
    void withoutAProjectListerProjectGrantsAreNotConsultedAndTheTenantIsRefused() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme.wayang.project.p1", "wayang.definition.read");
        assertEquals(ApiException.Code.FORBIDDEN, denied(() -> resolver.resolve("tok-ann", "acme")).code());
    }

    @Test
    void tenantWideGrantsAreNotDuplicatedPerProjectAndAdminOnAProjectImpliesAllThere() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme", "wayang.run.read");
        numu.grant(ann, "acme.wayang.project.p1", "wayang.admin");
        Principal p = withProjects(java.util.List.of("p1", "p2")).resolve("tok-ann", "acme");
        assertTrue(p.can(Permission.RUN_READ));
        assertEquals(java.util.Set.of("p1"), p.projectGrants().keySet());
        assertTrue(p.can(Permission.DEFINITION_WRITE, "p1"));
        assertFalse(p.can(Permission.DEFINITION_WRITE, "p2"));
        assertFalse(p.can(Permission.DEFINITION_WRITE));
    }

    @Test
    void projectIdsThatCannotBeScopeSegmentsAreNeverAskedAbout() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme", "wayang.run.read");
        numu.checked.clear();
        withProjects(java.util.List.of("p1", "p.2", "a b")).resolve("tok-ann", "acme");
        assertTrue(numu.checked.stream().noneMatch(c -> c.contains("p.2") || c.contains("a b")), numu.checked.toString());
        assertTrue(numu.checked.stream().anyMatch(c -> c.startsWith("acme.wayang.project.p1|")));
    }

    @Test
    void manyProjectsAreCheckedInBatchesAndTheCountIsCapped() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme.wayang.project.p150", "wayang.run.read");
        numu.grant(ann, "acme.wayang.project.p250", "wayang.run.read");
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) {
            ids.add("p" + i);
        }
        Principal p = withProjects(ids).resolve("tok-ann", "acme");
        assertTrue(numu.checkCalls > 1, "batched");
        assertTrue(p.can(Permission.RUN_READ, "p150"));
        assertFalse(p.can(Permission.RUN_READ, "p250"), "beyond the cap: fail closed");
    }

    @Test
    void aMalformedAnswerForProjectChecksFailsClosed() {
        var ann = numu.user("tok-ann");
        numu.grant(ann, "acme", "wayang.run.read");
        numu.malformed = true;
        assertEquals(ApiException.Code.UNAVAILABLE, denied(() -> withProjects(java.util.List.of("p1")).resolve("tok-ann", "acme")).code());
    }
}
