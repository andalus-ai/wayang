package tech.kayys.wayang.api.testkit;

import java.time.Instant;
import java.util.function.Supplier;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.ProjectStore;

/** Behavioural contract for {@link ProjectStore} implementations (pass a factory returning an empty store). */
public final class ProjectStoreContract {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private ProjectStoreContract() {
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("ProjectStore contract violated: " + what);
        }
    }

    public static void verify(Supplier<ProjectStore> fresh) {
        ProjectStore s = fresh.get();
        var p = s.create("a", "p1", "First", "desc", "u", T);
        check(p.name().equals("First") && p.createdAt().equals(T) && p.createdBy().equals("u"), "create returns what was stored");
        try {
            s.create("a", "p1", "Dup", null, "u", T);
            throw new AssertionError("ProjectStore contract violated: duplicate id must fail");
        } catch (ApiException e) {
            check(e.code() == ApiException.Code.CONFLICT, "duplicate id is CONFLICT");
        }
        check(s.create("b", "p1", "Other tenant, same id", null, "u", T).tenantId().equals("b"), "ids are per tenant");
        s.create("a", "p2", "Second", null, "u", T.plusSeconds(5));
        var l = s.list("a");
        check(l.size() == 2 && l.get(0).id().equals("p2"), "list is newest first and tenant scoped");
        check(s.get("b", "p2").isEmpty(), "other tenant cannot see it");
        var u = s.update("a", "p1", "Renamed", "new", T.plusSeconds(9)).orElseThrow();
        check(u.name().equals("Renamed") && u.createdAt().equals(T) && u.updatedAt().equals(T.plusSeconds(9)) && u.createdBy().equals("u"),
                "update changes name/description/updatedAt only");
        check(s.update("a", "nope", "x", null, T).isEmpty(), "updating a missing project is empty");
        check(s.update("b", "p2", "x", null, T).isEmpty(), "updating another tenant's project is empty");
        check(s.get("a", "p1").orElseThrow().description().equals("new"), "update is persisted");
        check(!s.delete("b", "p2"), "cannot delete another tenant's project");
        check(s.delete("a", "p2") && !s.delete("a", "p2"), "delete succeeds once");
        check(s.get("a", "p2").isEmpty(), "deleted project is gone");
    }
}
