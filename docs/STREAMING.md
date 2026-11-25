# Streaming agent replies: design (not implemented)

Status: **proposal**. Nothing here is built. It records what was verified in the code, what is missing, and the smallest path, so the change in Andalus can be agreed before any code is written there.

## Today

- `POST /v1/agents/{id}/chat` returns one JSON body after the run finishes (`wayang-embed`, `HttpService`).
- Wayang calls Andalus through `AgentInvoker.invoke(spec, input) -> Map` (`wayang-adapter-andalus`, `AndalusAgentInvoker`), which calls `ExecutionService.startAgent(...)` (andalus-framework `andalus-engine`) and reads the final variables of the returned `GraphExecutionContext`. `ExecutionService` has only blocking and `CompletableFuture` forms; no partial output is exposed.
- Andalus's backend SPI already has a streaming call: `InferenceBackend.stream(InferenceRequest) -> Multi<InferenceTypes.StreamingChunk>` (andalus-spi). Within `andalus-framework`'s engine module nothing calls it; the model call that `startAgent` ends up making is not in the modules inspected, so where a chunk could be tapped is **not yet confirmed**.
- Andalus has an observation channel: `AgentExecutionObserver.onEvent(ObservationEvent{executionId, traceId, type, attributes})` (andalus-observability). It is the natural carrier for deltas without changing `ExecutionService`'s signature.

## Constraints

1. Streaming the final text only after the run is complete is not streaming; it would be a fake. Deltas must come from the model call.
2. An agent step can call tools and loop. Only the text of model turns is streamed; tool calls and results are reported as events, not as text, and a tool loop may produce several text segments.
3. The durable record stays the final result. A stream is a view of a live run: a dropped connection must not cancel the run, and a reconnect gets the final result (and, at most, the deltas since a `Last-Event-ID`), never a replay promise.
4. Same security rules as chat: tenant/identity from the request principal, `chatId` pattern, 16,000-character message bound, per-run delta and total-size bounds, no secrets in events.

## Proposed path (two PRs, Andalus first)

**A. Andalus (branch + PR on `andalus-ai/andalus-framework` / `andalus`, additive):**
- Where the agent turn calls the backend, use `stream(...)` when an observer asks for deltas, otherwise keep today's blocking call (default fallback keeps every existing caller unchanged).
- For each text chunk publish `ObservationEvent(type = "llm.delta", attributes = {text, index})`; publish `llm.tool-call` / `llm.tool-result` (names and sizes only) and a terminal `run.completed` / `run.failed`. Chunk text is bounded per event.
- Test with a fake `InferenceBackend` whose `stream` emits known chunks: events arrive in order, the final result equals the concatenated text, a failing stream emits `run.failed`.

**B. Wayang (after A is merged and released):**
- `AgentInvoker` gets `default invokeStreaming(spec, input, Consumer<String> onText)` that falls back to `invoke` and delivers the whole reply as one chunk (so non-streaming invokers keep working and the HTTP layer needs no special case).
- The Andalus adapter implements it by registering an observer for the run's `executionId` and forwarding `llm.delta` text.
- `POST /v1/agents/{id}/chat` with `Accept: text/event-stream` answers SSE: `event: delta` (`{text}`), `event: tool` (name only), a final `event: done` carrying the same JSON as the non-streaming reply, `event: error` (generic message). Bounded total size and an idle timeout; the non-SSE behaviour is unchanged.
- Tests: SSE framing, ordering, bounds, error path, fallback invoker produces exactly one delta plus `done`.

## Open questions (need an Andalus owner)

- Which class makes the model call for `startAgent` (the Gollek backend adapter, per the module names), and does it already pass through `InferenceBackend`?
- Is `ObservationEvent` delivered synchronously on the model thread (fine) or batched (would add latency)?
- Can an observer be scoped to one execution id, or must Wayang filter a global stream?
