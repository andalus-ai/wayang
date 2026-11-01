package tech.kayys.wayang.control.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.wayang.control.domain.WayangDefinition;
import tech.kayys.wayang.control.domain.WayangProject;
import tech.kayys.wayang.control.dto.CatalogTemplate;
import tech.kayys.wayang.control.dto.EIPPatternType;
import tech.kayys.wayang.control.dto.PatternCatalogEntry;
import tech.kayys.wayang.control.dto.TemplateType;
import tech.kayys.wayang.schema.DefinitionType;
import tech.kayys.wayang.schema.canvas.CanvasData;
import tech.kayys.wayang.schema.canvas.CanvasNode;
import tech.kayys.wayang.schema.layout.Position;

/**
 * Template and pattern catalog service
 */
@ApplicationScoped
public class TemplateCatalogService {

        private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(TemplateCatalogService.class);

        // Built-in templates
        private final List<CatalogTemplate> builtInTemplates = initializeBuiltInTemplates();

        public Uni<List<CatalogTemplate>> browseTemplates(String category, String searchTerm) {
                LOG.info("Browse templates: category={}, searchTerm={}", category, searchTerm);
                return Uni.createFrom().item(() -> {
                        var stream = builtInTemplates.stream();

                        if (category != null) {
                                stream = stream.filter(t -> t.category().equals(category));
                        }

                        if (searchTerm != null) {
                                stream = stream.filter(t -> t.name().toLowerCase().contains(searchTerm.toLowerCase()) ||
                                                t.description().toLowerCase().contains(searchTerm.toLowerCase()));
                        }

                        return stream.toList();
                });
        }

        public Uni<CatalogTemplate> getTemplate(String templateId) {
                return Uni.createFrom().item(() -> builtInTemplates.stream()
                                .filter(t -> t.id().equals(templateId))
                                .findFirst()
                                .orElse(null));
        }

        public Uni<WayangDefinition> cloneToProject(String templateId, UUID projectId) {
                return getTemplate(templateId)
                                .flatMap(catalogTemplate -> {
                                        if (catalogTemplate == null) {
                                                return Uni.createFrom().failure(
                                                                new IllegalArgumentException("Template not found"));
                                        }

                                        // Clone catalog template to project template
                                        WayangDefinition cloned = new WayangDefinition();
                                        cloned.name = catalogTemplate.name() + " (Copy)";
                                        cloned.description = catalogTemplate.description();
                                        cloned.version = "1.0.0";
                                        cloned.definitionType = DefinitionType
                                                        .valueOf(catalogTemplate.templateType().name());

                                        tech.kayys.wayang.schema.WayangSpec spec = new tech.kayys.wayang.schema.WayangSpec();
                                        spec.setCanvas(catalogTemplate.canvasData());
                                        cloned.spec = spec;

                                        cloned.tags = new java.util.ArrayList<>(catalogTemplate.tags());
                                        cloned.createdAt = java.time.Instant.now();

                                        return io.quarkus.hibernate.reactive.panache.Panache
                                                        .withTransaction(
                                                                        () -> WayangProject.<WayangProject>findById(
                                                                                        projectId)
                                                                                        .flatMap(project -> {
                                                                                                cloned.projectId = project.projectId;
                                                                                                return cloned.persist()
                                                                                                                .map(t -> (WayangDefinition) t);
                                                                                        }));
                                });
        }

        public Uni<List<PatternCatalogEntry>> browsePatterns(String category) {
                return Uni.createFrom().item(() -> getBuiltInPatterns().stream()
                                .filter(p -> category == null || p.category().equals(category))
                                .toList());
        }

        private List<CatalogTemplate> initializeBuiltInTemplates() {
                List<CatalogTemplate> templates = new ArrayList<>();

                // AI Agent templates
                templates.add(new CatalogTemplate(
                                "ai-customer-support",
                                "AI Customer Support Agent",
                                "Autonomous agent for handling customer support inquiries",
                                "AI Agents",
                                TemplateType.AI_AGENT_WORKFLOW,
                                createCustomerSupportCanvas(),
                                List.of("ai", "customer-support", "chatbot"),
                                "beginner"));

                templates.add(new CatalogTemplate(
                                "ai-data-analyst",
                                "AI Data Analysis Agent",
                                "Agent for analyzing data and generating insights",
                                "AI Agents",
                                TemplateType.AI_AGENT_WORKFLOW,
                                createDataAnalystCanvas(),
                                List.of("ai", "data-analysis", "analytics"),
                                "intermediate"));

                // Integration templates
                templates.add(new CatalogTemplate(
                                "api-to-database",
                                "API to Database Sync",
                                "Sync data from REST API to database",
                                "Integration",
                                TemplateType.EIP_PATTERN,
                                createApiToDatabaseCanvas(),
                                List.of("integration", "api", "database"),
                                "beginner"));

                templates.add(new CatalogTemplate(
                                "event-driven-etl",
                                "Event-Driven ETL Pipeline",
                                "Process events and transform data for analytics",
                                "Integration",
                                TemplateType.EIP_PATTERN,
                                createETLCanvas(),
                                List.of("integration", "etl", "kafka"),
                                "advanced"));

                // Automation templates
                templates.add(new CatalogTemplate(
                                "approval-workflow",
                                "Multi-Level Approval Workflow",
                                "Hierarchical approval process with escalation",
                                "Automation",
                                TemplateType.AUTOMATION,
                                createApprovalCanvas(),
                                List.of("automation", "approval", "human-task"),
                                "beginner"));

                return templates;
        }

        private List<PatternCatalogEntry> getBuiltInPatterns() {
                return List.of(
                                new PatternCatalogEntry(
                                                "content-router",
                                                "Content-Based Router",
                                                "Route messages based on content",
                                                "Routing",
                                                EIPPatternType.CONTENT_BASED_ROUTER,
                                                "Route messages to different destinations based on message content"),
                                new PatternCatalogEntry(
                                                "message-translator",
                                                "Message Translator",
                                                "Transform message format",
                                                "Transformation",
                                                EIPPatternType.MESSAGE_TRANSLATOR,
                                                "Convert message from one format to another"),
                                new PatternCatalogEntry(
                                                "splitter-aggregator",
                                                "Splitter-Aggregator",
                                                "Split and aggregate messages",
                                                "Routing",
                                                EIPPatternType.SPLITTER,
                                                "Split message into parts, process, and aggregate results"));
        }

        // Canvas creation helpers
        private CanvasData createCustomerSupportCanvas() {
                CanvasData canvasData = new CanvasData();

                // Add nodes
                CanvasNode startNode = new CanvasNode();
                startNode.id = "start-1";
                startNode.type = "AI_AGENT";
                startNode.label = "Support Agent";
                startNode.config = Map.of(
                                "llmProvider", "openai",
                                "model", "gpt-4",
                                "systemPrompt", "You are a helpful customer support agent.");
                startNode.position = new Position(100, 100);

                canvasData.nodes.add(startNode);

                return canvasData;
        }

        private CanvasData createDataAnalystCanvas() {
                return new CanvasData();
        }

        private CanvasData createApiToDatabaseCanvas() {
                return new CanvasData();
        }

        private CanvasData createETLCanvas() {
                return new CanvasData();
        }

        private CanvasData createApprovalCanvas() {
                return new CanvasData();
        }
}
