# From design to release

A project has two phases.

1. **Design** (the Wayang server, Studio, API): you compose workflows, decisions, agents and integrations, test them, and
   publish versions. Everything is a definition, in one server, with drafts and tooling around it.
2. **Release** (this document): the *published* versions are turned into a small project that runs **without** this server,
   either as a command-line program (`STANDALONE`) or as an HTTP microservice (`SERVICE`), and depends only on what the
   workflows use.

Wayang still does not grow a second workflow engine: a release embeds the same node runner and reference engine used in design.

## What goes in, what comes out

`POST /api/v1/projects/{id}/release/plan` says what a release would contain; `POST /api/v1/projects/{id}/release` downloads it as a
zip (a Maven project). Body (all optional): `target` (`SERVICE` default | `STANDALONE` | `GAMELAN`), `gamelanVersion`/`quarkusVersion` (GAMELAN only), `groupId`, `artifactId` (default from the
project name), `version`, `package`, `port`, `profile`. The plan also returns the exact component IDs selected by the profile. All options are validated. Needs the same permission as
exporting the project. The Studio's Workspace screen has a *Release* card that shows the plan and the download command (it never
puts your token in the text).

The input is the project's workspace bundle (see `docs/WORKSPACE.md`) built in *release mode*:

* **published versions only**. A draft is never used; a definition that was never published stops the release and is named. If a
  newer draft exists it is reported in the bundle's notes, and the published version is what ships.
* **enabled triggers only** (switched-off ones are dropped, no design-time secret hashes), **no secret values** (only `${secret:NAME}` references), **only the settings the definitions refer to**
  (tenant-wide settings nothing uses are left out), nothing that ties it to this server (no project id).

The publisher resolves each process element to a stable component ID and expands the exact dependency closure from the profile-filtered registry. The component lock is dependency-first, sorted and SHA-256 digested; the digest and component IDs are repeated in release metadata. This is a component contract lock, not yet a full Maven artifact/JAR closure.\n\nThe output is deterministic: the same bundle and options give a byte-identical zip (sorted entries, fixed timestamps), so a release
can be compared and reproduced.

```
pom.xml                              only wayang-embed (+ wayang-connectors when a workflow calls a connector)
src/main/java/<pkg>/App.java         generated: builds the runtime, wires only the connectors the workflows call
src/main/java/<pkg>/Processes.java   generated: process ids as constants, published versions, connectors used
src/main/java/<pkg>/Agents.java      only when a workflow has agent tasks: an explicit stub to implement
src/main/resources/wayang/bundle.json   the workflows; do not edit, release again instead
src/main/resources/wayang/component-lock.json  exact component IDs, versions, dependency closure and profile\nsrc/main/resources/wayang/release.json  target, bundle hash, processes, components, connectors and component-lock digest
Dockerfile, .dockerignore, .gitignore, .env.example, README.md
deploy/kubernetes.yaml               SERVICE only: Secret, Deployment (probes, non-root, read-only fs), Service
```

## Running a release

```
mvn -q package
java -jar target/app.jar check               # lists missing secrets; exit 1 if any
java -jar target/app.jar run <process> --input in.json [--wait-seconds N]   # exit 0 completed, 1 failed, 2 waiting
java -jar target/app.jar serve               # SERVICE: HTTP on WAYANG_PORT
```

Secrets come from the environment, never from the bundle: a file `$WAYANG_SECRETS_DIR/NAME` (Docker/Kubernetes secrets) or the
variable `WAYANG_SECRET_<NAME>` (upper case, `.` and `-` become `_`). Settings: `WAYANG_CONFIG_<NAME>`, else the value the bundle
carried. Vault, cloud (`aws.`, `gcp.`, `azure.`) and `file.` secret names are not supported in a release: generation refuses them.
`WAYANG_REQUIRE_SECRETS=true` refuses to start while one is missing. Connectors keep their SSRF guard:
`WAYANG_CONNECTORS_ALLOWED_HOSTS`, `WAYANG_CONNECTORS_ALLOW_PRIVATE`.

### The HTTP service

```
GET  /healthz   GET /readyz            probes, no auth (readyz: 503 while a required secret is missing; counts only)
GET  /v1/release                       project, processes + versions, bundle hash, missing secret names
POST /v1/processes/{id}/runs           {"input": {...}}  -> 201 with the run (maybe WAITING with a task token)
GET  /v1/runs[?processId=&limit=]      GET /v1/runs/{id}      POST /v1/runs/{id}/cancel
POST /v1/tasks/{token}/complete        {"outputs": {...}}
```

Everything under `/v1` needs `Authorization: Bearer <WAYANG_API_KEY>` (or `WAYANG_API_KEY_FILE`), compared in constant time.
Without a key it starts only on loopback. Bodies are capped (1 MB), must be JSON objects, and errors never carry a stack trace.

## Limits you should know about

* **SERVICE/STANDALONE: runs live in memory** and are lost on restart; one replica only. For durable or long-running workflows use
  the `GAMELAN` target (below).
* **Agent tasks** run through the built-in model loop (below); `Agents.java` is the seam to swap in another runtime such as Andalus.
* **Triggers** (webhook and polling) run in `serve` only; see below. Chat channels beyond the poll sources and webhook providers listed there are not carried.
* **Dependency pruning is coarse**: `wayang-embed` depends on `wayang-runtime`, which carries the expression, DMN, BPMN, EIP and
  agent libraries; the one real choice is the connector library. No Quarkus, REST framework, store or Studio code is included.
* The generated project needs `tech.kayys.wayang:wayang-embed` (and `-connectors`) at `wayang.release.wayang-version` from a
  repository you control (`mvn install` in the Wayang repo, or your registry): they are not on Maven Central.

## Verified and not

Verified here with the plain-javac runner (no Maven): bundle parsing and refusal of unpublished definitions, the embedded runtime
(secrets/settings from the environment, parking on tasks, cancel, listing), the HTTP service (auth, limits, errors, loopback rule), the
CLI exit codes, release mode export (published-only, no triggers, only referenced settings, drafts reported, triggers), option validation and
injection resistance, determinism, and, importantly, **the generated sources are compiled with `-Werror` and run in a separate JVM**:
`check`, `run` (calling a local HTTP server through the real `http` connector with a secret from the environment) and `serve`.
Mutation-checked: 24 of 25 mutants of the embed and publish code were killed; the survivor (zip timestamp) became a test.

**Not verified:** the generated `pom.xml`, `Dockerfile` and `kubernetes.yaml` have never been run (no Maven, Docker or cluster here);
the REST resource compiles against stubs only; the Studio card is parse-checked only; the embedded runtime has never been used with
a real external service, agent runtime or under load.

## `doctor`

`java -jar app.jar doctor [--live] [--json]` (STANDALONE and SERVICE targets) lists what is wrong or risky: missing secrets, an unwritable `WAYANG_STATE_DIR`, no API key, no token cap,
chat triggers without an allow-list, rate limit or approvals, an invalid or plain-http model URL, schedules (with the one-copy warning). `--live` also makes one read-only call per model provider and per Telegram/Slack bot token.
Exit code 0 unless something failed. Findings name secrets, never print values, and probe errors are reported by kind only. The GAMELAN target has no `doctor` command (it is a Quarkus service); run it from a STANDALONE build of the same bundle.
Step-by-step real-world verification: `docs/SMOKE.md`. Tested with fakes and by running the generated project's `doctor`; `--live` has never been run against the real providers.

## Triggers in a release

Enabled triggers of the project travel in the bundle and are served by the generated service (`serve`), with the same rules as the design-time trigger service:

* **Webhook** `POST|GET /hooks/<id>`, no API key (it has its own authentication).
  * Shared-secret webhooks read `X-Wayang-Secret` and compare it in constant time with secret **`trigger.<id>`**, i.e. `WAYANG_SECRET_TRIGGER_<ID>` or a file `trigger.<id>` in `WAYANG_SECRETS_DIR`. The secret shown once at design time cannot be carried (only its hash exists), so choose a new one for the release. With no secret configured every call is refused with the same 401 as an unknown trigger.
  * Provider webhooks (`config.auth`: `slack`, `whatsapp`, `github`) are verified by the provider's signature, answer handshakes, and are de-duplicated by event id (last 5,000 per trigger). Their secrets are `${secret:NAME}` references like any other.
  * Body at most 1 MB, event payload at most 64 KB. Run input is `{event, trigger}`.
* **Poll** (`telegram`, `gmail`, `imap`, `rss`): every `everySeconds` (5..86400), at-least-once (the cursor moves only after the whole batch started runs), exponential backoff on failure up to 1 hour, errors masked of secret values. Set `WAYANG_STATE_DIR` to keep cursors across restarts; without it a restart starts from no cursor.
* `SCHEDULE` triggers (cron, see `docs/TRIGGERS.md`) are carried too; the release validates the cron at load and keeps the last run in `WAYANG_STATE_DIR`.
* `GET /v1/triggers` (API key) shows per trigger the last poll time, failure count and last error; never configuration.
* The generator adds `wayang-connectors` when triggers need it and wires only the poll sources and providers used. Unknown sources or providers refuse the release. `check`/`/readyz` count missing trigger secrets.

Limits: poll cursors are per process (one replica only, or each replica polls the same source and runs every event); webhook runs are in memory like all runs; nothing here has run against real Slack, WhatsApp, GitHub, Telegram, Gmail or IMAP. Only a local RSS feed and a local webhook caller were used in the tests.

## The GAMELAN target (durable runs)

`target: GAMELAN` generates a **Quarkus** project instead of a plain jar. Its runs are Gamelan's, so they survive a restart of the
service; everything else is the same API (`/v1/...`, `/hooks/<id>`, `/healthz`, `/readyz`) and the same triggers.

How it works:
* The project depends on `wayang-gamelan-worker` (the Wayang executors, the plan-to-Gamelan mapper and `GamelanReleaseEngine`; no
  api-core, no REST adapter), `wayang-embed`, `gamelan-sdk-executor-remote` and `gamelan-sdk-client-remote`. `application.properties`
  indexes those jars so Quarkus finds the executors, and registers them with the engine over gRPC.
* At start `ReleaseApp` deploys each process to Gamelan as `release~<process>` with version `<published>-<hash of the document>`
  (so a re-release with changed content never runs the older definition Gamelan already holds). It retries for
  `WAYANG_GAMELAN_DEPLOY_MINUTES` (default 10) while Gamelan is unreachable; `/readyz` is 503 until the deploy worked or while a
  secret is missing. Gamelan being down answers 503 on `/v1`, without endpoint details.
* Configuration: `WAYANG_GAMELAN_ENDPOINT` (required), `WAYANG_GAMELAN_API_KEY`, `WAYANG_GAMELAN_GRPC_HOST/PORT`,
  `WAYANG_GAMELAN_JWT_ENABLED/JWT`, plus all the usual `WAYANG_*` variables. The Gamelan engine must accept the tenant `release`.
* Decisions and agents come from the bundle (tenant-qualified `release/<id>`); other tenants' references resolve to nothing.

Limits: human tasks, timers and message waits are **parked in the process that ran the node** (as in the design-time adapter), so keep
**one replica** when a workflow waits; after a restart Gamelan re-dispatches the node and it re-attaches. Poll-trigger cursors are
per process (`WAYANG_STATE_DIR`). There are no `check`/`run`/`info` commands. **Not verified:** `GamelanReleaseEngine` was tested only
against an in-memory fake of the Gamelan client; the generated Quarkus sources were compiled with `javac` against stubs for Quarkus/CDI
only; the pom, Quarkus build, gRPC registration, Dockerfile and manifest were never built or run, and nothing ran against a real Gamelan.
Assumptions to check live: status strings, the "already exists" error on redeploy, the 404 text, that the engine accepts tenant `release`.

## Agents in a release

A release has no agent platform, so agent tasks run in `LlmAgentInvoker` (wayang-embed, JDK HTTP only): a tool-calling loop against a chat model.

* `model` is `provider/model`: `openai`, `openrouter`, `groq`, `together`, `mistral`, `deepseek`, `ollama`, `vllm` (OpenAI-compatible chat API) or `anthropic` (Messages API).
* **Key:** secret `llm.<provider>.api-key` (`WAYANG_SECRET_LLM_<PROVIDER>_API_KEY`); not needed for `ollama`/`vllm`. The bundle names the keys it needs, so `check` and `/readyz` report a missing one. **Endpoint:** `WAYANG_LLM_<PROVIDER>_URL`, else the provider's default. Both are the operator's: a workflow cannot point an agent at another host. The key goes only to that endpoint and is masked in errors.
* **Tools** are the agent's declared tools: `type` is a connector of the release, `config` its configuration (`${secret:}`/`${config:}` resolved; `parameters` is the JSON schema shown to the model), the model supplies the arguments. A tool without such a connector, or listed in `requireApproval`, is **not run** (a release cannot pause an agent for a human): the model is told so.
* `maxSteps` bounds model calls and `timeoutSeconds` the whole run; tool output is cut at 20,000 characters. Options: `temperature`, `maxTokens`.
* Result: `output`, `steps`, `toolCalls`. Failures from the model endpoint fail the node (the engine's retry policy decides about retrying).

Not verified: tested against scripted fake replies and one local HTTP server speaking the OpenAI shape; **never against a real OpenAI, Anthropic, Ollama or other model**, so real-world response quirks (streaming-only models, tool-call formats of other servers, rate limits) are untested. No conversation memory across runs, no streaming, no token accounting.

Connectors named by an agent's tools (`type`) are wired into the release even when no process calls them. A tool type with no connector is not a release error: the agent is told it has no such tool.

## Agent memory

An agent with `options.memory: {key: "{{ event.chatId }}", turns: 10}` is given the last `turns` exchanges of the conversation `key` names
(rendered from the run's variables) and adds each new one after it answers. What is kept is only what the person said and what the agent
answered, never tool output, cut to 8,000 characters per message, private to tenant + process + agent, and dropped after 30 days. The
model's chat history is those exchanges in order (OpenAI-compatible and Anthropic providers). Where it lives: the server keeps it in memory,
or in the database table `wayang_agent_memory` with `wayang.store=jdbc` (shared by replicas); a release keeps it in memory, or in files under
`WAYANG_STATE_DIR` (`memory-<hash>.json`, one process only), which survives restarts. It holds chat text: protect that directory like the
secrets directory. Agents run by the Andalus adapter receive the history in `input.history` but the adapter does not use it yet. There is no
summary of old turns: the oldest exchange simply falls off.
