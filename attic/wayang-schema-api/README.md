# Wayang Schema API Modules

This document describes the gRPC and REST API modules for schema validation in the Wayang platform.

## Overview

The Wayang Schema API modules provide standardized interfaces for validating various data structures used in the platform:

- **gRPC API** - High-performance binary protocol for internal services
- **REST API** - JSON-based HTTP interface for external integrations

Both APIs provide consistent validation capabilities across agent configurations, workflows, and plugin configurations.

## gRPC API

### Service Definition

The gRPC service is defined in `schema_service.proto` and includes the following methods:

- `ValidateSchema` - Validates arbitrary data against a JSON schema
- `ValidateAgentConfig` - Validates agent configuration structures
- `ValidateWorkflow` - Validates workflow structures
- `ValidatePluginConfig` - Validates plugin configuration structures
- `ValidateWithRules` - Validates data with custom validation rules

### Implementation

The service implementation is located in `SchemaGrpcServiceImpl.java` and integrates with the validation services from the schema module.

### Server

The gRPC server is implemented in `GrpcServer.java` and runs on port 9090 by default.

### Usage Example (Java Client)

```java
ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 9090)
    .usePlaintext()
    .build();

SchemaServiceGrpc.SchemaServiceBlockingStub stub = SchemaServiceGrpc.newBlockingStub(channel);

SchemaValidationRequest request = SchemaValidationRequest.newBuilder()
    .setSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}")
    .setData("{\"name\":\"test\"}")
    .build();

SchemaValidationResponse response = stub.validateSchema(request);
System.out.println("Valid: " + response.getValid());
```

## REST API

### Endpoints

The REST API provides the following endpoints under `/v1/schema`:

- `POST /validate` - Validates arbitrary data against a JSON schema
- `POST /validate/agent-config` - Validates agent configuration
- `POST /validate/workflow` - Validates workflow structure
- `POST /validate/plugin-config` - Validates plugin configuration
- `POST /validate-with-rules` - Validates with custom rules

### Request/Response Format

All endpoints accept and return JSON. The standard request format is:

```json
{
  "schema": "...",  // JSON Schema string
  "data": { ... }   // Data to validate
}
```

The standard response format is:

```json
{
  "valid": true/false,
  "message": "Optional error message",
  "issues": [...]   // Array of validation issues
}
```

### Implementation

The REST API is implemented in `SchemaApi.java` using JAX-RS annotations and integrates with the same validation services as the gRPC API.

### Usage Example

```bash
curl -X POST http://localhost:8080/v1/schema/validate \
  -H "Content-Type: application/json" \
  -d '{
    "schema": "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}",
    "data": {"name":"test"}
  }'
```

## Integration with Schema Module

Both APIs leverage the validation services from the `wayang-schema` module:

- `SchemaValidationService` - Core validation interface
- `AgentConfigValidator` - Agent-specific validation
- `WorkflowValidator` - Workflow-specific validation
- `PluginConfigValidator` - Plugin-specific validation

## Dependencies

### gRPC Module Dependencies
- `wayang-schema` - Core schema validation services
- `grpc-netty-shaded` - gRPC server implementation
- `grpc-protobuf` - Protocol buffer support
- `jakarta.annotation-api` - Jakarta EE annotations

### REST API Module Dependencies
- `wayang-schema` - Core schema validation services
- `quarkus-resteasy` - REST framework
- `quarkus-resteasy-jackson` - JSON serialization
- `hibernate-validator` - Validation framework
- `smallrye-openapi` - OpenAPI documentation

## Configuration

### gRPC Server
- Default port: 9090
- Configurable via Quarkus configuration

### REST Server
- Default port: 8080 (standard Quarkus port)
- Configurable via Quarkus configuration

## Error Handling

Both APIs provide consistent error handling:
- Validation failures return appropriate status codes
- Detailed error messages are provided in the response
- Validation issues include field names and severity levels

## Security

- APIs should be secured based on deployment requirements
- Authentication and authorization should be implemented as needed
- Input validation is performed on all requests

## Performance Considerations

- gRPC API offers better performance for internal communications
- REST API is suitable for external integrations
- Both APIs share the same validation logic for consistency