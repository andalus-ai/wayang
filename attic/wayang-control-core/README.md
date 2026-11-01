## Control Plane



1. 

SchemaRegistryService
 (The Validator)
This service acts as a central repository for JSON Schemas (the rules) and is responsible for validating that incoming data matches those rules.

Core Function: Registering JSON Schema definitions and validating dynamic payloads (like tool executions, agent configurations, or node inputs/outputs) against those schemas.
Storage: Currently, it uses an in-memory ConcurrentHashMap (schemaStore) to temporarily hold schemas during runtime.
SPI Implementation: It explicitly implements tech.kayys.wayang.control.spi.SchemaRegistrySpi, making it a pluggable component that other modules via the SPI can use to validate arbitrary map data.
Analogy: It is the "Building Inspector" checking if a blueprint meets safety codes.
2. 

WayangDefinitionService
 (The Storage & Lifecycle Manager)
This service manages the primary domain entity, 

WayangDefinition
, which holds the actual user-created workflows, agents, and canvas layouts.

Core Function: Manages the persistence (CRUD) and lifecycle of the workflows. It handles things like versioning (versionNumber), locking for collaboration, publishing workflows to production states, and "forking" (branching) a workflow into a new copy.
Storage: It uses Hibernate Reactive Panache to persistently store the data into the PostgreSQL database, leveraging the jsonb column for the core 

WayangSpec
.
Focus: It's deeply tied to business logic, tenant isolation (tenantId, projectId), and tracking who did what and when (auditing fields like createdBy, updatedAt).
Analogy: It is the "Filing Cabinet & Version Control System" where the blueprints are stored, versioned, and shared among users.
How they work together:
In a complete flow, when a user tries to save a new agent or workflow via the 

WayangDefinitionService
, the platform might first call the 

SchemaRegistryService
 to validate the interior JSON (

WayangSpec
) against the official Wayang schema rules before allowing it to be permanently saved to the database!