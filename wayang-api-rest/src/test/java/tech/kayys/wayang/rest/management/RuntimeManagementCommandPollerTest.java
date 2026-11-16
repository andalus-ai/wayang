package tech.kayys.wayang.rest.management;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RuntimeManagementCommandPollerTest {
    @Test
    void indexesExplicitHandlerAllowList() {
        RuntimeManagementCommandHandler apply = handler(Set.of("APPLY_DEPLOYMENT", "APPLY_RELEASE"));
        var indexed = RuntimeManagementCommandPoller.indexHandlers(List.of(apply));

        assertSame(apply, indexed.get("APPLY_DEPLOYMENT"));
        assertSame(apply, indexed.get("APPLY_RELEASE"));
        assertThrows(UnsupportedOperationException.class, () -> indexed.put("OTHER", apply));
    }

    @Test
    void rejectsAmbiguousAndEmptyHandlerDeclarations() {
        RuntimeManagementCommandHandler first = handler(Set.of("APPLY_DEPLOYMENT"));
        RuntimeManagementCommandHandler second = handler(Set.of("APPLY_DEPLOYMENT"));

        assertThrows(IllegalStateException.class,
                () -> RuntimeManagementCommandPoller.indexHandlers(List.of(first, second)));
        assertThrows(IllegalStateException.class,
                () -> RuntimeManagementCommandPoller.indexHandlers(List.of(handler(Set.of()))));
    }

    @Test
    void deploymentSuccessRequiresSha256Digest() {
        assertTrue(RuntimeManagementCommandPoller.isSha256Digest("sha256:" + "a".repeat(64)));
        assertTrue(RuntimeManagementCommandPoller.isSha256Digest("SHA256:" + "A".repeat(64)));
        assertFalse(RuntimeManagementCommandPoller.isSha256Digest(null));
        assertFalse(RuntimeManagementCommandPoller.isSha256Digest("sha256:abc"));
        assertFalse(RuntimeManagementCommandPoller.isSha256Digest("sha256:" + "g".repeat(64)));
    }

    private static RuntimeManagementCommandHandler handler(Set<String> types) {
        return new RuntimeManagementCommandHandler() {
            @Override public Set<String> commandTypes() { return types; }
            @Override public CommandResult execute(Command command) {
                return new CommandResult(true, "ok", null);
            }
        };
    }
}
