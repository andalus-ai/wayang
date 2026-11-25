# Agent Builder Mode Architecture

> Target design for the Agent Builder mode. Current agent-related implementation and verification are documented in [RAG](../../RAG.md), [triggers](../../TRIGGERS.md), [release](../../RELEASE.md) and [architecture](../../ARCHITECTURE.md).

## 1. Goals and boundaries

Agent Builder authors agent definitions, tools, model/provider choices, memory, knowledge/RAG, triggers, guardrails and multi-agent composition. It is an authoring mode and capability family, not a replacement for the neutral Wayang core or the durable workflow engine.

- Andalus owns agent execution semantics and agent lifecycle when used through its adapter.
- Gamelan owns durable workflow orchestration for workflows delegated to it.
- Wayang owns project composition, schemas, validation, release compilation and deployment integration.
- Storage, retrieval and model inference are explicit capabilities with typed contracts; they are not hidden implicit node side effects.

## 2. Logical architecture

```mermaid
flowchart TB
  subgraph Studio["Agent Builder Studio"]
    Canvas[Agent / tool graph designer]
    Inspector[Persona, guardrails and model inspector]
    Test[Prompt / tool test bench]
    Trace[Execution trace viewer]
  end
  subgraph Definition["Agent project and capability packs"]
    AgentSpec[Agent definition]
    ToolSpec[Tool contracts]
    KnowledgeSpec[Knowledge / RAG definition]
    TriggerSpec[Trigger and schedule definitions]
    Policy[Guardrail and policy references]
  end
  subgraph Runtime["Generated agent runtime"]
    API[Agent invocation API]
    Adapter[Andalus adapter]
    Guard[Policy / guardrail enforcement]
    ToolBroker[Tool broker]
    Context[Context and memory adapter]
  end
  subgraph Services["External capabilities"]
    Model[Model / inference provider]
    Vector[(Vector index)]
    Object[(Object / document storage)]
    Search[Retrieval / reranker]
    Systems[External tools and APIs]
  end
  Canvas --> AgentSpec
  Inspector --> AgentSpec
  Test --> AgentSpec
  Trace -.-> API
  AgentSpec --> API
  ToolSpec --> ToolBroker
  KnowledgeSpec --> Context
  Policy --> Guard
  API --> Guard --> Adapter
  Adapter --> Model
  Adapter --> ToolBroker
  ToolBroker --> Systems
  Context --> Object
  Context --> Search
  Search --> Vector
```

## 3. Agent invocation sequence

```mermaid
sequenceDiagram
  autonumber
  actor Client
  participant API as Agent Runtime API
  participant Auth as Identity / Tenant Guard
  participant Policy as Persona / Guardrails
  participant Agent as Andalus Agent Adapter
  participant Model as Model Router / Provider
  participant Tools as Tool Broker
  participant Tool as External Tool
  Client->>API: Invoke agent(input, conversation, idempotency key)
  API->>Auth: Authenticate and establish tenant context
  Auth-->>API: Principal + tenant context
  API->>Policy: Evaluate input and execution constraints
  Policy-->>API: Allow / deny / obligations
  API->>Agent: Start/resume agent execution
  Agent->>Model: Inference request with bounded context
  Model-->>Agent: Response / tool call
  opt Tool call requested
    Agent->>Tools: Invoke typed tool with scoped credential reference
    Tools->>Tool: Authorized external operation
    Tool-->>Tools: Typed result
    Tools-->>Agent: Tool result
    Agent->>Model: Continue reasoning with bounded result
    Model-->>Agent: Final response / next action
  end
  Agent-->>API: Result, usage and execution metadata
  API-->>Client: Response + trace/correlation IDs
```

The adapter must define streaming, cancellation, deadline, maximum tool calls/iterations, token/context budgets, idempotency and error mapping. A model timeout is not automatically safe to retry if a tool side effect may already have occurred.

## 4. RAG ingestion and query paths

```mermaid
flowchart LR
  Source[Files, URLs, repositories, events] --> Ingest[Ingestion trigger]
  Ingest --> Extract[Extract text / metadata / media]
  Extract --> Chunk[Chunk and normalize]
  Chunk --> Embed[Embedding capability]
  Embed --> Index[(Vector / lexical index)]
  Extract --> Store[(Object storage)]
  Query[Agent query] --> Auth[Tenant and ACL filter]
  Auth --> Retrieve[Hybrid retrieval]
  Index --> Retrieve
  Retrieve --> Rerank[Optional reranker]
  Rerank --> Context[Context assembler]
  Store --> Context
  Context --> Agent[Agent execution]
```

Storage is an explicit reusable resource. RAG nodes declare required inputs and storage/index dependencies. Missing mandatory data inputs produce visible diagnostics; by default they are advisory and do not block saving, testing, publishing or workflow execution. A connector that cannot proceed returns a typed empty/no-op outcome and a warning rather than fabricating data. Only an explicitly declared hard precondition may block execution. Separate source object storage, extracted document metadata and vector/lexical indexes. Enforce tenant and source ACL filters before returning chunks.

### Ingestion sequence

```mermaid
sequenceDiagram
  autonumber
  participant Source as Source Connector
  participant Ingest as Ingestion Service
  participant Extract as Extractor / Parser
  participant Store as Object Storage
  participant Embed as Embedding Provider
  participant Index as Vector / Search Index
  Source->>Ingest: New or changed document
  Ingest->>Ingest: Deduplicate by source/version/content digest
  Ingest->>Extract: Extract text, metadata and media references
  Extract->>Store: Persist original/normalized asset
  Extract->>Embed: Embed normalized chunks
  Embed-->>Index: Chunk vectors + metadata + ACL
  Index-->>Ingest: Index acknowledgement
  Ingest-->>Source: Ingestion outcome / checkpoint
```

## 5. Triggers, schedules and durable execution

```mermaid
flowchart TB
  Webhook[Webhook / API] --> Trigger[Trigger adapter]
  Chat[Chat channel] --> Trigger
  Schedule[Schedule / timer] --> Trigger
  Event[Message / event] --> Trigger
  Trigger --> Auth[Authenticate, tenant, deduplicate]
  Auth --> Start{Execution type}
  Start -->|Short agent request| Agent[Andalus agent execution]
  Start -->|Long-running orchestration| Workflow[Gamelan durable workflow]
  Workflow --> Agent
  Agent --> Result[Outcome / event]
  Result --> Sink[Response / callback / message]
```

Use the durable workflow engine when the use case needs persisted waits, timers, retries, human approval or multi-step recovery. Do not use an in-memory agent request loop as a substitute for durable process state.

## 6. Security and operations

- Persona is descriptive behavior; guardrails and policy enforcement are executable controls. Never treat prompt text as the security boundary.
- Tool permissions are least-privilege and tenant-scoped. Resolve secret references at invocation time; never package secret values.
- Bound model calls, tool calls, output size, context size, spend and execution time.
- Apply retrieval ACLs and tenant filters before context reaches the model.
- Record model/provider, model version, prompt/template version, tool calls, policy decisions, token/cost metrics and trace IDs with sensitive payload redaction.
- Support cancellation and distinguish cancelled, timed out, denied, failed and completed outcomes.
- Define retention and deletion for conversation state, memory and indexed content.

## 7. Scaling

Scale stateless invocation/API and tool workers horizontally. Scale ingestion, embedding and retrieval separately because their CPU, memory, I/O and GPU profiles differ. Use bounded queues and tenant quotas. Keep agent conversation/session state in an explicit persistence adapter if it must survive instance replacement. Use sticky routing only as an optimization, never as the only durability mechanism.

For Enterprise, capability discovery can advertise agent execution, model/provider access and resource classes. Placement must honor tenant, region, data residency, model availability and GPU constraints. Andalus remains owner of agent execution state; Gamelan remains owner of durable workflow lifecycle.
