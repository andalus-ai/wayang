# Wayang Server-Side Schema Models

This document describes the complete server-side Java schema models that correspond to the UI schema models in the Wayang platform.

## Overview

The server-side schema models provide Java representations of the data structures used in the Wayang platform. These models are designed to be compatible with JSON serialization/deserialization and follow standard Java conventions.

## Package Structure

The schema models are organized into the following packages:

- `tech.kayys.wayang.schema.common` - Common utility models used across the platform
- `tech.kayys.wayang.schema.agent` - Models related to AI agents
- `tech.kayys.wayang.schema.workflow` - Models related to workflows
- `tech.kayys.wayang.schema.node` - Models related to workflow nodes
- `tech.kayys.wayang.schema.tool` - Models related to tools
- `tech.kayys.wayang.schema.memory` - Models related to memory systems
- `tech.kayys.wayang.schema.data` - Models related to data processing
- `tech.kayys.wayang.schema.model` - Models related to AI/ML models

## Common Models

### RateLimit
Represents rate limiting configuration for API calls or operations.

### Metadata
Represents metadata associated with various entities in the system.

### Position
Represents a 2D position with x and y coordinates.

### RetryPolicy
Configuration for retry policies in case of failures.

### CircuitBreaker
Configuration for circuit breaker pattern to handle failures gracefully.

### FileHandling
Configuration for file handling in the system.

### ThreadPoolProfile
Configuration for thread pool profiles.

### PromptTemplate
Represents a template for prompts used by AI models.

## Agent Models

### AgentConfig
Configuration for an AI agent in the system, including model parameters, rate limits, retry policies, and other settings.

## Workflow Models

### Workflow
Represents a workflow containing nodes and connections.

### Connection
Represents a connection between two nodes in a workflow.

### WorkflowConfig
Configuration options for a workflow, including timeouts, retries, and error handling strategies.

## Node Models

### Node
Represents a node in a workflow, with metadata, type, position, inputs, outputs, and configuration.

## Tool Models

### Tool
Represents a tool that can be used by agents in the system.

## Memory Models

### Memory
Represents a memory system for storing and retrieving information.

## Data Models

### DataSchema
Represents data processing configuration and schemas.

### Field
Represents a field in a data schema.

### FieldConstraints
Represents constraints for a field in a data schema.

### DataSource
Represents a data source configuration.

### DataTransformation
Represents a data transformation operation.

### ValidationRule
Represents a validation rule for data.

## Model Models

### Model
Represents a machine learning or AI model configuration.

### ModelCapabilities
Represents the capabilities of a model.

## Serialization

All models are annotated with Jackson annotations to support JSON serialization and deserialization:

- `@JsonProperty` - Maps Java properties to JSON fields
- Standard getters and setters for all properties
- Default constructors for deserialization

## Usage Example

```java
ObjectMapper mapper = new ObjectMapper();
Workflow workflow = mapper.readValue(jsonString, Workflow.class);
String json = mapper.writeValueAsString(workflow);
```

## Extending the Schema

To add new models:

1. Create the model class in the appropriate package
2. Add Jackson annotations for proper serialization
3. Include appropriate getter/setter methods
4. Add necessary imports for other schema models if needed

## Validation

Models can be extended with validation annotations from Bean Validation (JSR 303) if needed for server-side validation:

```java
@JsonProperty("name")
@NotBlank
private String name;
```

## Relationship to UI Schema

These server-side models correspond to the Dart models in the UI schema (`wayang-ui/wayang_kulit/lib/schema`) and maintain structural compatibility to ensure seamless data exchange between client and server.