# 1. System architecture

## Principles

- Wayang owns **definitions** (what to run); it never implements a second durable workflow engine.
- **Gamelan** owns durable execution (scheduling, retries, persistence, run history). **Andalus** owns agent execution (models, tools, memory). **Numu** (optional) owns identity and tenancy.
- One definition model, several execution topologies. Engine-neutral at the Wayang boundary: no Gamelan or Andalus types in the definition modules.
- Honest verification: documents say what was tested and how.

## Context

```mermaid
flowchart LR
  user([Designer / operator]) --> studio[Studio<br/>Flutter]
  studio -->|REST, WebSocket, gRPC| api[Design server<br/>wayang-api-rest / api-core]
  api --> store[(Definitions, secrets,<br/>waits: memory or JDBC)]
  api -->|run engine| ref[Reference engine<br/>in-process]
  api -->|run engine| gam[Gamelan<br/>durable engine]
  gam --> worker[Wayang worker<br/>wayang-gamelan-worker]
  ref --> rt[wayang-runtime<br/>NodeRunner + handlers]
  worker --> rt
  rt --> andalus[Andalus<br/>agents]
  rt --> ext[Connectors, MCP, LLM,<br/>knowledge, files]
  api -->|publish + release| rel[wayang-publish]
  rel --> gen[[Generated runtime<br/>standalone service]]
  gen --> rt2[wayang-embed<br/>same NodeRunner]
  console[Wayang Console<br/>separate repository] -. heartbeat / commands .-> gen
  numu[Numu identity] -. optional .-> api
```

## Planes

| Plane | Product | Responsibility |
|---|---|---|
| Design | Studio + design server | compose, validate, test, version, publish definitions |
| Execution | generated runtimes, or the in-process engine in Community | run published definitions |
| Management | Wayang Console (own repository) + a Management Agent inside each runtime | inventory, deploy, observe, govern |

No runtime needs the Console or Studio to keep executing.

## Two editions from one codebase

```mermaid
flowchart TB
  subgraph community[Community / development: all-in-one]
    s1[Studio] --- d1[Design server] --- e1[Execution in the same process]
  end
  subgraph pro[Pro / Enterprise: separated]
    s2[Studio for production<br/>design only] -->|publish, release| g1[Agent runtime service]
    s2 -->|publish, release| g2[Workflow runtime service<br/>BPMN / DMN / EIP]
    c[Wayang Console] -.-> g1
    c -.-> g2
  end
```

The design server's mode is `WAYANG_MODE` (`community` executes, `design` refuses to execute). Details and status (what exists, what is proposed): [EDITIONS.md](../EDITIONS.md).

## Layers and modules

```mermaid
flowchart TB
  subgraph defs[Definitions: pure, engine-neutral]
    bp[wayang-business-process<br/>BPMN subset, compiler]
    dmn[wayang-dmn]
    eip[wayang-integration<br/>EIP + mapper]
    ag[wayang-agent<br/>specs, templates]
    ex[wayang-expression<br/>FEEL-style]
    cel[wayang-cel]
  end
  subgraph run[Execution]
    rtm[wayang-runtime<br/>NodeRunner, handlers, waits,<br/>profiles, extensions]
    con[wayang-connectors<br/>http, chat, mail, SaaS...]
    rag[wayang-rag + rag-pdf]
    kn[wayang-knowledge<br/>rag-*, file-* extension]
    mcp[wayang-mcp]
    mm[wayang-multimodal]
  end
  subgraph adapters[Adapters]
    ga[wayang-adapter-gamelan<br/>wayang-gamelan-worker]
    aa[wayang-adapter-andalus]
    na[wayang-adapter-numu]
  end
  subgraph product[Product layer]
    api[wayang-api-core / api-rest]
    pub[wayang-publish]
    emb[wayang-embed]
    dep[wayang-deployment<br/>management-client]
    st[studio]
  end
  defs --> rtm
  rtm --> con & rag & kn & mcp & mm
  rtm --> adapters
  api --> rtm
  pub --> emb
  emb --> rtm
  st --> api
```

Dependency direction is one-way: definitions know nothing of execution; execution knows nothing of the API; adapters implement ports defined here. The full module table is in the root [README](../../README.md).

## Boundaries with other repositories

```mermaid
flowchart LR
  W[andalus-ai/wayang] -->|depends on| C[wayang-core<br/>shared contracts]
  K[andalus-ai/wayang-console] -->|depends on| C
  W -->|adapters| G[gamelan]
  W -->|adapters| A[andalus]
  W -.optional.-> N[numu]
```

Core must never depend on Wayang or the Console. Console persistence, fleet registry and UI live only in the Console repository (`AGENTS.md`).
