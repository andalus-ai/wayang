package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FunctionRegistryTest {

    private static FunctionDescriptor descriptor(
            String overloadId,
            String implementationVersion,
            FunctionDescriptor.Effect effect,
            FunctionDescriptor.Determinism determinism) {
        return new FunctionDescriptor(
                "wayang.math", "wayang_is_even", "1.0.0", overloadId,
                List.of("int"), "bool", effect, determinism, implementationVersion);
    }

    @Test
    void registryIsExplicitAndRejectsUnknownOverloads() {
        FunctionRegistry registry = FunctionRegistry.builder()
                .register(descriptor("wayang_is_even_int", "1.0.0",
                        FunctionDescriptor.Effect.PURE,
                        FunctionDescriptor.Determinism.DETERMINISTIC))
                .build();

        assertEquals(1, registry.descriptors().size());
        assertThrows(IllegalArgumentException.class,
                () -> registry.require("wayang_not_registered"));
    }

    @Test
    void duplicateOverloadIdsFailClosed() {
        FunctionRegistry.Builder builder = FunctionRegistry.builder()
                .register(descriptor("wayang_is_even_int", "1.0.0",
                        FunctionDescriptor.Effect.PURE,
                        FunctionDescriptor.Determinism.DETERMINISTIC));

        assertThrows(IllegalArgumentException.class,
                () -> builder.register(descriptor("wayang_is_even_int", "2.0.0",
                        FunctionDescriptor.Effect.PURE,
                        FunctionDescriptor.Determinism.DETERMINISTIC)));
    }

    @Test
    void unsafeFunctionsCannotEnterExpressionSafeProfile() {
        FunctionRegistry registry = FunctionRegistry.builder()
                .register(descriptor("wayang_read_customer_int", "1.0.0",
                        FunctionDescriptor.Effect.READ_ONLY,
                        FunctionDescriptor.Determinism.CONTEXTUAL))
                .build();

        assertThrows(IllegalStateException.class, registry::requireExpressionSafe);
    }

    @Test
    void compatibilityFingerprintIsStableAndTracksImplementationVersion() {
        FunctionRegistry first = FunctionRegistry.builder()
                .register(descriptor("wayang_is_even_int", "1.0.0",
                        FunctionDescriptor.Effect.PURE,
                        FunctionDescriptor.Determinism.DETERMINISTIC))
                .build();
        FunctionRegistry same = FunctionRegistry.builder()
                .register(descriptor("wayang_is_even_int", "1.0.0",
                        FunctionDescriptor.Effect.PURE,
                        FunctionDescriptor.Determinism.DETERMINISTIC))
                .build();
        FunctionRegistry changed = FunctionRegistry.builder()
                .register(descriptor("wayang_is_even_int", "1.1.0",
                        FunctionDescriptor.Effect.PURE,
                        FunctionDescriptor.Determinism.DETERMINISTIC))
                .build();

        assertEquals(first.compatibilityFingerprint(), same.compatibilityFingerprint());
        assertNotEquals(first.compatibilityFingerprint(), changed.compatibilityFingerprint());
        assertTrue(first.compatibilityFingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    void builderCannotBeMutatedAfterFreeze() {
        FunctionRegistry.Builder builder = FunctionRegistry.builder();
        builder.build();
        assertThrows(IllegalStateException.class,
                () -> builder.register(descriptor("wayang_is_even_int", "1.0.0",
                        FunctionDescriptor.Effect.PURE,
                        FunctionDescriptor.Determinism.DETERMINISTIC)));
    }
}
