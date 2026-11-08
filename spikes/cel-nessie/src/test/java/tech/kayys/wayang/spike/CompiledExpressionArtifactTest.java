package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CompiledExpressionArtifactTest {

    private static CompiledExpressionArtifact artifact() {
        return CompiledExpressionArtifact.create(
                "customer.score > 10", "bool", "wayang-cel-v1", "nessie-cel-java",
                "0.8.0", "1".repeat(64), "2".repeat(64), "3".repeat(64),
                "4".repeat(64), "policy-7");
    }

    private static CompiledExpressionArtifact.Compatibility compatible() {
        return new CompiledExpressionArtifact.Compatibility(
                1, "bool", "wayang-cel-v1", "nessie-cel-java", "0.8.0",
                "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64), "policy-7");
    }

    @Test
    void createsStableSourceAndArtifactFingerprints() {
        CompiledExpressionArtifact first = artifact();
        CompiledExpressionArtifact second = artifact();

        assertEquals(first.sourceSha256(), second.sourceSha256());
        assertEquals(first.artifactFingerprint(), second.artifactFingerprint());
    }

    @Test
    void exactCompatibilityPasses() {
        artifact().requireCompatible(compatible());
    }

    @Test
    void providerVersionMismatchFailsClosed() {
        CompiledExpressionArtifact.Compatibility oldProvider = new CompiledExpressionArtifact.Compatibility(
                1, "bool", "wayang-cel-v1", "nessie-cel-java", "0.9.0",
                "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64), "policy-7");

        IncompatibleExpressionArtifactException failure = assertThrows(
                IncompatibleExpressionArtifactException.class,
                () -> artifact().requireCompatible(oldProvider));

        assertEquals("providerVersion", failure.field());
    }

    @Test
    void registryOrPolicyChangeInvalidatesCompatibility() {
        CompiledExpressionArtifact.Compatibility changedRegistry = new CompiledExpressionArtifact.Compatibility(
                1, "bool", "wayang-cel-v1", "nessie-cel-java", "0.8.0",
                "a".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64), "policy-7");
        assertThrows(IncompatibleExpressionArtifactException.class,
                () -> artifact().requireCompatible(changedRegistry));
    }

    @Test
    void tamperedSourceDigestIsRejectedAtConstruction() {
        CompiledExpressionArtifact valid = artifact();
        assertThrows(IllegalArgumentException.class, () -> new CompiledExpressionArtifact(
                valid.schemaVersion(), valid.source() + " ", valid.sourceSha256(),
                valid.expectedResultType(), valid.semanticProfile(), valid.providerId(),
                valid.providerVersion(), valid.functionRegistryFingerprint(),
                valid.environmentFingerprint(), valid.variableSchemaFingerprint(),
                valid.compileOptionsFingerprint(), valid.policyVersion(), valid.artifactFingerprint()));
    }
}
