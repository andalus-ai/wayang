package tech.kayys.wayang.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ReleaseArtifactCatalogTest {

    @Test
    void resolvesAStableTransitiveClosureFromTheEmbeddedEntryPoint() {
        var first = ReleaseArtifactCatalog.resolve(List.of("wayang.service.connector", "wayang.agent.invoke"), "0.0.1");
        var second = ReleaseArtifactCatalog.resolve(List.of("wayang.agent.invoke", "wayang.service.connector"), "0.0.1");

        assertEquals(first, second);
        var ids = first.stream().map(ReleaseArtifactCatalog.Artifact::artifactId).toList();
        assertTrue(ids.containsAll(List.of("wayang-embed", "wayang-runtime", "wayang-connectors",
                "wayang-agent", "wayang-expression", "wayang-dmn", "wayang-business-process", "wayang-integration")));
    }

    @Test
    void refusesUnmappedExtensionComponentsInsteadOfProducingAnIncompleteLock() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> ReleaseArtifactCatalog.resolve(List.of("vendor.custom.connector"), "0.0.1"));
        assertTrue(error.getMessage().contains("No Maven artifact mapping"));
    }

    @Test
    void gamelanUsesTheWorkerAsItsArtifactRoot() {
        var roots = ReleaseArtifactCatalog.roots(List.of("wayang.bpmn.control-flow"), true);
        assertEquals(List.of("wayang-business-process", "wayang-gamelan-worker"), roots);
    }
}
