# Consolidation of the imported modules

Principle: Wayang owns definitions and UX; it must not grow a second workflow engine or a second copy of
things Gamelan/Andalus already do. Imported code was kept only where it adds something that is not already
covered by the verified modules; value was **ported** into `wayang-api-core` rather than compiled in place
(the imported modules use Quarkus/Lombok/Mutiny and could not be built in the verification environment).

## Verdicts

| Imported module | Verdict | Where its value went |
|---|---|---|
| `wayang-control-grpc` | **Removed** | Superseded by `wayang.proto` + `WayangGrpcService` (tenant from the authenticated principal). The original trusted a client-supplied tenant |
| `wayang-control-websocket` | **Removed** | Rooms/presence/broadcast semantics ported to `Rooms` + `WsSession`; tenant-namespaced, principal-authenticated, size/rate limited. The original accepted an unvalidated Bearer value and a client-chosen tenant |
| `wayang-schema-grpc` | **Removed** | Transport wrapper only; schema validation is exposed over REST (`validate`, `GET /catalog/nodes`) |
| `subworkflow-runtime` | **Removed** | A runtime extension of Gamelan: belongs in the Gamelan repo, not here. Call-activity/sub-process support in Wayang is a pending feature that should *use* it |
| `wayang-control-core` | **Attic** | Projects -> `Project`/`ProjectStore` (+JDBC); canvas auto layout -> `AutoLayout`; canvas validation -> `WayangApi.validate` + `NodeCatalog` schemas; `DeploymentManager`/`DesignerService` were stubs. Left: `TemplateCatalogService`/`WorkflowTemplate` (route templates) — port if the Studio wants a template gallery |
| `wayang-schema-core` / `wayang-schema-api` | **Attic** | Replaced for node config by `NodeCatalog` + `JsonSchemaLite` (bounded subset, verified). Keep if full JSON-Schema (refs, oneOf, formats) is needed for user-defined connector schemas |
| `wayang-control-spi` | **Attic** | Overlaps `DefinitionStore`/`RunEngine`/`WaitRegistry` SPIs. Mine for plugin/extension-point ideas |
| `wayang-vault-manager` | **Attic** | Core need met by `SecretCipher`/`SecretService`/`SecretStore` (+`${secret:NAME}`). HashiCorp Vault is done (`VaultClient`, `${secret:vault.NAME}`, master keys from a file or Vault: `docs/WORKSPACE.md`). Left: AWS Secrets Manager / Azure / GCP backends — implement as `SecretResolver` adapters behind the same SPI |

`attic/` is not in the root pom and nothing depends on it; delete it once you have taken what you want.

## Security findings in the imported code (fixed by replacement, not by patching)

1. control-websocket: tenant taken from client input; Bearer token not validated -> cross-tenant read/spoof.
2. control-grpc: tenant supplied by the client in the request.
3. vault `SecretResource`: unauthenticated, client-supplied tenant, **returns plaintext secrets**.
4. vault `KeyManager`: AES/ECB with no IV (leaks equal blocks, unauthenticated). Replaced by AES-256-GCM, random IV, AAD binding tenant+name+version.
5. Local secret manager was in-memory (lost on restart).
6. `WayangDefinitionService.findById` was not tenant-scoped.

## What was added in this pass (all verified only as stated in ARCHITECTURE.md)

Studio envelope (`studio` key, stripped before compile), projects, auto layout, node catalog with config
schemas, write-only secrets with `${secret:NAME}` (service/agent/EIP nodes only; values redacted from
errors/outputs; refused inside expression `inputs` if they contain quotes; tenant comes from a variable the
engine overwrites, never from caller input), collaboration rooms, REST for catalog/projects/secrets.

## Still open

- Real Gamelan: `GamelanRunEngine` has only run against a fake client. Quarkus/gRPC shells compiled only against stubs; `.proto` never compiled; Maven build never run (verification used plain `javac` plus a JUnit-API shim).
- Master key from a cloud KMS (a file and HashiCorp Vault are done); per-secret access policy (reads are audited with `wayang.audit.secret-reads`); keyring reload is per replica.
- Rooms are single-node (no cross-node fan-out); Flutter web cannot send an Authorization header on WebSocket (use a short-lived ticket or cookie).
- Call activity / sub-process, triggers (schedule, webhook, message start), message buffer is not tenant-namespaced.

## Studio and Console (Flutter) — `studio/`

The two Flutter apps (`wayang_studio`: BPMN/DMN/EIP designers; `wayang_console`: platform admin) were Malacca-era
and talked to a different backend. They are consolidated into **one app, `studio/`**, over the Wayang API.

| From | Verdict |
|---|---|
| Studio: BPMN canvas, DMN table editor, plugin registry, theme/widgets | **Kept and adapted.** Models use the 12 Wayang element types; hit policies limited to what the engine supports; lanes/layout/column labels live in `studio.meta` |
| Studio: palette/inspector | **Rewritten**: palette and property forms are generated from `GET /catalog/nodes` (`SchemaForm`), so editor and server share one schema |
| Studio: save/validate/test/publish/BPMN XML | **Now server-side** (`WayangRepository`, optimistic `If-Match` drafts); no local XML writer or validator |
| Studio: boundary events, sub-process, script task, text annotation, DMN DRD and extra hit policies | **Dropped** (no engine support; shown as unsupported rather than silently converted) |
| Studio: EIP graph canvas, schema registry, connectors screen | **Not ported**; agents and pipelines get a validated JSON editor |
| Console: executions, tasks, projects, secrets, settings, dashboard | **Ported** as Runs (polling, cancel, path highlight on the canvas), Task inbox (+ send message), Projects, Secrets (write-only), Settings, Overview |
| Console: IAM, organizations, billing, usage, audit, guardrails, deployments, evaluation, webhooks, tools, policies, system | **Not ported**: Wayang has no such API; belongs to the platform around it (identity/billing) or is future work |

Contract: `docs/contract/*.json` are what the Dart mappers must produce; the Java side executes them
(`ContractFixturesTest`). `studio/test/mapper_test.dart` checks the Dart side.

## Identity: Numu instead of re-implementing it

Wayang's own token handling stays minimal (`jwt` mode). Users, service accounts, PATs, OIDC/SAML/LDAP federation, tenants
and path-scoped roles are Numu's (`docs/IDENTITY.md`); Wayang does not copy them. The Console's IAM/organizations screens
are therefore not ported: they belong to Numu's own UI.
