# Workspace bundles, configuration and the vault

Three things that let the same workflows run in more than one environment (dev, staging, production, another tenant) without
copying credentials around.

| | What it is | Where secrets are |
|---|---|---|
| **Workspace bundle** | A project as one JSON file: definitions, triggers, settings, and what the target must provide | Never in it: only `${secret:NAME}` references |
| **Configuration** | Plain per-environment settings, `${config:NAME}` in a node config | Not for secrets: names that look like one are refused |
| **Vault** | Secret store master keys from a file or HashiCorp Vault; `${secret:vault.NAME}` read live from Vault; an encrypted archive to move secrets | Encrypted at rest (AES-256-GCM); archive protected by a passphrase |

## Workspace bundle

`GET /api/v1/projects/{id}/export[?includeTenantConfig=true][&only=kind:id,...]` (a read; `only` narrows the bundle to the listed definitions, for example `only=process:orders,decision:risk`, case-insensitive; a selector that matches nothing is a 422 listing it, and a definition a selected one refers to is not added for you; triggers follow the processes that are in) and `POST /api/v1/workspace/import`
`{bundle, options}`.

```json
{"format":"wayang.workspace","formatVersion":1,"exportedAt":"...",
 "project":{"name":"Shop","description":""},
 "definitions":[{"kind":"PROCESS","id":"orders","publishedVersion":0,"document":{...}}],
 "triggers":[{"id":"poll1","kind":"POLL","processId":"orders","enabled":true,"config":{...}}],
 "config":{"project":{"CHANNEL":"#orders"},"tenant":{...}},
 "requires":{"secrets":["SLACK"],"externalSecrets":[],"oauthConnections":[],"config":["CHANNEL"]},
 "integrity":{"sha256":"...","hmac":"..."}}
```

* The draft of each definition is exported (the latest published version when there is no draft). The id of the project it was in
  is removed; the importing server decides where it goes.
* **No credentials leave.** Export is refused, naming the JSON paths (never the values), when a key that looks like a credential
  (`token`, `secret`, `password`, `apiKey`, `authorization`, `cookie`, `bearer`, ...) holds anything but a `${secret:NAME}`
  reference, or a string holds a private key or a URL with a password. Import applies the same check to a bundle someone made by
  hand. A trigger's webhook secret and poll cursor are not exported.
* `requires` lists what the target must have before the workflows can run: secrets by name, settings by name, connected accounts
  (`oauth.*`, which are set up again by connecting the account) and `vault.*` secrets.
* **Integrity.** `integrity.sha256` is a digest of everything else in canonical JSON: it catches truncation and edits. It is not
  authenticity, since anyone can recompute it. With `wayang.workspace.signing-key-file` set, exports also carry an HMAC and this
  server **only** imports bundles carrying a valid one (unless the caller passes `allowUnsigned`): that is what tells "our other
  server" from "a file somebody made".

### Import

A **dry run is the default**: nothing is written and the report says what would happen. Options:

| option | default | |
|---|---|---|
| `dryRun` | `true` | set `false` to apply |
| `targetProject` | none | import into this project; otherwise a new one is created, named as in the bundle |
| `onConflict` | `fail` | `skip` or `overwrite` for definitions that already exist **in the target project**. A definition with the same id in another project (or none) is always a conflict: it is never moved |
| `publish` | `false` | publish what was imported, decisions and agents before the processes that use them |
| `triggers` | `true` | create the bundle's triggers (they are created **disabled**; a new webhook secret is returned once). An existing trigger is never overwritten: one with the same kind, process and configuration is reported as `unchanged` and is no conflict, so re-importing a bundle you exported from here works; one that differs is a conflict and the report names what differs (`kind`, `process`, `config`), never the values |
| `projectConfig` / `tenantConfig` | `true` / `false` | carry the project's settings / the tenant-wide ones |
| `allowUnsigned` | `false` | accept a bundle without a valid signature on a server that requires one |

With `overwrite`, a definition whose current draft (or latest published version when there is no draft) is already exactly the bundle's is reported as `unchanged` (status `skipped`): nothing is written, so no new revision appears, and a dry run shows which definitions would really change. With `publish` on it is still written and published, so the version history stays what you asked for.

Settings are reported by what is already there: `set` (new), `overwrite` (a different value is replaced; this has always been so, whatever `onConflict` says, and is now visible in the dry run) or `unchanged` (same value, not written). The report never shows setting values.

The report: `ok`, `items` (project, each definition, setting, trigger, with action and status), `problems`, `warnings`,
`missingSecrets` (checked against this tenant's secrets when the caller may list them; otherwise `secretsChecked` is false and it
lists every secret the bundle needs), `missingConfig`, `oauthConnections`, `webhookSecrets`.

Everything goes through the normal services, so every permission, size limit and id rule applies as if the calls were made one by
one: a project-only editor can import definitions into their project but not triggers or tenant settings; a definition invalid in
a way a draft cannot hide (structure, node configuration) blocks the import; a reference to a decision or agent that is in the
bundle is not an error, one that is neither in the bundle nor published here is a warning.

**Not transactional.** Applying writes in order (project, definitions, publish, settings, triggers) and stops at the first
failure; what was written before it stays, and the report lists it. Limits: 500 definitions, 100 triggers, 500 settings per
scope, 8 MB.

## Configuration

`GET /api/v1/config[?project=ID]`, `PUT /api/v1/config/{key}[?project=ID]` `{value}`, `DELETE /api/v1/config/{key}[?project=ID]`.

* Use as `${config:NAME}` in the configuration of service, agent and EIP nodes, next to `${secret:NAME}`.
* Two scopes: the tenant, or one project. A process of a project sees the project's value first, then the tenant's.
* Reading needs definition-read, writing definition-write, on the project or tenant-wide.
* Values are shown, not hidden, and are **not redacted from outputs**. The value is inserted as text and **not expanded again**
  (a value `${secret:X}` is not a reference), and inside `inputs` expressions a value with a quote or backslash is refused, as for
  secrets.
* Refused: names matching `token|secret|password|passwd|apikey|api_key|private|credential`, values containing `${`, control
  characters, more than 4096 characters. Deleting a project deletes its settings.
* Stored with `wayang.store` (memory or jdbc, table `wayang_config`, compare-and-set on version).

## Vault

### Where the master keys come from

Exactly one of:

```properties
wayang.secrets.key=<base64 of 32 bytes>               # simplest; the key is in the process config
wayang.secrets.key-file=/run/secrets/wayang-keyring   # a mounted file (see below)
wayang.secrets.vault-keyring-path=wayang/keyring      # a KV v2 secret in Vault (needs wayang.vault.*)
```

The keyring file (and the Vault secret, with one field per line below) is:

```
active=k2
k1:<base64 of 32 bytes>
k2:<base64 of 32 bytes>
```

The file is refused if other users can read it or its group can write it (`chmod 0400`), unless
`wayang.secrets.key-file-allow-loose-permissions=true` (some platform mounts cannot do better). Keys are read at startup, and again on
`POST /api/v1/secrets/keys/reload` (admin; file or Vault sources only, not `wayang.secrets.key`). Rotating: add the new key to the
file or Vault secret and set `active`, call reload, then `POST /api/v1/secrets/rotate` to re-seal. A reload that would **drop** a
key this server still has loaded is refused (it could lock out secrets sealed under it): removing a key needs a restart, after
rotate. A failed read leaves the current keys in place and never echoes the cause. In a cluster, call reload on every replica. `GET /api/v1/secrets/keys`
shows `activeKeyId`, how many secrets sit under each key and how many still `needRotation` (SECRET_MANAGE).

### Vault connection

```properties
wayang.vault.address=https://vault.example.com:8200     # https; http only for loopback or wayang.vault.allow-insecure
wayang.vault.namespace=team-a                           # optional
wayang.vault.token-file=/run/secrets/vault-token        # re-read on every use, so a renewing sidecar needs no restart
# or wayang.vault.token=..., or AppRole:
wayang.vault.approle.role-id=...
wayang.vault.approle.secret-id-file=/run/secrets/vault-secret-id
wayang.vault.mount=secret            # KV v2 mount
wayang.vault.base-path=wayang
wayang.vault.cache-seconds=30        # hits; misses are cached for at most 5 s
```

The client never follows redirects, caps responses at 256 KB, and puts no token, role secret or response body into an error.

### `${secret:vault.NAME}`

Read live from Vault: `<mount>/data/<base-path>/<tenant>/NAME`, field `value`. The tenant is part of the path and comes from the
run, so a tenant cannot name its way into another's secrets. Wayang never writes to Vault. Names must be plain (no `..`, no empty
segment); anything that goes wrong reads as "no such secret" to the run, which fails closed, and is logged without secret data.
Secrets cannot be stored locally under a `vault.` name.

### Who read which secret

`wayang.audit.secret-reads=true` adds one audit entry (`GET /api/v1/audit`, admin) each time a run's node resolves a
`${secret:NAME}`: actor `run:<process>`, method `RESOLVE`, route `secret`, ids `{secret, node, run}`, `OK`, or `FAILED` (status
404) when the secret did not exist. Never a value; `${config:...}` is not reported. Off by default because it writes one entry per
secret per node execution, so volume follows run volume. An audit store failure never fails a run (it is counted).

### Moving secrets between servers

`POST /api/v1/secrets/archive/export` `{passphrase}` and `POST /api/v1/secrets/archive/import` `{archive, passphrase, overwrite}`.

* **Off unless `wayang.secrets.archive-enabled=true`** (it is the one place plaintext leaves the store on purpose), admin only,
  audited (POST).
* The archive is PBKDF2-HMAC-SHA256 (600,000 rounds, random salt) into AES-256-GCM, header authenticated. Passphrase 12-256
  characters, not repetitive. Once the file leaves the server the passphrase is the only protection: share it out of band.
* Names and values are inside the ciphertext. Connected-account tokens (`oauth*`) are not included.
* Import is all or nothing (every name and size is checked before anything is written), lands in the caller's tenant, skips
  existing secrets unless `overwrite`, and re-seals under the importing server's own key.

## Studio

Sections **Configuration** (settings of the tenant or a project) and **Workspace** (export to a copyable bundle; import with
"Check (dry run)", then "Apply" only for exactly what was checked, with the report: problems, warnings, what is missing here, new
webhook secrets shown once). Both the export dialog and the import box show one summary line of the bundle: project, definitions by kind, triggers, the secrets and settings it needs, and `signed` or `unsigned` (read from the text, nothing is checked until the dry run). The **Secrets** section shows where key rotation stands (active key, how many secrets are still under an
older one), has a "Reload keyring" button (`POST /secrets/keys/reload`, admin), and checks names before saving (`vault.*`, `file.*`
and `oauth*` are refused, as the server does; so are the cloud prefixes).

## Verified vs not

* Unit tests: `ConfigServiceTest` (+ contract), `WorkspaceServiceTest` (round trip between two in-memory servers, integrity,
  signature, credential refusal, conflicts, permissions, partial failure), `VaultTest` (against a fake Vault HTTP server: KV read,
  AppRole lease, redirects, paths, caching, keyring parsing), `SecretArchiveTest`, `ConfigRuntimeTest`; mutation-checked (the
  survivors are equivalent: a constant-time compare, redundant guards). `JdbcConfigStore` passes `ConfigStoreContract` on
  PostgreSQL 16.
* **Not verified:** nothing was run against a real HashiCorp Vault (KV v2 and AppRole responses are as documented, not observed;
  Kubernetes/JWT/cloud auth methods are not implemented), nor Quarkus or gRPC (the REST resources compile against stubs only),
  nor the Gamelan adapter edit (`configResolver`), nor the Dart (parse-checked only). The vault cache and rate of Vault calls are per
  process. Reload is per replica. Run-time secret reads are audited only with `wayang.audit.secret-reads=true`, per process like the rest of the audit settings.

## Mounted-file secrets (`file.*`)

`${secret:file.NAME}` reads `<wayang.secrets.file-dir>/<tenant>/NAME`, for Docker/Kubernetes secrets and systemd credentials.
Off unless `wayang.secrets.file-dir` is set. The tenant comes from the run, not the name; names are plain (`[A-Za-z0-9_-]` segments,
no `..`); a symlink that resolves outside the directory is refused (Kubernetes' own `..data` links inside it are fine); only regular
UTF-8 files up to the secret size limit are read, with one trailing newline dropped. Failures read as "no such secret" and the log
carries neither path nor content. Not cached, so a rotated mount applies on the next run. Names starting `file.` or `vault.` cannot
be stored in the local vault, and bundles list them under `requires.externalSecrets`, never with a value.

Verified: 8 unit tests on real temp directories (isolation, traversal, symlinks, size/encoding, rotation, routing). Not verified: a
real Kubernetes mount, file-permission policy (none is enforced on these files), and the REST wiring (`OAuthConfig`, compiled
against stubs only).

## Cloud secrets (`aws.*`, `azure.*`, `gcp.*`)

Optional, each on its own, and alongside Wayang's own store, Vault and mounted files: a node config says
`${secret:aws.API_KEY}`, `${secret:azure.API_KEY}` or `${secret:gcp.API_KEY}` and the value is read from that service at run time.
Wayang only reads. The tenant is part of every cloud id and comes from the run, so a tenant cannot name its way into another's
secrets; give Wayang's cloud identity read-only access to the base path and nothing else. A prefix whose cloud is not configured
reads as "no such secret" (it is never looked up in the local store), and these names cannot be stored locally, exported as values,
or shown by the Studio. **Names starting `aws.`, `gcp.` or `azure.` are now reserved**: a local secret that already had such a name
is shadowed once that cloud is configured, and cannot be created again.

| Prefix | Secret id read | Auth | Limits on names |
|---|---|---|---|
| `aws.NAME` | Secrets Manager `<base-path>/<tenant>/NAME`, the `SecretString` | access key from config/files or `AWS_*` env, SigV4 | none beyond the usual |
| `azure.NAME` | Key Vault `[base--]tenant--NAME` | Azure AD app, client credentials | letters, digits, `-` only; no `--` |
| `gcp.NAME` | Secret Manager `[base--]tenant--NAME`, latest version | service-account key (RS256 JWT) | letters, digits, `_`, `-`; no `--` |

Azure and Google ids are one flat string, so parts are joined with `--` and no part may contain `--` or start/end with `-`: that
keeps two different (tenant, name) pairs from ever producing the same id. Names or tenants that do not fit read as missing.
Hits are cached 30 s, misses 5 s (`wayang.cloud.cache-seconds`), per process. Credentials are re-read from their files on each
token refresh, so a rotated file applies. Every failure reads as "no such secret" to the run; the log gets the HTTP status (and the
AWS error type), never a credential, URL, secret or response body. Endpoints must be https (loopback or `wayang.cloud.allow-insecure`
excepted), redirects are not followed, responses are capped at 256 KB. Properties are in `application.properties` and in the
`CloudSecretsConfig` javadoc.

Verified: unit tests against a fake HTTP server, including the AWS published SigV4 test vector (`get-vanilla`), the Google JWT
checked against its public key, token caching and refresh, tenant isolation, flat-id collisions, routing; 16 tests, mutation-checked
(survivors are equivalent guards). **Not verified: any real AWS, Azure or Google service** (request and response shapes are as
documented, not observed; IAM and RBAC setup is yours), nor Quarkus wiring (`CloudSecretsConfig` compiles against stubs only).
Not implemented: AWS web-identity (IRSA) and SSO credentials, binary secrets, secret versions/stages (always the current value).

**Platform credentials instead of configured keys** (so no secret file is needed on the host):

| Cloud | Property | Source |
|---|---|---|
| AWS | `wayang.cloud.aws.credentials=imds` | EC2 instance role via IMDSv2 (never v1); `wayang.cloud.aws.imds-endpoint` |
| AWS | `wayang.cloud.aws.credentials=container` | ECS/Fargate task role: `AWS_CONTAINER_CREDENTIALS_RELATIVE_URI` (or `_FULL_URI`) and the task's authorization token |
| Azure | `wayang.cloud.azure.auth=managed-identity` | VM/AKS IMDS, or App Service/Functions when `IDENTITY_ENDPOINT` and `IDENTITY_HEADER` are set; `client-id` picks a user-assigned identity |
| Google | `wayang.cloud.gcp.auth=metadata` | GCE/GKE/Cloud Run attached service account; `wayang.cloud.gcp.metadata-endpoint` |

These endpoints are plain http on a link-local address by design, so they are fixed by the operator's properties (never from a run
or request) and are the one place http is accepted without `allow-insecure`. Credentials are cached until five minutes before they
expire (tokens: a minute, at most an hour). Verified against fake endpoints only, 5 tests, mutation-checked; **not** against a real
EC2, ECS, Azure or Google host. Note IMDS hop limits and network policies on containers/pods can block these addresses.
