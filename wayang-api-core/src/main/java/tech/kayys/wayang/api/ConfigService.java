package tech.kayys.wayang.api;

import java.time.Clock;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ConfigResolver;

/**
 * Plain settings for the environment a workspace runs in: used in node configuration as {@code ${config:NAME}} and moved with a
 * workspace bundle, so the same definitions run against a different base URL, channel or threshold in each environment.
 *
 * <p>Two scopes: the whole tenant, or one project (which wins when a process of that project asks). Not for credentials: keys that
 * look like one are refused, and values may not contain {@code ${...}} (they are never re-expanded, so it would only mislead).
 * Reading needs definition-read, writing definition-write, on the project or tenant-wide.
 */
public final class ConfigService implements ConfigResolver {

    public static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    public static final Pattern CREDENTIAL_LIKE = Pattern.compile("(?i).*(token|secret|password|passwd|apikey|api_key|private|credential).*");
    public static final int MAX_VALUE_CHARS = 4096;
    public static final int MAX_PER_SCOPE = 500;

    private final ConfigStore store;
    private final Predicate<String[]> projectExists; // {tenant, projectId}
    private final BiFunction<String, String, String> projectOfProcess; // (tenant, processId) -> projectId or null
    private final Clock clock;

    public ConfigService(ConfigStore store, Predicate<String[]> projectExists, BiFunction<String, String, String> projectOfProcess, Clock clock) {
        this.store = store;
        this.projectExists = projectExists;
        this.projectOfProcess = projectOfProcess;
        this.clock = clock;
    }

    public ConfigEntry put(Principal p, String scope, String key, String value) {
        String sc = scope(p, scope, Permission.DEFINITION_WRITE);
        checkKey(key);
        checkValue(value);
        return write(p.tenantId(), sc, key, value, p.userId());
    }

    /** Writes without the caller's permissions: the workspace import has already checked them. */
    ConfigEntry write(String tenant, String sc, String key, String value, String by) {
        for (int attempt = 0; attempt < 5; attempt++) {
            var cur = store.get(tenant, sc, key);
            if (cur.isEmpty() && store.list(tenant, sc).size() >= MAX_PER_SCOPE) {
                throw ApiException.badRequest("At most " + MAX_PER_SCOPE + " settings per scope");
            }
            int curV = cur.map(ConfigEntry::version).orElse(0);
            ConfigEntry e = new ConfigEntry(tenant, sc, key, value, curV + 1, clock.instant(), by);
            if (store.put(e, curV)) {
                return e;
            }
        }
        throw new ApiException(ApiException.Code.CONFLICT, "Concurrent update of setting " + key);
    }

    public List<ConfigEntry> list(Principal p, String scope) {
        String sc = scope(p, scope, Permission.DEFINITION_READ);
        return store.list(p.tenantId(), sc);
    }

    public void delete(Principal p, String scope, String key) {
        String sc = scope(p, scope, Permission.DEFINITION_WRITE);
        checkKey(key);
        if (!store.delete(p.tenantId(), sc, key)) {
            throw ApiException.notFound("setting " + key);
        }
    }

    /** A deleted project takes its settings with it, so a later project that reuses the id does not inherit them. */
    public int dropProject(Principal p, String projectId) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.can(Permission.DEFINITION_WRITE)) {
            throw ApiException.forbidden(Permission.DEFINITION_WRITE);
        }
        if (projectId == null || projectId.isEmpty()) {
            throw ApiException.badRequest("A project id is required");
        }
        return store.deleteScope(p.tenantId(), projectId);
    }

    /** Runtime-only: the project's value when the process belongs to a project that sets it, else the tenant's. */
    @Override
    public String resolve(String tenantId, String processId, String name) {
        if (tenantId == null || name == null || !KEY.matcher(name).matches()) {
            return null;
        }
        if (processId != null) {
            String project = projectOfProcess.apply(tenantId, processId);
            if (project != null) {
                var v = store.get(tenantId, project, name);
                if (v.isPresent()) {
                    return v.get().value();
                }
            }
        }
        return store.get(tenantId, "", name).map(ConfigEntry::value).orElse(null);
    }

    private String scope(Principal p, String scope, Permission perm) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        String sc = scope == null ? "" : scope;
        if (sc.isEmpty()) {
            if (perm == Permission.DEFINITION_READ ? !p.canAnywhere(perm) : !p.can(perm)) {
                throw ApiException.forbidden(perm);
            }
            return "";
        }
        if (!p.can(perm, sc)) {
            throw ApiException.forbidden(perm);
        }
        if (!projectExists.test(new String[] { p.tenantId(), sc })) {
            throw ApiException.notFound("project " + sc);
        }
        return sc;
    }

    static void checkKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ApiException.badRequest("Setting name must match " + KEY.pattern());
        }
        if (CREDENTIAL_LIKE.matcher(key).matches()) {
            throw ApiException.badRequest("'" + key + "' looks like a credential: store it as a secret and use ${secret:NAME}");
        }
    }

    static void checkValue(String value) {
        if (value == null || value.length() > MAX_VALUE_CHARS) {
            throw ApiException.badRequest("Setting value must be 0.." + MAX_VALUE_CHARS + " characters");
        }
        if (value.contains("${")) {
            throw ApiException.badRequest("A setting value may not contain '${': references are not expanded inside values");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\t') {
                throw ApiException.badRequest("A setting value may not contain control characters");
            }
        }
    }
}
