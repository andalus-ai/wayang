package tech.kayys.wayang.control.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kayys.wayang.control.dto.realtime.ControlPlaneRealtimeEvent;
import tech.kayys.wayang.control.spi.SchemaRegistrySpi;
import tech.kayys.wayang.schema.validator.SchemaValidationService;
import tech.kayys.wayang.schema.validator.ValidationResult;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing schema registration and validation.
 */
@ApplicationScoped
public class SchemaRegistryService implements SchemaRegistrySpi {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaRegistryService.class);

    @Inject
    SchemaValidationService schemaValidationService;

    @Inject
    Event<ControlPlaneRealtimeEvent> realtimeEvents;

    // In-memory storage for schemas - in production, this would use a persistent store
    private final Map<String, String> schemaStore = new ConcurrentHashMap<>();

    @Override
    public Uni<Void> registerSchema(String schemaId, String schema, String schemaType, Map<String, String> metadata) {
        LOG.info("Registering schema: {} of type: {}", schemaId, schemaType);
        
        return Uni.createFrom().item(() -> {
            schemaStore.put(schemaId, schema);
            LOG.info("Schema {} registered successfully", schemaId);
            return null;
        }).onItem().invoke(() -> {
            LOG.debug("Schema registered with metadata: {}", metadata);
            realtimeEvents.fire(new ControlPlaneRealtimeEvent(
                    "schema.registered",
                    "node",
                    schemaId,
                    Map.of(
                            "schemaId", schemaId,
                            "schemaType", schemaType,
                            "metadata", metadata == null ? Map.of() : metadata),
                    Map.of("source", "schema-registry-service"),
                    Set.of("tenant:community", "schema:catalog")));
        })
         .replaceWithVoid();
    }

    @Override
    public Uni<String> getSchema(String schemaId) {
        LOG.debug("Retrieving schema: {}", schemaId);
        
        return Uni.createFrom().item(schemaStore.get(schemaId));
    }

    @Override
    public Uni<ValidationResult> validateAgainstSchema(String schemaId, Map<String, Object> data) {
        LOG.debug("Validating data against schema: {}", schemaId);
        
        String schema = schemaStore.get(schemaId);
        if (schema == null) {
            LOG.warn("Schema not found: {}", schemaId);
            return Uni.createFrom().item(ValidationResult.failure("Schema not found: " + schemaId));
        }
        
        return Uni.createFrom().item(() -> 
            schemaValidationService.validateSchema(schema, data)
        );
    }

    @Override
    public Uni<ValidationResult> validateSchema(String schema, Map<String, Object> data) {
        LOG.debug("Validating data against provided schema");
        
        return Uni.createFrom().item(() -> 
            schemaValidationService.validateSchema(schema, data)
        );
    }

    @Override
    public Uni<Void> removeSchema(String schemaId) {
        LOG.info("Removing schema: {}", schemaId);
        
        return Uni.createFrom().item(() -> {
            schemaStore.remove(schemaId);
            LOG.info("Schema {} removed successfully", schemaId);
            return null;
        }).onItem().invoke(() -> realtimeEvents.fire(new ControlPlaneRealtimeEvent(
                "schema.removed",
                "node",
                schemaId,
                Map.of("schemaId", schemaId),
                Map.of("source", "schema-registry-service"),
                Set.of("tenant:community", "schema:catalog"))))
         .replaceWithVoid();
    }
}
