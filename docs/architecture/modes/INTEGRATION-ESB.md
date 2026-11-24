# Integration / ESB Mode Architecture

> Target architecture for Integration / ESB. The selected integration engine and its exact EIP coverage remain subject to dependency and conformance decisions.

## 1. Goals and boundaries

The mode models enterprise connectivity: endpoints, routes, processors, transformations, routing, messaging, error channels and operational policies. It should feel like an integration product, not an agent canvas with a different palette.

Wayang owns route authoring, schemas, validation, capability packs, packaging and deployment contracts. A mature integration engine/component ecosystem (Apache Camel is a candidate/reference) should own protocol and EIP execution where appropriate. Do not reimplement every transport and EIP primitive in Wayang core.

## 2. Logical architecture

```mermaid
flowchart TB
  subgraph Studio["Integration Designer"]
    Endpoint[Endpoint catalog and config]
    Route[Route / EIP designer]
    Mapping[Schema and transformation editor]
    Error[Error channel and retry policy editor]
  end
  subgraph Project["Integration project"]
    Sources[Sources and consumers]
    Processors[Processors / filters / routers]
    Targets[Destinations / producers]
    Contracts[Payload schemas and contracts]
  end
  subgraph Runtime["Generated integration runtime"]
    Ingress[Ingress adapters]
    Engine[EIP engine adapter]
    Transform[Mapping / transformation]
    Delivery[Delivery and idempotency layer]
    DLQ[Dead-letter handling]
  end
  subgraph Systems["External systems"]
    HTTP[HTTP / REST]
    Kafka[Kafka / messaging]
    DB[Database]
    SaaS[SaaS / ERP / CRM]
    Files[Files / object storage]
  end
  Endpoint --> Sources
  Route --> Processors
  Mapping --> Contracts
  Error --> DLQ
  Sources --> Ingress --> Engine
  Processors --> Engine
  Engine --> Transform --> Delivery
  Delivery --> Targets
  Targets --> HTTP
  Targets --> Kafka
  Targets --> DB
  Targets --> SaaS
  Targets --> Files
```

## 3. Message route execution sequence

```mermaid
sequenceDiagram
  autonumber
  participant Source as Source Endpoint
  participant Adapter as Consumer Adapter
  participant Route as Route Engine
  participant Policy as Auth / Validation / Policy
  participant Transform as Mapper
  participant Target as Destination Adapter
  participant Store as Offset / Inbox / Outbox
  Source->>Adapter: Deliver message / poll record
  Adapter->>Store: Check deduplication / delivery identity
  Store-->>Adapter: New or already processed
  Adapter->>Route: Dispatch accepted message
  Route->>Policy: Validate schema, identity and route policy
  Policy-->>Route: Allow / reject
  Route->>Transform: Map / enrich / split / aggregate
  Transform-->>Route: Typed output
  Route->>Target: Deliver with idempotency key
  Target-->>Route: Ack / retryable error / permanent error
  Route->>Store: Commit outcome / checkpoint
  Route-->>Adapter: Ack only after configured completion boundary
```

The acknowledgement and offset-commit boundary must be explicit. Avoid acknowledging before durable acceptance when at-least-once delivery is required.

## 4. EIP routing patterns

```mermaid
flowchart LR
  In[Endpoint] --> Validate[Validate]
  Validate --> Filter{Filter}
  Filter -->|accepted| Router{Content-based router}
  Filter -->|rejected| Reject[Reject / quarantine]
  Router -->|A| Direct[Direct route]
  Router -->|B| Enrich[Enrich]
  Direct --> Split[Split]
  Enrich --> Aggregate[Aggregate]
  Split --> Aggregate
  Aggregate --> Transform[Transform]
  Transform --> Out[Destination]
  Out --> Ack[Acknowledge]
  Reject --> Error[Error channel]
  Error --> DLQ[(Dead-letter queue)]
```

The pack catalog should group patterns by semantics: channel adapters, message construction, routing, transformation, endpoint interaction, system management and error handling. Each node documents delivery guarantees, ordering, concurrency, transaction support and resource requirements.

## 5. Retry and dead-letter sequence

```mermaid
sequenceDiagram
  autonumber
  participant Route as Route Engine
  participant Target as Destination
  participant Retry as Retry Policy
  participant DLQ as Dead-letter Store
  participant Ops as Operator / Replay Tool
  Route->>Target: Deliver message (idempotency key)
  Target-->>Route: Transient failure
  Route->>Retry: Classify and apply bounded retry budget
  loop While budget remains and deadline permits
    Retry->>Target: Retry delivery
    Target-->>Retry: Ack or failure
  end
  alt Delivery succeeds
    Retry-->>Route: Success
  else Permanent or exhausted
    Retry->>DLQ: Persist original envelope, headers and failure metadata
    DLQ-->>Ops: Alert / inspect
    Ops->>DLQ: Approve replay / repair payload
    DLQ->>Route: Replay with original identity + replay attempt metadata
  end
```

Do not retry non-idempotent side effects blindly. Define poison-message handling, retention, encryption, access control, replay authorization and audit. Preserve original message identity and add a distinct replay attempt ID.

## 6. Delivery semantics and operations

- Document at-most-once, at-least-once or transactional semantics per connector/route; do not claim end-to-end exactly-once across arbitrary external systems.
- Define ordering key/partition behavior, consumer group ownership and concurrency.
- Use inbox/outbox and idempotency keys where needed.
- Bound retries by attempt count, backoff, jitter and total deadline.
- Make DLQ replay explicit, authorized, observable and auditable.
- Protect against SSRF, unsafe redirects, private/metadata network access, credential leakage and unbounded payloads.
- Store connector credentials as secret references; redact headers and payload fields.
- Support schema evolution and compatibility checks at route boundaries.

## 7. Scaling and high availability

Scale consumers by partitions or source concurrency and destinations by independent throughput limits. Scale stateless transformation workers horizontally. Preserve partition/order affinity where required. Use backpressure so a slow destination cannot create unbounded memory growth.

For distributed deployments, register runtime capabilities and connector availability in discovery. Use partition/lease ownership or the broker's consumer-group protocol rather than assigning the same partition to multiple active consumers. Rebalance gracefully: stop intake, finish/commit safe work, release ownership, then start replacement consumers. Store offsets/checkpoints durably and test broker, database and destination outage recovery.

A route may be deployed as a dedicated minimal integration runtime. Avoid loading agent/BPMN engines unless the artifact requires them.
