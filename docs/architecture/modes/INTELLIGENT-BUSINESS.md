# Intelligent Business Automation Mode Architecture

> This mode composes Agent, Integration, BPMN, DMN, data and human capabilities. It is a Studio composition experience and cross-domain contract layer, not a separate workflow engine.

## 1. Purpose and ownership

Use this mode for end-to-end business solutions where deterministic process governance, integration and AI reasoning cooperate.

- BPMN owns the governed process lifecycle when a BPMN process is used.
- DMN owns deterministic business decision evaluation.
- Andalus owns agent execution.
- Integration engine/connectors own endpoint and EIP semantics.
- Gamelan owns durable orchestration when a workflow is explicitly delegated to it.
- Wayang owns cross-domain authoring, typed bindings, dependency resolution, deployment packaging and cross-engine observability context.

## 2. Reference architecture

```mermaid
flowchart TB
  subgraph Experience["Intelligent Business Workspace"]
    Canvas[Cross-domain composition]
    Contracts[Typed input/output contracts]
    Mapping[Data mapping and transformation]
    Governance[Policy, approvals and audit design]
  end
  subgraph Domain["Domain-owned definitions"]
    Process[BPMN process]
    Decision[DMN decisions]
    Agent[Agent definition]
    Route[EIP integration routes]
    Data[Storage / data resources]
  end
  subgraph Engines["Independent execution owners"]
    BpmnEngine[BPMN engine]
    DmnEngine[DMN evaluator]
    Andalus[Andalus agent runtime]
    EipEngine[Integration engine]
  end
  Canvas --> Contracts
  Mapping --> Contracts
  Governance --> Contracts
  Contracts --> Process
  Contracts --> Decision
  Contracts --> Agent
  Contracts --> Route
  Contracts --> Data
  Process --> BpmnEngine
  Decision --> DmnEngine
  Agent --> Andalus
  Route --> EipEngine
  BpmnEngine <-->|typed service / event contract| Andalus
  BpmnEngine -->|decision invocation| DmnEngine
  BpmnEngine <-->|message / callback| EipEngine
```

## 3. Procurement approval sequence

```mermaid
sequenceDiagram
  autonumber
  participant ERP as ERP / Integration Endpoint
  participant EIP as Integration Runtime
  participant BPMN as BPMN Process Engine
  participant Agent as Andalus Agent Runtime
  participant DMN as DMN Decision Service
  participant Human as Human Task Service
  participant ERPOut as ERP Update Route
  ERP->>EIP: Purchase request event
  EIP->>EIP: Validate, normalize, deduplicate
  EIP->>BPMN: Start process with correlation/business key
  BPMN->>BPMN: Persist process instance and version references
  BPMN->>Agent: Request contextual risk/recommendation
  Agent-->>BPMN: Typed recommendation + evidence references
  BPMN->>DMN: Evaluate deterministic approval policy
  DMN-->>BPMN: APPROVE / REJECT / NEEDS_HUMAN
  alt Human approval required
    BPMN->>Human: Create approval task with deadline
    Human-->>BPMN: Approved / rejected + actor/audit
  else Automated outcome
    BPMN->>BPMN: Continue with DMN outcome
  end
  BPMN->>ERPOut: Request ERP status update (idempotency key)
  ERPOut->>ERP: Update purchase request
  ERP-->>ERPOut: Acknowledgement
  ERPOut-->>BPMN: Typed integration outcome
  BPMN-->>EIP: Process completion event
  EIP-->>ERP: Optional callback / notification
```

The agent provides contextual recommendation, not an ungoverned authorization decision. Deterministic policy and human approval remain explicit process steps.

## 4. Cross-engine invocation contract

Every boundary must carry:
- contract name and version, typed input/output schema;
- tenant/principal and delegated authorization context;
- correlation, causation, business and idempotency identifiers;
- deadline, cancellation token and execution budget;
- trace context and artifact/process/agent version references;
- typed error categories and retryability;
- data classification, residency and retention obligations.

Never pass engine-internal object references or rely on shared JVM memory across runtime boundaries. Prefer schema-versioned messages or service contracts.

## 5. Failure, retry and compensation

```mermaid
stateDiagram-v2
  [*] --> Accepted
  Accepted --> ProcessStarted
  ProcessStarted --> AgentPending
  AgentPending --> AgentCompleted
  AgentPending --> AgentFailed
  AgentFailed --> AgentRetry: retry budget and policy allow
  AgentRetry --> AgentPending
  AgentFailed --> Incident: exhausted / permanent
  AgentCompleted --> DecisionPending
  DecisionPending --> HumanPending: requires human
  DecisionPending --> Approved: auto approved
  DecisionPending --> Rejected: rejected
  HumanPending --> Approved
  HumanPending --> Rejected
  Approved --> IntegrationPending
  IntegrationPending --> Completed
  IntegrationPending --> IntegrationFailed
  IntegrationFailed --> Reconcile: uncertain external outcome
  Reconcile --> Completed: confirmed applied
  Reconcile --> Compensation: confirmed not applied / business rollback
  Rejected --> Compensation
  Compensation --> Incident
  Completed --> [*]
  Incident --> [*]
```

Compensation is a business action, not a database rollback. Each side effect needs a defined compensating action or reconciliation path. If a timeout occurs after an external system may have committed, query/reconcile before retrying.

## 6. Deployment topology

A mixed-mode solution can be deployed as one generated runtime when the required engines can safely coexist, or as multiple services when isolation, independent scaling or engine requirements demand it. The artifact manifest declares the topology and cross-service contracts. Do not assume one runtime per mode or one runtime per project.

## 7. Governance, security and observability

- Apply policy at entry and at each engine boundary; propagate trusted tenant/principal context.
- Treat agent output as untrusted input to BPMN/DMN and integration steps; validate against schemas and policy.
- Keep human decisions attributable and auditable.
- Use outbox/inbox and idempotency for cross-engine event delivery.
- Propagate trace/correlation/business keys through every engine.
- Record model/agent version, decision version, process version, route version, artifact digest and human actor.
- Redact secrets and sensitive business payloads from traces.
- Define data retention and regional placement across all participating engines.

## 8. Scaling

Scale each engine according to its own workload profile: integration consumers by partition/endpoint throughput, BPMN jobs by ready-job backlog, agent workers by inference concurrency and provider quotas, DMN evaluators by request rate (often stateless). Avoid scaling the whole solution as one unit when bottlenecks differ. Enforce tenant quotas and end-to-end deadlines. Use durable event contracts for cross-service boundaries and preserve ordering only where business semantics require it.
