# Protocol connectors

Service-task connectors for talking to other systems by protocol. All go through the same URL guard as `http` (no private addresses unless
`WAYANG_CONNECTORS_ALLOW_PRIVATE=true`, no redirects, no credentials in URLs) and take secrets only as `${secret:NAME}` references.
`http` (REST), `soap` (WSDL) and `mcp` (client, Streamable HTTP) already exist; see `docs/MAIL.md` and `docs/MCP.md`.

| connector | what | key config | inputs |
|---|---|---|---|
| `graphql` | one query / mutation over POST | `url`, `headers`, **`query`** (fixed), `operationName`, `failOnErrors` (true), `outputMap` | the GraphQL **variables** |
| `a2a` | Agent2Agent (JSON-RPC over HTTP) | `url`, `headers`, `operation` = `send` / `getTask` / `cancelTask` / `agentCard`, `blocking`, `failOnTaskFailure` | `text`, `contextId`, `taskId` |
| `grpc` | unary RPC with JSON messages (**Connect protocol**) | `url`, **`service`**, **`method`**, `headers`, `outputMap` | the request message |
| `websocket` | connect, send one message, collect answers, close | `url` (ws/wss), `headers`, `subprotocol`, `collect` (1), `waitMs`, `maxBytes`, `outputMap` | `message` |

## graphql
The document lives in the definition, values travel as variables, so inputs can never change the query. `allowDynamicQuery: true` lets the
input `query` / `variables` replace it (only for a node that is meant to let a model write queries). A non-empty `errors` list fails the node
(non-retryable) unless `failOnErrors: false`. Subscriptions are refused. Output: `data`, `errors`, plus `outputMap` variables (paths into the output, e.g. `data.user.name`).

## a2a
`send` posts `message/send` with one text part and returns `text` (artifacts first, else the status message, at most 20,000 characters),
`state`, `taskId`, `contextId` and the raw `result`. Pass `contextId` / `taskId` back to continue a conversation. A task that ends
`failed`, `rejected` or `canceled` fails the node. `agentCard` fetches `/.well-known/agent-card.json` (then `agent.json`) of the endpoint's origin, or `cardUrl`.
What another agent says is untrusted input. Not supported: streaming (`message/stream`), push notifications, non-text parts.

## grpc
**Not native gRPC.** Native gRPC is protobuf over HTTP/2 with status in trailers and needs the service's compiled descriptors. This connector speaks
the **Connect protocol with JSON** (`POST {url}/{service}/{method}`, `Connect-Protocol-Version: 1`), which connect-go / connect-es / connect-java servers,
Envoy's gRPC-JSON transcoder and gRPC gateways accept. Unary calls only. Errors `{code, message}` fail the node; `unavailable`, `deadline_exceeded`,
`resource_exhausted` and `aborted` are retryable.

## websocket
A request/answer exchange, not a subscription (use a poller for streams): after connecting it sends `message` (a string, or an object/array as JSON),
waits for `collect` text messages (0 = send only) up to `waitMs`, then closes. Output `messages`, `text` (last), `json` (last, when it is JSON), `timedOut`.
No message at all before the timeout is a retryable failure; a too large answer is not. Binary frames are ignored. The connection error never echoes the URL.
Note: the URL guard resolves the host for the check and the JDK resolves it again to connect; both go through the JVM address cache, which pins the answer unless the cache is switched off (see `docs/SECURITY.md`).

## Studio / API
The connector test button can run `a2a` `agentCard` (reads only). Other operations send data and are refused there, like the other connectors.

## Verified / not
Unit tests against local fake servers (including a hand-written RFC 6455 server for websocket). Not run against a real GraphQL API, A2A agent, Connect server or
production WebSocket service. Studio has no dedicated forms for these yet (they are configured as generic service-task settings).
