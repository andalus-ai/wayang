# Business Automation Mode Architecture

> Target architecture for BPMN and DMN authoring and execution. Current supported subsets and limitations must be taken from implementation documentation and tests; this document does not assert full BPMN 2.0 or DMN conformance.

## 1. Goals and domain boundaries

Business Automation provides native BPMN process modeling, DMN decision modeling, human tasks, timers, escalation, process monitoring and governed lifecycle.

- BPMN owns process control flow, tokens, events, timers, subprocesses and process state.
- DMN owns deterministic decision model evaluation and hit-policy semantics within the supported conformance profile.
- Wayang owns Studio authoring, project references, validation boundaries, capability packs, release compilation and integration with identity/operations.
- A BPMN-compatible engine owns process execution and recovery. A DMN evaluator owns decision evaluation. Gamelan may orchestrate broader workflows but must not silently replace BPMN semantics.

## 2. Logical architecture

```mermaid
flowchart TB
  subgraph Studio["Business Automation Studio"]
    BpmnDesigner[BPMN process modeler]
    DmnDesigner[DMN decision editor]
    FormDesigner[User task form designer]
    Monitor[Process instance monitor]
  end
  subgraph Definition["Versioned process assets"]
    BpmnDoc[(BPMN document)]
    DmnDoc[(DMN models)]
    Forms[(Forms and task schemas)]
    Bindings[Input/output and decision bindings]
  end
  subgraph Runtime["Business process runtime"]
    API[Process API]
    Engine[BPMN engine adapter]
    Evaluator[DMN evaluator adapter]
    Task[Human task service]
    Timer[Timer / job service]
    Audit[Audit and history]
  end
  BpmnDesigner --> BpmnDoc
  DmnDesigner --> DmnDoc
  FormDesigner --> Forms
  BpmnDoc --> API
  DmnDoc --> Evaluator
  Forms --> Task
  Bindings --> Engine
  API --> Engine
  Engine --> Evaluator
  Engine --> Task
  Engine --> Timer
  Engine --> Audit
  Monitor -.-> API
```

## 3. Process start and decision sequence

```mermaid
sequenceDiagram
  autonumber
  actor Caller
  participant API as Process API
  participant Auth as Identity / Tenant Policy
  participant Engine as BPMN Engine
  participant DMN as DMN Evaluator
  participant Store as Durable Process Store
  Caller->>API: Start process(key, businessKey, input)
  API->>Auth: Authenticate, authorize and validate tenant
  Auth-->>API: Authorized context
  API->>Engine: Start version-pinned process definition
  Engine->>Store: Persist instance and initial token
  Engine->>DMN: Evaluate referenced decision with typed inputs
  DMN-->>Engine: Decision result + evaluation metadata
  Engine->>Store: Persist decision and process transition
  Engine-->>API: Instance ID and current state
  API-->>Caller: Accepted + process reference
```

The process must pin or explicitly resolve process and decision versions at start. Do not let an in-flight instance unexpectedly change semantics because a newer model was published.

## 4. Human task lifecycle

```mermaid
sequenceDiagram
  autonumber
  participant Engine as BPMN Engine
  participant Tasks as Task Service
  participant Inbox as User Task Inbox
  actor User
  participant Policy as Authorization
  participant Store as Process Store
  Engine->>Tasks: Create task with assignment rules and deadline
  Tasks->>Store: Persist task and process wait state
  Tasks-->>Inbox: Publish task available event
  User->>Inbox: Open task
  Inbox->>Policy: Check tenant, candidate and task permissions
  Policy-->>Inbox: Allow
  User->>Inbox: Submit form and completion command
  Inbox->>Tasks: Complete task with idempotency token
  Tasks->>Store: Validate state/version and persist completion
  Tasks->>Engine: Signal process continuation
  Engine->>Store: Advance process token
  Engine-->>Tasks: Process resumed
```

Human tasks need assignment/candidate rules, tenant isolation, optimistic concurrency, due dates, escalation, delegation, audit history and idempotent completion. UI availability is not authorization.

## 5. Timers, escalation and recovery

```mermaid
flowchart TD
  Engine[BPMN Engine persists timer] --> Scheduler[Durable timer scheduler]
  Scheduler --> Due{Timer due?}
  Due -->|No| Wait[Wait until due]
  Wait --> Scheduler
  Due -->|Yes| Claim[Claim timer with lease/fencing]
  Claim --> Signal[Deliver timer event to engine]
  Signal --> Engine
  Engine --> Path{Process outcome}
  Path -->|normal| Continue[Continue process]
  Path -->|deadline missed| Escalate[Escalation / notification]
  Path -->|failure| Incident[Incident / retry policy]
```

Timers must survive process and worker restarts. Scheduler delivery may be at-least-once; engine-side timer identity and idempotency prevent duplicate transitions. Define timezone, daylight-saving and calendar semantics explicitly.

## 6. BPMN / DMN document lifecycle

```mermaid
sequenceDiagram
  autonumber
  actor Analyst
  participant Studio as BPMN / DMN Studio
  participant Validator as Model Validator
  participant Repo as Versioned Asset Store
  participant Compiler as Release Compiler
  Analyst->>Studio: Edit process and decision models
  Studio->>Validator: Validate syntax, references and supported conformance
  Validator-->>Studio: Errors, warnings and unsupported constructs
  Analyst->>Studio: Publish selected versions
  Studio->>Repo: Store immutable process/decision versions
  Repo->>Compiler: Resolve process-to-decision references
  Compiler->>Compiler: Pin versions and engine compatibility
  Compiler-->>Studio: Release artifact and dependency manifest
```

Never silently drop unsupported BPMN/DMN constructs during import/export. Show explicit diagnostics and preserve source documents where round-trip fidelity is not supported.

## 7. Scaling and HA

Scale stateless process APIs and task inbox services horizontally. Scale job executors according to ready-job backlog and process-engine concurrency. Stateful process execution requires a durable engine store and safe job acquisition/locking; use the selected engine's supported clustering model rather than inventing a competing token scheduler.

Ensure single ownership of timers/jobs through engine-native locks or fencing. Human-task stores and process state must share reliable transaction/outbox boundaries or recoverable reconciliation. Test worker loss during job execution, timer firing, task completion and process migration. Define process version migration separately; do not mutate running instances implicitly.

## 8. Conformance and engine selection gates

Before selecting or shipping a BPMN/DMN engine, record:
- supported BPMN/DMN specification versions and profiles;
- supported events, gateways, tasks, subprocesses, compensation and transaction semantics;
- DMN hit policies, FEEL support and type behavior;
- import/export and diagram interchange fidelity;
- persistence, clustering, job acquisition and recovery model;
- licensing, extension model, Java/Quarkus compatibility and operational footprint;
- test suite and interoperability evidence.

Expose conformance limitations in Studio and docs. Do not market a subset as full standard compliance.
