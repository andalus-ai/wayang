package tech.kayys.wayang.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySecretStore implements SecretStore {
    private final Map<String, SealedSecret> m = new ConcurrentHashMap<>();

    private static String k(String t, String n) {
        return t + "\0" + n;
    }

    @Override
    public Optional<SealedSecret> get(String tenant, String name) {
        return Optional.ofNullable(m.get(k(tenant, name)));
    }

    @Override
    public List<SealedSecret> list(String tenant) {
        List<SealedSecret> out = new ArrayList<>();
        m.values().stream().filter(s -> s.tenant().equals(tenant)).forEach(out::add);
        out.sort(Comparator.comparing(SealedSecret::name));
        return out;
    }

    @Override
    public boolean put(SealedSecret s, int expectedVersion) {
        boolean[] ok = {false};
        m.compute(k(s.tenant(), s.name()), (key, cur) -> {
            int curV = cur == null ? 0 : cur.version();
            if (curV != expectedVersion) {
                return cur;
            }
            ok[0] = true;
            return s;
        });
        return ok[0];
    }

    @Override
    public boolean reseal(SealedSecret s) {
        boolean[] ok = {false};
        m.computeIfPresent(k(s.tenant(), s.name()), (key, cur) -> {
            if (cur.version() != s.version()) {
                return cur;
            }
            ok[0] = true;
            return s;
        });
        return ok[0];
    }

    @Override
    public boolean delete(String tenant, String name) {
        return m.remove(k(tenant, name)) != null;
    }
}
