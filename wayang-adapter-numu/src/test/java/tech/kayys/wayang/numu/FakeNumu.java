package tech.kayys.wayang.numu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** In-memory Numu following its real rule: an assignment at S applies to path P when P == S or P starts with S + ".". */
final class FakeNumu implements NumuIdentity {

    record Assignment(UUID principal, String scopePath, Set<String> permissions) {
    }

    final Map<String, Resolved> credentials = new HashMap<>();
    final List<Assignment> assignments = new ArrayList<>();
    boolean down;
    boolean malformed;
    int resolveCalls;
    int checkCalls;
    final List<String> checked = new ArrayList<>();

    Resolved user(String credential) {
        Resolved r = new Resolved(PrincipalType.USER, UUID.randomUUID(), "User " + credential);
        credentials.put(credential, r);
        return r;
    }

    Resolved service(String credential) {
        Resolved r = new Resolved(PrincipalType.SERVICE_ACCOUNT, UUID.randomUUID(), "svc");
        credentials.put(credential, r);
        return r;
    }

    void grant(Resolved who, String scopePath, String... permissions) {
        assignments.add(new Assignment(who.id(), scopePath, Set.of(permissions)));
    }

    @Override
    public Optional<Resolved> resolve(String credential) {
        resolveCalls++;
        if (down) {
            throw new IdentityUnavailableException("down", null);
        }
        return Optional.ofNullable(credentials.get(credential));
    }

    @Override
    public List<Boolean> check(Resolved principal, List<Check> checks) {
        checkCalls++;
        if (down) {
            throw new IdentityUnavailableException("down", null);
        }
        if (malformed) {
            return List.of();
        }
        List<Boolean> out = new ArrayList<>();
        for (Check c : checks) {
            checked.add(c.path() + "|" + c.permission());
            boolean allowed = assignments.stream().anyMatch(a -> a.principal().equals(principal.id())
                    && (a.scopePath().equals(c.path()) || c.path().startsWith(a.scopePath() + "."))
                    && a.permissions().contains(c.permission()));
            out.add(allowed);
        }
        return out;
    }
}
