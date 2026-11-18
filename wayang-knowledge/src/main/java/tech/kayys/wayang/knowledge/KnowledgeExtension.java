package tech.kayys.wayang.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.runtime.ComponentDescriptor;
import tech.kayys.wayang.runtime.Profile;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WayangExtension;

/** Adds the {@code rag-ingest} and {@code rag-search} connectors; loaded only when the profile enables the KNOWLEDGE feature. */
public final class KnowledgeExtension implements WayangExtension {

    private final KnowledgeRegistry registry = new KnowledgeRegistry();

    @Override
    public String id() {
        return "knowledge";
    }

    @Override
    public Set<Profile.Feature> requires() {
        return Set.of(Profile.Feature.KNOWLEDGE);
    }

    @Override
    public Map<String, ServiceHandler.Connector> connectors() {
        return Map.of("rag-ingest", new RagIngest(registry), "rag-search", new RagSearch(registry));
    }

    @Override
    public List<ComponentDescriptor> components() {
        return List.of(
                component("wayang.knowledge.rag-ingest", "Add documents (txt, md, html, csv, docx, xlsx, pptx) to a knowledge base", "knowledge.ingest"),
                component("wayang.knowledge.rag-search", "Retrieve passages from a knowledge base (bm25, vector, hybrid)", "knowledge.search"));
    }

    private static ComponentDescriptor component(String id, String description, String capability) {
        return new ComponentDescriptor(id, "0.0.1", ComponentDescriptor.Kind.CONNECTOR, "wayang-service",
                description, Set.of(Profile.Feature.KNOWLEDGE), List.of(),
                ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of(capability));
    }
}
