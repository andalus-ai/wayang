# Endpoints: Wayang as an integration bus (ESB)

Wayang already has the routing and transformation half of an ESB: the EIP pipelines of `docs/EIP.md` cover the Hohpe and Woolf catalog, and Gamelan gives durable execution. What an ESB also needs is **endpoints**: ways to receive from and send to the systems that exist. Rather than re-implement Apache Camel, Wayang adds endpoints as optional extensions and, later, an optional Camel bridge.

```mermaid
flowchart LR
  subgraph in[Consume: pollers and webhooks]
    fsin[fs: new files]
    jdbcin[jdbc: new rows]
    other[sftp, kafka, amqp: planned]
  end
  in -->|one run per event| wf[Workflow / EIP pipeline]
  wf --> out
  subgraph out[Produce: connectors]
    fsout[fs-write, fs-move]
    sqlout[sql]
    otherout[sftp, kafka, amqp: planned]
  end
  camel[camel connector: planned, optional] -.-> wf
```

## Plan

| Slice | What | Status |
|---|---|---|
| 1 | `wayang-endpoints`: file system connectors and the `fs` trigger source (JDK only) | implemented (this document) |
| 2 | `sql` connector and `jdbc` trigger source over JDBC (driver supplied by the deployment, parameterized queries only) | implemented (below) |
| 3 | SFTP/FTP, Kafka and AMQP/JMS as separate optional modules (each needs a client library) | **proposed** |
| 4 | Optional `camel` bridge: call a Camel route or endpoint URI from a step, for teams that already have Camel components (DSL routes, Kamelets) | **proposed**; heavy dependency, never in the core |
| 5 | Releases that serve poll sources from extensions (today a release can run `fs` connectors, but not an `fs` trigger) | **proposed** |

## File endpoints (`wayang-endpoints`)

Feature `ENDPOINTS` (executor type `wayang-endpoints`; in the `integration` and `full` profiles, **not** in `agentic`, so an assistant never gets the file system unless you add it: `WAYANG_PROFILE=agentic,endpoints`). Everything is **off until the operator sets `WAYANG_ENDPOINTS_FS_ROOT`** to a directory.

| Step | Does | Inputs |
|---|---|---|
| `fs-read` | read a file as text (strict UTF-8) or `encoding: base64`; `maxBytes` (default 8 MiB, max 32 MiB) | `path` |
| `fs-write` | atomic write (temp file then move); refuses to replace unless `overwrite: true` | `path`, `content` |
| `fs-list` | files of a directory, `glob` (a file name pattern such as `*.csv`), at most 1000 | `path`, `glob` |
| `fs-move` | move or rename (for example into an archive folder); refuses to replace unless `overwrite` | `from`, `to` |
| `fs-delete` | delete one file, never a directory; already gone is not an error | `path` |

Confinement: each tenant has its own subdirectory under the root (`u-<tenant>`, or `h-<hash>` when the tenant id is not a safe name), so tenants cannot see each other's files. Paths are relative; absolute paths, drive letters, backslashes, NUL, `..` and symbolic links that lead outside the tenant directory are refused. A missing input never fails a run: the step returns `found:false` / `written:false` with a `reason`.

### The `fs` trigger source

A run per new file. Trigger config: `source: fs`, `path` (directory), `glob`, `settleSeconds` (default 2: a file changed more recently is left for the next poll, so a half-written file is never handed over; also write with `fs-write`, which is atomic), `includeExisting` (default false: the first poll ignores the backlog). Events are `{path, name, size, modifiedAt}`; the workflow reads the file with `fs-read` and typically moves it with `fs-move`. The cursor is the last file's (modified time, name): each file is handed over once, in order, at-least-once like every poller; at most 100 events per poll. The trigger service gives pollers the trigger's own tenant, so a trigger can only watch its tenant's directory.

A typical flow: `fs` trigger -> `fs-read` -> EIP pipeline (`parse` csv, `splitter`, `map`, `call`) -> `fs-move` to `archive/`. Failures go to a dead-letter folder with another `fs-move` on the error path (`onError: continue`).

## SQL endpoints (`sql`, `jdbc`)

Same feature (`ENDPOINTS`). **Off until the operator sets `WAYANG_ENDPOINTS_JDBC_URLS`** to a comma-separated list of permitted `jdbc:` URL prefixes, for example `jdbc:postgresql://db.internal:5432/`. Wayang adds no database library: the deployment puts the JDBC driver on the class path (for a generated release, add the driver dependency to the generated `pom.xml`).

`sql` node config: `url`, `user`, `password` (`${secret:NAME}`), `query`, `mode` (`read` default, or `write`), `maxRows` (default 1000, max 10000), `queryTimeoutSeconds` (default 30, max 300). Input: `params`, the values for the `?` placeholders. Output: `rows` (flat maps, lower-case column names; timestamps as ISO text, binary as base64, long text cut at 64 KiB), `count`, `truncated`; for a write: `updated`.

Why it is safe to expose to workflows and agents:
- The SQL is **node configuration written by the author**. A caller, or a model when `sql` is an agent tool, passes only values, always bound as parameters, never concatenated. A query with a different number of `?` than parameters returns a `reason` instead of running.
- Read-only by default: a single statement that starts with `SELECT` or `WITH`, on a read-only connection. Changing data needs `mode: write` on the node (an author decision; do not expose a write node as an agent tool you do not trust).
- Credentials never go in the URL (a `password=` in it is refused); the URL must match the operator's list.
- Errors show the SQLState (retryable for connection and transaction classes), never the driver's message, which can carry hosts, users or values.
- A `?` inside a string literal in the query counts as a placeholder; use `CHR`/concatenation or a parameter for such text.

`jdbc` trigger source (a run per new row): config `url`, `user`, `password`, `query` with exactly one `?` (the cursor), `cursorColumn` (a result column that only grows), `initialCursor` (required: where to start, so a trigger never replays a whole table), `maxRows` (1-100). Example: `SELECT id, total FROM orders WHERE id > ? ORDER BY id`, `cursorColumn: id`, `initialCursor: 0`. The cursor is bound as a number when it looks like one, otherwise as text; it is always read-only. Delivery is at-least-once, in query order. A trigger sees only what its query selects: give the database user only the rights it needs.

### Limits and verification

- One host directory: a release scales to one replica for file consumption (two replicas would both see the same file); use Gamelan with a single trigger runner, or an atomic claim by `fs-move` into a per-instance folder.
- Network file systems and clock skew can break "settled" detection; raise `settleSeconds`.
- A design-only server (`WAYANG_MODE=design`) refuses triggers like any other.
- Unit-tested (temp directories, symlink, traversal, tenant isolation, atomic write, poller order, settle, cursor). The SQL steps are tested against a fake JDBC driver (statements, binding, read-only enforcement, bounds, hidden errors, cursor): **never run against a real database or driver**. Not run on a network file system or with real inbound traffic.
