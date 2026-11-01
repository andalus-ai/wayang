package tech.kayys.wayang.schema.catalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import tech.kayys.wayang.plugin.spi.node.NodeDefinition;
import tech.kayys.wayang.plugin.spi.node.NodeProvider;
import tech.kayys.wayang.schema.WayangSpec;
import tech.kayys.wayang.schema.generator.SchemaGeneratorUtils;
import tech.kayys.wayang.schema.vector.VectorSearchConfig;
import tech.kayys.wayang.schema.vector.VectorUpsertConfig;
import tech.kayys.wayang.schema.workflow.WorkflowSpec;

/**
 * Unified JSON Schema catalog.
 *
 * Core schemas (workflow, plugin-config) are registered inline.
 * Node schemas are discovered at runtime via {@link NodeProvider}.
 * Standalone schemas are discovered via {@link SchemaProvider}.
 */
public final class BuiltinSchemaCatalog {

  public static final String WORKFLOW = "workflow";
  public static final String WORKFLOW_SPEC = "workflow-spec";
  public static final String PLUGIN_CONFIG = "plugin-config";
  public static final String WAYANG_SPEC = "wayang-spec";

  // Vector Executor
  public static final String VECTOR_SEARCH = "vector-search-node";
  public static final String VECTOR_UPSERT = "vector-upsert-node";

  // Graph Executor
  public static final String GRAPH_QUERY = "graph-query-node";
  public static final String GRAPH_UPSERT = "graph-upsert-node";

  // Agent
  public static final String AGENT_CONFIG = "agent-config";
  public static final String AGENT_ORCHESTRATOR = "agent-orchestrator";
  public static final String AGENT_ANALYTIC = "agent-analytic";
  public static final String AGENT_CODER = "agent-coder";
  public static final String AGENT_EVALUATOR = "agent-evaluator";
  public static final String AGENT_PLANNER = "agent-planner";
  public static final String AGENT_BASIC = "agent-basic";

  // HITL
  public static final String HITL_HUMAN_TASK = "hitl-human-task";

  // EIP
  public static final String EIP_ENDPOINT = "eip-endpoint";
  public static final String EIP_ROUTER = "eip-router";
  public static final String EIP_FILTER = "eip-filter";
  public static final String EIP_AGGREGATOR = "eip-aggregator";
  public static final String EIP_SPLITTER = "eip-splitter";
  public static final String EIP_TRANSFORMER = "eip-transformer";
  public static final String EIP_ENRICHER = "eip-enricher";
  public static final String EIP_RETRY = "eip-retry";
  public static final String EIP_THROTTLER = "eip-throttler";
  public static final String EIP_DEBUG_PRINT = "eip-debug-print";
  public static final String DEAD_LETTER_CHANNEL = "dead-letter-channel";

  // Trigger Source Nodes
  public static final String TRIGGER_START = "start";
  public static final String TRIGGER_MANUAL = "trigger-manual";
  public static final String TRIGGER_SCHEDULE = "trigger-schedule";
  public static final String TRIGGER_EMAIL = "trigger-email";
  public static final String TRIGGER_TELEGRAM = "trigger-telegram";
  public static final String TRIGGER_WEBSOCKET = "trigger-websocket";
  public static final String TRIGGER_WEBHOOK = "trigger-webhook";
  public static final String TRIGGER_EVENT = "trigger-event";
  public static final String TRIGGER_KAFKA = "trigger-kafka";
  public static final String TRIGGER_FILE = "trigger-file";
  public static final String SUB_WORKFLOW_NODE = "sub-workflow";
  public static final String CUSTOM_AGENT_NODE = "custom-agent-node";

  private static final String PLUGIN_CONFIG_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "id": { "type": "string", "pattern": "^[a-zA-Z][a-zA-Z0-9_-]*$" },
          "name": { "type": "string" },
          "version": { "type": "string" },
          "description": { "type": "string" },
          "provider": { "type": "string" },
          "capabilities": {
            "type": "array",
            "items": { "type": "string" }
          },
          "configuration": { "type": "object" }
        },
        "required": ["id", "name", "version"]
      }
      """;

  private static final String AGENT_BASE_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "name": { "type": "string" },
          "description": { "type": "string" },
          "systemPrompt": { "type": "string" },
          "maxIterations": { "type": "integer", "minimum": 1 },
          "maxDelegations": { "type": "integer", "minimum": 1 },
          "maxLatencyMs": { "type": "integer", "minimum": 1 },
          "maxAgentLatencyMs": { "type": "integer", "minimum": 1 },
          "maxRetriesPerDelegation": { "type": "integer", "minimum": 0 },
          "temperature": { "type": "number", "minimum": 0, "maximum": 2 },
          "model": { "type": "string" },
          "providerMode": { "type": "string", "enum": ["auto", "local", "cloud"] },
          "provider": { "type": "string" },
          "preferredProvider": { "type": "string" },
          "fallbackProvider": { "type": "string" },
          "localProvider": {
            "type": "object",
            "properties": {
              "providerId": { "type": "string" },
              "model": { "type": "string" },
              "endpoint": { "type": "string" },
              "runtime": { "type": "string" }
            },
            "additionalProperties": true
          },
          "cloudProvider": {
            "type": "object",
            "properties": {
              "providerId": { "type": "string" },
              "model": { "type": "string" },
              "endpoint": { "type": "string" },
              "region": { "type": "string" }
            },
            "additionalProperties": true
          },
          "credentialRefs": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "backend": { "type": "string", "enum": ["local", "vault", "aws", "azure"] },
                "path": { "type": "string" },
                "key": { "type": "string" },
                "version": { "type": "integer", "minimum": 1 }
              },
              "required": ["name", "path"],
              "additionalProperties": true
            }
          },
          "vault": {
            "type": "object",
            "properties": {
              "backend": { "type": "string", "enum": ["local", "vault", "aws", "azure"] },
              "tenantId": { "type": "string" },
              "pathPrefix": { "type": "string" }
            },
            "additionalProperties": true
          },
          "tools": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "toolId": { "type": "string" },
                "name": { "type": "string" },
                "description": { "type": "string" },
                "type": { "type": "string", "enum": ["WEB_SEARCH", "MCP", "FUNCTION_CALL", "API_CALL", "DATABASE_QUERY", "FILE_OPERATION", "CODE_EXECUTION", "CUSTOM"] },
                "endpointUrl": { "type": "string" },
                "enabled": { "type": "boolean", "default": true },
                "serverId": { "type": "string" },
                "parametersSchema": { "type": "string" },
                "configuration": { "type": "object", "additionalProperties": true },
                "metadata": { "type": "object", "additionalProperties": true }
              },
              "required": ["name", "type"]
            }
          },
          "goal": { "type": "string" },
          "strategy": { "type": "string" },
          "candidateOutput": { "type": "string" },
          "criteria": { "type": "string" },
          "memory": {
            "type": "object",
            "additionalProperties": true
          }
        },
        "additionalProperties": true
      }
      """;

  private static final String BUILTIN_TOOL_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "toolId": { "type": "string" },
          "name": { "type": "string" },
          "description": { "type": "string" },
          "type": { "type": "string" },
          "enabled": { "type": "boolean" },
          "configuration": { "type": "object" }
        },
        "required": ["toolId", "name", "type"]
      }
      """;

  private static final String HITL_HUMAN_TASK_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "assignTo": { "type": "string" },
          "assigneeType": { "type": "string" },
          "taskType": { "type": "string" },
          "title": { "type": "string" },
          "description": { "type": "string" },
          "priority": { "type": "string" },
          "dueInHours": { "type": "integer", "minimum": 0 },
          "dueInDays": { "type": "integer", "minimum": 0 },
          "escalationConfig": { "type": "object", "additionalProperties": true },
          "notificationConfig": { "type": "object", "additionalProperties": true },
          "formSchema": { "type": "object", "additionalProperties": true },
          "requiredApprovals": { "type": "integer", "minimum": 1 }
        },
        "additionalProperties": true
      }
      """;

  private static final String EIP_GENERIC_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "enabled": { "type": "boolean" },
          "name": { "type": "string" },
          "description": { "type": "string" },
          "metadata": { "type": "object", "additionalProperties": true }
        },
        "additionalProperties": true
      }
      """;

  private static final String EIP_SPLITTER_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "expression": { "type": "string" },
          "strategy": { "type": "string" },
          "parallel": { "type": "boolean" },
          "batchSize": { "type": "integer", "minimum": 1 }
        },
        "required": ["expression"],
        "additionalProperties": true
      }
      """;

  private static final String DEAD_LETTER_CHANNEL_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "channelName": { "type": "string" },
          "retentionDays": { "type": "integer", "minimum": 1 },
          "notifyOnFailure": { "type": "boolean" }
        },
        "required": ["channelName"],
        "additionalProperties": true
      }
      """;

  private static final String EIP_DEBUG_PRINT_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "enabled": { "type": "boolean" },
          "prefix": { "type": "string" },
          "level": { "type": "string", "enum": ["trace", "debug", "info", "warn", "error"] },
          "message": {},
          "payload": {}
        },
        "additionalProperties": true
      }
      """;

  private static final String TRIGGER_BASE_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "triggerType": { "type": "string" },
          "enabled": { "type": "boolean" },
          "metadata": { "type": "object", "additionalProperties": true }
        },
        "additionalProperties": true
      }
      """;

  private static final String TRIGGER_SCHEDULE_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "mode": { "type": "string", "enum": ["interval", "cron"] },
          "intervalSeconds": { "type": "integer", "minimum": 1 },
          "cron": { "type": "string" },
          "timezone": { "type": "string" }
        },
        "additionalProperties": true
      }
      """;

  private static final String TRIGGER_EMAIL_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "imapHost": { "type": "string" },
          "imapPort": { "type": "integer", "minimum": 1 },
          "folder": { "type": "string" },
          "subjectFilter": { "type": "string" }
        },
        "additionalProperties": true
      }
      """;

  private static final String TRIGGER_TELEGRAM_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "botToken": { "type": "string" },
          "chatId": { "type": "string" },
          "allowedUserId": { "type": "string" }
        },
        "additionalProperties": true
      }
      """;

  private static final String TRIGGER_WEBSOCKET_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "path": { "type": "string" },
          "channel": { "type": "string" }
        },
        "additionalProperties": true
      }
      """;

  private static final String TRIGGER_WEBHOOK_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "path": { "type": "string" },
          "method": { "type": "string", "enum": ["POST", "PUT", "PATCH"] },
          "secret": { "type": "string" }
        },
        "additionalProperties": true
      }
      """;

  private static final String TRIGGER_EVENT_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "eventName": { "type": "string" },
          "eventSource": { "type": "string" }
        },
        "additionalProperties": true
      }
      """;

  private static final String TRIGGER_KAFKA_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "brokers": { "type": "string" },
          "topic": { "type": "string" },
          "groupId": { "type": "string" }
        },
        "additionalProperties": true
      }
      """;

  private static final String TRIGGER_FILE_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "path": { "type": "string" },
          "pattern": { "type": "string" },
          "pollSeconds": { "type": "integer", "minimum": 1 }
        },
        "additionalProperties": true
      }
      """;

  private static final String SUB_WORKFLOW_SCHEMA = """
      {
        "type": "object",
        "properties": {
          "projectId": { "type": "string" },
          "definitionId": { "type": "string" },
          "invokeMode": { "type": "string", "enum": ["sync", "async"] },
          "maxDepth": { "type": "integer", "minimum": 1, "maximum": 5 },
          "waitForCompletion": { "type": "boolean" },
          "inputs": { "type": "object", "additionalProperties": true },
          "parameters": { "type": "object", "additionalProperties": true },
          "inputBindings": { "type": "object", "additionalProperties": true },
          "exposedOutput": {
            "type": "object",
            "properties": {
              "type": { "type": "string" },
              "properties": { "type": "object", "additionalProperties": true },
              "required": { "type": "array", "items": { "type": "string" } }
            },
            "additionalProperties": true
          },
          "wayangSpec": { "$ref": "/v1/schema/catalog/wayang-spec" },
          "workflowSpec": { "$ref": "/v1/schema/catalog/workflow-spec" },
          "metadata": { "type": "object", "additionalProperties": true }
        },
        "additionalProperties": true
      }
      """;

  private static final Map<String, String> SCHEMAS = new LinkedHashMap<>();

  static {
    // Core schemas
    // Keep backward compatibility: "workflow" is an alias of "workflow-spec".
    String workflowSpecSchema = SchemaGeneratorUtils.generateSchema(WorkflowSpec.class);
    SCHEMAS.put(WORKFLOW, workflowSpecSchema);
    SCHEMAS.put(WORKFLOW_SPEC, workflowSpecSchema);
    SCHEMAS.put(PLUGIN_CONFIG, PLUGIN_CONFIG_SCHEMA);
    SCHEMAS.put(WAYANG_SPEC, SchemaGeneratorUtils.generateSchema(WayangSpec.class));
    SCHEMAS.put(VECTOR_SEARCH, SchemaGeneratorUtils.generateSchema(VectorSearchConfig.class));
    SCHEMAS.put(VECTOR_UPSERT, SchemaGeneratorUtils.generateSchema(VectorUpsertConfig.class));
    SCHEMAS.put(AGENT_CONFIG, AGENT_BASE_SCHEMA);
    SCHEMAS.put(AGENT_ORCHESTRATOR, AGENT_BASE_SCHEMA);
    SCHEMAS.put(AGENT_ANALYTIC, AGENT_BASE_SCHEMA);
    SCHEMAS.put(AGENT_CODER, AGENT_BASE_SCHEMA);
    SCHEMAS.put(AGENT_EVALUATOR, AGENT_BASE_SCHEMA);
    SCHEMAS.put(AGENT_PLANNER, AGENT_BASE_SCHEMA);
    SCHEMAS.put(AGENT_BASIC, AGENT_BASE_SCHEMA);
    SCHEMAS.put(HITL_HUMAN_TASK, HITL_HUMAN_TASK_SCHEMA);
    SCHEMAS.put(EIP_ENDPOINT, EIP_GENERIC_SCHEMA);
    SCHEMAS.put(EIP_ROUTER, EIP_GENERIC_SCHEMA);
    SCHEMAS.put(EIP_FILTER, EIP_GENERIC_SCHEMA);
    SCHEMAS.put(EIP_AGGREGATOR, EIP_GENERIC_SCHEMA);
    SCHEMAS.put(EIP_SPLITTER, EIP_SPLITTER_SCHEMA);
    SCHEMAS.put(EIP_TRANSFORMER, EIP_GENERIC_SCHEMA);
    SCHEMAS.put(EIP_ENRICHER, EIP_GENERIC_SCHEMA);
    SCHEMAS.put(EIP_RETRY, EIP_GENERIC_SCHEMA);
    SCHEMAS.put(EIP_THROTTLER, EIP_GENERIC_SCHEMA);
    SCHEMAS.put(EIP_DEBUG_PRINT, EIP_DEBUG_PRINT_SCHEMA);
    SCHEMAS.put(DEAD_LETTER_CHANNEL, DEAD_LETTER_CHANNEL_SCHEMA);
    SCHEMAS.put(TRIGGER_START, TRIGGER_BASE_SCHEMA);
    SCHEMAS.put(TRIGGER_MANUAL, TRIGGER_BASE_SCHEMA);
    SCHEMAS.put(TRIGGER_SCHEDULE, TRIGGER_SCHEDULE_SCHEMA);
    SCHEMAS.put(TRIGGER_EMAIL, TRIGGER_EMAIL_SCHEMA);
    SCHEMAS.put(TRIGGER_TELEGRAM, TRIGGER_TELEGRAM_SCHEMA);
    SCHEMAS.put(TRIGGER_WEBSOCKET, TRIGGER_WEBSOCKET_SCHEMA);
    SCHEMAS.put(TRIGGER_WEBHOOK, TRIGGER_WEBHOOK_SCHEMA);
    SCHEMAS.put(TRIGGER_EVENT, TRIGGER_EVENT_SCHEMA);
    SCHEMAS.put(TRIGGER_KAFKA, TRIGGER_KAFKA_SCHEMA);
    SCHEMAS.put(TRIGGER_FILE, TRIGGER_FILE_SCHEMA);
    SCHEMAS.put(SUB_WORKFLOW_NODE, SUB_WORKFLOW_SCHEMA);
    SCHEMAS.put(CUSTOM_AGENT_NODE, SUB_WORKFLOW_SCHEMA);
    SCHEMAS.put("agent-tool", BUILTIN_TOOL_SCHEMA);

    // Discover node-provided schemas via NodeProvider SPI
    for (NodeProvider provider : ServiceLoader.load(NodeProvider.class)) {
      for (NodeDefinition node : provider.nodes()) {
        if (node.configSchema() != null && !node.configSchema().isBlank()) {
          SCHEMAS.put(node.type(), node.configSchema());
        }
      }
    }

    // Discover standalone schemas via SchemaProvider SPI (backward-compat)
    for (SchemaProvider provider : ServiceLoader.load(SchemaProvider.class)) {
      SCHEMAS.putAll(provider.schemas());
    }
  }

  private BuiltinSchemaCatalog() {
  }

  public static Set<String> ids() {
    return SCHEMAS.keySet();
  }

  public static String get(String schemaId) {
    return SCHEMAS.get(schemaId);
  }

  /**
   * Dynamically registers schemas from a NodeProvider at runtime.
   * Useful for external plugins loaded after static initialization.
   */
  public static void register(NodeProvider provider) {
    if (provider == null)
      return;
    for (NodeDefinition node : provider.nodes()) {
      if (node.configSchema() != null && !node.configSchema().isBlank()) {
        SCHEMAS.put(node.type(), node.configSchema());
      }
    }
  }
}
