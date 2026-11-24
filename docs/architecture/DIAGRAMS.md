# Wayang Platform Architecture Diagrams

> These diagrams describe the target architecture. A diagram is not evidence that every depicted component is implemented. Consult [current implementation architecture](../ARCHITECTURE.md), [editions and deployment status](../EDITIONS.md), and the mode-specific documents in this directory.

## 1. System context

```mermaid
flowchart TB
  User[Builder / Analyst / Integrator]
  Operator[Platform Operator]
  External[Enterprise Systems, APIs, Events, Data]
  Studio[Wayang Studio]
  Console[Wayang Console - separate product]
  Runtime[Wayang Runtime Fleet]
  User --> Studio
  Operator --> Console
  Studio -->|author, validate, publish| Artifacts[(Artifact Registry)]
  Artifacts -->|deployable immutable artifacts| Runtime
  Runtime <-->|business traffic| External
  Console -.->|manage, observe, govern| Runtime
```

The Console is a separate product/repository. Runtime business execution must not synchronously depend on Studio, Console, or the artifact registry after deployment.

## 2. Platform logical architecture

```mermaid
flowchart TB
  subgraph Studio["Design plane - Wayang Studio"]
    Mode[Mode Manager]
    AgentUI[Agent Designer]
    EipUI[Integration Designer]
    BpmnUI[BPMN / DMN Designers]
    ComposeUI[Intelligent Business Workspace]
    CreativeUI[Future Creative Designer]
    Mode --> AgentUI
    Mode --> EipUI
    Mode --> BpmnUI
    Mode --> ComposeUI
    Mode --> CreativeUI
  end
  subgraph Core["Neutral Wayang platform core"]
    Project[Project / Document Model]
    Registry[Capability Pack Registry]
    Schema[Schema, Type and Validation]
    Compiler[Artifact Compiler]
    Contract[Versioned Execution / Adapter Contracts]
    Project --> Schema
    Registry --> Schema
    Schema --> Compiler
    Compiler --> Contract
  end
  subgraph Packs["Domain capability packs"]
    AgentPack[Agent Pack]
    EipPack[EIP / Connector Packs]
    BpmnPack[BPMN Pack]
    DmnPack[DMN Pack]
    MediaPack[Future Media Pack]
  end
  subgraph Engines["Execution engines - adapter boundary"]
    Andalus[Andalus Agent Runtime]
    Gamelan[Gamelan Durable Workflow Engine]
    Integration[EIP Engine / Components]
    Bpmn[BPMN Engine]
    Dmn[DMN Evaluator]
    Media[Future Media Workers]
  end
  AgentUI --> Project
  EipUI --> Project
  BpmnUI --> Project
  ComposeUI --> Project
  CreativeUI --> Project
  AgentPack --> Registry
  EipPack --> Registry
  BpmnPack --> Registry
  DmnPack --> Registry
  MediaPack --> Registry
  Contract --> Andalus
  Contract --> Gamelan
  Contract --> Integration
  Contract --> Bpmn
  Contract --> Dmn
  Contract -.-> Media
```

## 3. Authoring-to-artifact sequence

```mermaid
sequenceDiagram
  autonumber
  actor Builder
  participant Studio as Wayang Studio
  participant API as Design API
  participant Validator as Domain Validators
  participant Compiler as Artifact Compiler
  participant Registry as Artifact Registry
  Builder->>Studio: Edit project / domain document
  Studio->>API: Save versioned project
  API->>Validator: Validate schemas, references, policies
  Validator-->>API: Diagnostics
  API-->>Studio: Validation result
  Builder->>Studio: Publish selected version
  Studio->>API: Publish request
  API->>Compiler: Compile immutable release
  Compiler->>Compiler: Resolve packs, engines and dependency lock
  Compiler->>Compiler: Emit manifest, checksums and provenance
  Compiler->>Registry: Store artifact
  Registry-->>Studio: Artifact ID and digest
  Studio-->>Builder: Published release
```

Compiler requirements: deterministic resolution; pinned versions; compatibility checks; software bill of materials where applicable; signed provenance where enabled; no embedded secret values; configuration and secret references only.

## 4. Runtime deployment sequence

```mermaid
sequenceDiagram
  autonumber
  actor Operator
  participant Console as Wayang Console (optional)
  participant Registry as Artifact Registry
  participant Runtime as Runtime Provisioner
  participant Node as Runtime Instance
  participant Discovery as Service Discovery
  Operator->>Console: Request deployment
  Console->>Registry: Resolve artifact by immutable digest
  Registry-->>Console: Artifact + manifest
  Console->>Runtime: Desired deployment specification
  Runtime->>Node: Provision minimal runtime
  Node->>Node: Verify signature/digest and compatibility
  Node->>Node: Load only required packs and adapters
  Node->>Discovery: Register identity, endpoint and capabilities
  Discovery-->>Node: Registration lease / acknowledgement
  Node-->>Console: Ready status and health
  Console-->>Operator: Deployment status
```

For Community, the integrated runtime starts locally without Console or service discovery. For Pro, a generated runtime may be started independently. Enterprise adds fleet management and distributed placement. The exact automation of these steps is implementation-dependent.

## 5. Runtime registration and capability discovery

```mermaid
sequenceDiagram
  autonumber
  participant Runtime as Runtime Instance
  participant Agent as Local Management Agent
  participant Registry as Discovery Registry
  participant Health as Health / Readiness
  Runtime->>Health: Startup checks
  Health-->>Runtime: Ready
  Runtime->>Agent: Publish runtime identity and capability manifest
  Agent->>Registry: Register(instance, version, capabilities, endpoint, lease)
  Registry-->>Agent: Registration accepted
  loop Heartbeat interval
    Agent->>Registry: Renew lease + health metadata
    Registry-->>Agent: Lease renewed
  end
  Note over Registry: Expired leases are removed from eligible endpoints
```

Discovery records should include runtime ID, tenant/security scope, deployment and artifact version, supported adapter/component capabilities, protocol/API version, endpoint, region/zone, capacity/load, readiness, lease expiry and drain state. Do not expose secrets in discovery metadata.

## 6. Capability-aware dispatch sequence

```mermaid
sequenceDiagram
  autonumber
  participant Caller as Runtime / Event Gateway
  participant Dispatcher as Task Dispatcher
  participant Policy as Policy / Tenant Guard
  participant Discovery as Discovery Registry
  participant Worker as Selected Runtime
  Caller->>Dispatcher: Invoke capability + tenant + correlation ID
  Dispatcher->>Policy: Authorize caller, tenant and requested capability
  Policy-->>Dispatcher: Allow / deny + constraints
  Dispatcher->>Discovery: Find ready compatible workers
  Discovery-->>Dispatcher: Candidate endpoints and capacity
  Dispatcher->>Dispatcher: Filter protocol/version, locality and limits
  Dispatcher->>Worker: Invoke with deadline and idempotency key
  Worker-->>Dispatcher: Result / typed error
  Dispatcher-->>Caller: Result + correlation metadata
```

Routing policy can consider capability match, tenant placement, region/data residency, priority, concurrency limits and observed load. Do not route based on load before enforcing authorization and compatibility.

## 7. Scale-out and scale-in

```mermaid
flowchart TD
  Demand[Queue depth / latency / CPU / concurrency] --> Metrics[Metrics and SLO evaluator]
  Metrics --> Decision{Capacity policy}
  Decision -->|Scale out| Provision[Provision compatible runtime]
  Provision --> Ready[Startup and readiness checks]
  Ready --> Register[Register endpoint and capabilities]
  Register --> Dispatch[Dispatcher can select new instance]
  Decision -->|Scale in| Drain[Mark instance draining]
  Drain --> StopNew[Stop accepting new work]
  StopNew --> Finish[Finish or safely checkpoint in-flight work]
  Finish --> Deregister[Deregister / expire lease]
  Deregister --> Terminate[Terminate instance]
  Decision -->|No action| Stable[Maintain current capacity]
```

Scale-in must not kill active work. Long-running executions need engine-owned checkpoint/resume or a safe drain timeout and recovery policy. Queue depth and latency are often more useful than CPU alone for worker scaling.

## 8. Failure detection and recovery

```mermaid
sequenceDiagram
  autonumber
  participant Worker as Runtime Worker
  participant Discovery as Discovery Registry
  participant Dispatcher as Dispatcher
  participant State as Durable State / Engine
  participant Replacement as Replacement Worker
  Worker--xDiscovery: Heartbeats stop
  Discovery->>Discovery: Lease expires; mark unavailable
  Dispatcher->>Discovery: Resolve eligible worker
  Discovery-->>Dispatcher: Exclude expired instance
  Dispatcher->>State: Inspect execution ownership / lease
  State-->>Dispatcher: Recoverable work and fencing epoch
  Dispatcher->>Replacement: Reassign only if engine policy permits
  Replacement->>State: Claim with new fencing token
  Replacement->>Replacement: Resume/checkpoint or retry idempotently
  Note over State,Replacement: Engine owns durable state and retry semantics
```

A heartbeat timeout is not proof that an execution stopped. Use leases/fencing or equivalent ownership to prevent two workers from committing the same work. The engine that owns durable execution owns retry and recovery semantics; avoid duplicate retry loops in adapters and dispatchers.

## 9. High availability reference topology

```mermaid
flowchart TB
  LB[Ingress / Load Balancer]
  subgraph AZ1["Failure domain / zone A"]
    CP1[Control Plane replica]
    W1[Runtime workers]
    D1[Local agent / health]
  end
  subgraph AZ2["Failure domain / zone B"]
    CP2[Control Plane replica]
    W2[Runtime workers]
    D2[Local agent / health]
  end
  subgraph Shared["Replicated / durable services"]
    DB[(Durable metadata database)]
    Queue[(Message broker / queue)]
    Registry[(Discovery / lease store)]
    Artifact[(Artifact registry)]
    Telemetry[(Metrics, logs, traces)]
  end
  LB --> CP1
  LB --> CP2
  CP1 --> DB
  CP2 --> DB
  W1 --> Queue
  W2 --> Queue
  W1 --> Registry
  W2 --> Registry
  CP1 --> Artifact
  CP2 --> Artifact
  W1 --> Telemetry
  W2 --> Telemetry
```

This is a reference topology, not a claim that Wayang currently ships these clustered services. Production HA requires explicit RPO/RTO, quorum and backup strategy, database/broker replication, zone-aware placement, tested restore, fencing, secret management and failure-injection tests. A load balancer and service discovery alone do not provide HA.

## 10. Security and tenancy flow

```mermaid
sequenceDiagram
  autonumber
  participant Client
  participant Edge as Gateway / Identity Provider
  participant Runtime
  participant Policy as Policy Enforcement
  participant Secret as Secret Provider
  participant Connector as External Connector
  Client->>Edge: Request with identity credential
  Edge->>Edge: Authenticate; derive trusted tenant/principal
  Edge->>Runtime: Forward identity context (signed/trusted)
  Runtime->>Policy: Authorize operation and resource
  Policy-->>Runtime: Decision + obligations
  Runtime->>Secret: Resolve scoped secret reference
  Secret-->>Runtime: Short-lived secret material
  Runtime->>Connector: Call external system
  Connector-->>Runtime: Result (redacted)
  Runtime-->>Client: Response + correlation ID
```

Tenant identity must be derived from trusted authentication context, not accepted as an arbitrary caller-supplied header. Enforce tenant scope in stores, queues, artifacts, logs, discovery, secrets and execution state. Redact credentials and sensitive payloads from telemetry.

## 11. Mode diagram index

- [Agent Builder](modes/AGENT-BUILDER.md): agent graph, RAG/data path, tool execution and human approval.
- [Integration / ESB](modes/INTEGRATION-ESB.md): endpoint-to-route topology, EIP, delivery/retry and dead-letter sequence.
- [Business Automation](modes/BUSINESS-AUTOMATION.md): BPMN/DMN lifecycle, user tasks, timers and process execution.
- [Intelligent Business Automation](modes/INTELLIGENT-BUSINESS.md): cross-engine procurement composition and saga/compensation sequence.
- [Creative Content](modes/CREATIVE-CONTENT.md): future media graph, asset and GPU worker architecture.
- [Distributed Runtime](DISTRIBUTED-RUNTIME.md): discovery, routing, scaling, availability, state and operational controls.
