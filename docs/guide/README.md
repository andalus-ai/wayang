# Wayang architecture and developer guide

A guided tour of how Wayang is built and how to extend it. Diagrams are [Mermaid](https://mermaid.js.org/) (GitHub renders them).
Everything here describes the code in this repository; where a statement is a plan it says **proposed**. The reference documents under `docs/` (RELEASE, RAG, API, PROFILES...) stay the source for details; this guide connects them.

| Read | When you want to |
|---|---|
| [1. System architecture](01-system-architecture.md) | see the planes, the two editions, the modules and who owns what |
| [2. Definitions, nodes and the plan](02-workflow-model.md) | understand BPMN / DMN / EIP / agent definitions, node types and how they compile |
| [3. Execution and sequences](03-execution-sequences.md) | follow a run: start, handlers, retries, waits, agents, human tasks, triggers, errors |
| [4. Features](04-features.md) | find what Wayang offers: connectors, RAG, files, triggers, secrets, tenancy, profiles |
| [5. Release and operations](05-release-and-operations.md) | go from design to a generated runtime, deployment modes, Console and Management Agent |
| [6. Developer guide](06-developer-guide.md) | add a connector, extension, node type, RAG method, Studio screen; test and document it |
| [Editions](../EDITIONS.md) | Community all-in-one versus Pro/Enterprise separated |

Verification vocabulary used everywhere: **unit-tested** (JUnit, in CI), **contract-tested** (against a real PostgreSQL or a local HTTP server),
**compiled only** (built in CI, never run against the real system), **not verified**.
