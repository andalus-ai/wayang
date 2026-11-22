# 3. Execution and sequences

## The node runner

`NodeRunner` takes a `PlanNode` and the run's variables and returns a `NodeOutcome`: `Completed(outputs)`, `Waiting(ticket)` or `Failed(error, retryable)`. It picks the `DomainHandler` for the node's executor type, handles dead paths and flow flags, and applies the profile (a handler for a disabled feature is dropped, so the node fails with a clear message). A handler never throws for an expected failure; it returns `Failed`.

```mermaid
flowchart TB
  n[PlanNode + variables] --> act{Any live<br/>incoming flow?}
  act -- no --> dead[Completed: active=false,<br/>outgoing flows dead]
  act -- yes --> prof{Feature enabled<br/>by profile?}
  prof -- no --> f[Failed: needs wayang-agent...]
  prof -- yes --> h[DomainHandler.handle]
  h --> o{Outcome}
  o -- Completed --> flags[add outgoing-flow flags]
  o -- Waiting --> park[register ticket in WaitRegistry]
  o -- Failed --> pol[engine retry policy / onError]
```

Handlers: `ControlHandler` (events, gateways), `ServiceHandler` (connectors), `DmnHandler`, `EipHandler`, `AgentHandler`, `HumanHandler`; an extension adds its own `DomainHandler` for a custom `executorType`.

## Sequence: design to a finished run (Community, in-process)

```mermaid
sequenceDiagram
  actor U as Designer
  participant S as Studio
  participant A as Design server (api-core)
  participant D as DefinitionStore
  participant E as RunEngine (reference or Gamelan)
  participant R as NodeRunner
  U->>S: edit process
  S->>A: PUT /definitions/process/{id}/draft (If-Match)
  A->>D: save draft revision
  S->>A: POST .../validate
  A-->>S: issues (live)
  U->>S: publish
  S->>A: POST .../publish
  A->>A: validate, check referenced definitions are published
  A->>D: store immutable version n
  U->>S: start run
  S->>A: POST /runs {processId, input}
  A->>A: compile to ExecutionPlan
  A->>E: start(tenant, user, plan, input)
  loop each ready node
    E->>R: run node
    R-->>E: Completed / Waiting / Failed
    E-->>A: RunEvent
    A-->>S: WebSocket /ws/events
  end
```

## Sequence: a service task calling a connector

```mermaid
sequenceDiagram
  participant R as ServiceHandler
  participant X as Expressions / Mapper
  participant V as SecretResolver
  participant C as Connector
  R->>X: evaluate inputs / inputMapping over variables
  R->>R: tenantScoped? set config["wayang.tenant"] from the run
  R->>V: resolve ${secret:NAME} in config
  R->>R: drop missing mandatory inputs, collect warnings
  R->>C: call(config, inputs)
  alt success
    C-->>R: outputs
    R->>X: outputMapping
    R-->>R: Completed(outputs + warnings)
  else ConnectorException(retryable?)
    C-->>R: error
    R-->>R: Failed(message, retryable)
  end
```

A missing **mandatory** input never stops a run: the connector gets no value, the node adds a `warnings` entry, and the run completes. Error messages never include secret values.

## Retries, timeouts, errors

`retries` (0..10) are extra attempts with `retryDelay`, `retryMaxDelay`, `retryBackoff`; `timeout` is per attempt; `onError: continue` makes the last failure a completed node with `failed=true` and `error=<message>` so a gateway can route on `<node>.failed`. Gamelan retries every failure, so make connector calls idempotent before enabling retries. Routing errors (no matching flow) always fail the run. Details and engine facts: [ARCHITECTURE.md](../ARCHITECTURE.md).

## Sequence: human task, timer and message waits

```mermaid
sequenceDiagram
  participant E as Engine
  participant H as HumanHandler
  participant W as WaitRegistry
  actor P as Person
  participant A as API
  E->>H: run USER_TASK
  H->>W: register(runId, ticket{token, nodeId, kind=task}) - idempotent per run+node
  H-->>E: Waiting(ticket), the executor call stays parked
  P->>A: GET /tasks, then POST /tasks/{token}/complete {outputs}
  A->>W: take(token)
  A->>E: completeWait(token, outputs)
  E-->>E: node Completed, dependents scheduled
```

Timers: a ticket with `dueAt`; a ticker (every 5 s) calls `fireDue()`. Messages: `POST /messages {name, correlationKey, payload}` completes a matching wait or is **buffered** (bounded) until the wait parks. Tickets are durable with `wayang.store=jdbc`; the parked executor call itself still lives in the executor JVM (a known limit in ARCHITECTURE.md).

## Sequence: an agent step with a tool call

```mermaid
sequenceDiagram
  participant R as AgentHandler
  participant I as AgentInvoker (Andalus adapter or LlmAgentInvoker)
  participant M as Model
  participant T as Tool = connector
  R->>I: invoke(agent spec, input, tenant)
  loop up to maxSteps
    I->>M: prompt + tool schemas
    M-->>I: answer or tool call(name, arguments)
    opt tool call
      I->>I: requireApproval? then park for a person
      I->>T: call(author config + tenant, model arguments)
      T-->>I: result (untrusted data)
    end
  end
  I-->>R: outputs (outputKeys)
```

Tool results, retrieved passages and fetched pages are **untrusted data** and are passed to the model as such, never as instructions. The run's tenant is injected into tenant-scoped tools by the engine; the model cannot choose it.

## Sequence: RAG (knowledge) ingest and answer

```mermaid
sequenceDiagram
  participant F as file-put / file-fetch
  participant S as FileStore
  participant G as rag-ingest
  participant X as Extractor (txt, pdf, docx, image OCR...)
  participant K as KnowledgeBase (+ persistence)
  participant Q as rag-search
  F->>S: store bytes, get fileId (tenant, retention)
  G->>S: read file by fileId
  G->>X: extract text
  G->>K: chunk (fixed, paragraph, heading, clause, parent-child), embed, index, persist source
  Note over K: expired documents are removed on use
  Q->>K: bm25 / vector / hybrid, MMR, parent expansion, filter
  K-->>Q: passages with source and score
```

The knowledge base is keyed by tenant and name, kept in memory and rebuilt from stored sources after a restart. More in [RAG.md](../RAG.md).

## Sequence: triggers

```mermaid
sequenceDiagram
  participant X as Source (Telegram, mailbox, webhook, cron, RSS)
  participant T as Trigger service
  participant C as Cursor store
  participant A as API
  X-->>T: new events (Poller.poll(config, cursor)) or webhook (signature checked)
  T->>A: start run per event (small flat inputs)
  T->>C: save nextCursor only after hand-over (at-least-once)
```

Pollers are stateless (the caller owns the cursor), webhooks verify provider signatures. See [TRIGGERS.md](../TRIGGERS.md).

## Tenancy, identity and RBAC

Every API call carries a tenant from the authenticated principal (JWT claim `tenant_id` by default, or Numu). Definitions, runs, secrets, knowledge bases and files are keyed by tenant. Roles: VIEWER < OPERATOR < DESIGNER < ADMIN ([API.md](../API.md), [IDENTITY.md](../IDENTITY.md)). A generated release has one tenant (`release`) and an API key.
