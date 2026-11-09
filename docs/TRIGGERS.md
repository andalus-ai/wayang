# Triggers and chat channels (Telegram, Slack, WhatsApp)

A **trigger** starts a process because something happened outside Wayang. Two kinds:

| Kind | How it starts runs |
|---|---|
| `WEBHOOK` | `POST /hooks/{tenant}/{id}` with header `X-Wayang-Secret`. The secret is returned **once** when the trigger is created and stored only as a SHA-256 hash. The body (JSON object, max 64 KB) becomes `event` in the run input |
| `POLL` | Every `everySeconds` (5..86400, default 30) the scheduler asks a poller for new events and starts one run per event |

Run input is `{ "event": {...}, "trigger": "<id>" }`.

## Telegram

```json
POST /api/v1/triggers
{ "id": "tg-inbox", "kind": "POLL", "processId": "assistant",
  "config": { "source": "telegram", "botToken": "${secret:TELEGRAM_TOKEN}",
              "allowedChatIds": [123456789], "everySeconds": 10 } }
```

* `botToken` must be a secret reference (set it under Secrets). `allowedChatIds` is **required**: anyone can message a
  bot, so messages from other chats are dropped (and acknowledged, so they are not re-read) and never start a run.
  In a **group** every member can write: add `allowedUserIds` (Telegram user ids; Slack: `allowedUsers` on the webhook) to accept only those senders.
* Event: `updateId, chatId, chatType, fromId, fromUsername, messageId, text, date`. Only text messages.
* Reply with a service task: connector `telegram`, config `{ "botToken": "${secret:TELEGRAM_TOKEN}" }`, inputs `chatId`,
  `text` (max 4096), optional `replyTo`.
* Outbound traffic goes through the same SSRF guard as the `http` connector.

## Slack and WhatsApp (webhooks with provider signatures)

Provider webhooks are `WEBHOOK` triggers with `config.auth` naming the provider. They are authenticated by the provider's
signature over the **raw body**, not by a Wayang secret. Point the provider at `https://<host>/hooks/<tenant>/<trigger id>`.
Every credential in a config must be a pure `${secret:NAME}` reference (creation is refused otherwise).

```json
{ "id": "slack-dm", "kind": "WEBHOOK", "processId": "assistant",
  "config": { "auth": "slack", "signingSecret": "${secret:SLACK_SIGNING}", "allowedChannels": ["D0123ABCD"] } }

{ "id": "wa", "kind": "WEBHOOK", "processId": "assistant",
  "config": { "auth": "whatsapp", "appSecret": "${secret:WA_APP}", "verifyToken": "${secret:WA_VERIFY}",
              "allowedNumbers": ["628123456789"] } }
```

* **Slack**: HMAC-SHA256 `v0:<ts>:<body>`, requests outside +/-5 minutes refused (replay), `url_verification` answered after
  the signature checks out. Bot messages and subtypes (edits, joins) are dropped so the assistant cannot answer itself.
  `allowedChannels` required (DM channel ids start with `D`). Reply with connector `slack` (`botToken`, inputs `channel`,
  `text`, optional `threadTs`).
* **WhatsApp**: official **Cloud API** only (unofficial WhatsApp Web bridges break the terms of service and get numbers
  banned). `X-Hub-Signature-256` over the body, GET `hub.challenge` handshake with `verifyToken`, `allowedNumbers` required
  (digits, country code, no `+`). Text messages only. Reply with connector `whatsapp` (`accessToken`, `phoneNumberId`,
  inputs `to`, `text`). Meta only allows free-form text within 24 h of the user's last message; approved templates are
  not supported.
* **Retries**: providers re-send when slow; events are deduplicated by event/message id (in memory, last 5000 per trigger,
  lost on restart, so a retry right after a restart can run twice).
* A missing secret makes the webhook answer 503, never open.

## The assistant pattern

`wayang-examples/PersonalAssistant`: message -> agent drafts -> **decision table** -> auto-send or **human approval**.
The policy reads the incoming text and the draft, not what the agent says about itself, so injected instructions cannot
skip approval (money words in the message or a link in the draft always need a person).

## Scheduled triggers (cron)

`kind: SCHEDULE` starts a process by the clock. Config: `cron` (five fields `minute hour day month weekday`, with `*`, lists, ranges, steps, `JAN-DEC`, `SUN-SAT`,
or `@hourly @daily @midnight @weekly @monthly @yearly`), `timezone` (IANA id, default UTC), `graceSeconds` (0 to 86400, default 3600) and `input`
(an object up to 8 KB merged into the event; secret-looking keys must be `${secret:NAME}` references, also nested). The run's input is `{event, trigger}` with
`event = input + scheduledFor, firedAt, timezone`.

* **Once per due time.** A schedule runs when its next time (counted from its last run, or from creation / when it was switched on) has come. It is not run for what came
  due before it existed or while it was switched off.
* **Missed runs are skipped, not run late.** If the server or release was down and the time is more than `graceSeconds` past, the run is skipped, the reason is in `lastError`
  and the next one runs on time. A start that fails (process missing, tenant suspended is just not ticked) is retried each tick until the grace ends.
* **DST:** a time inside a spring-forward gap runs later by the gap (02:30 becomes 03:30); in a repeated hour it runs once, at the first.
* day-of-month and day-of-week both restricted means either matches (classic cron).
* **Release:** carried like other triggers; the last run is kept in `WAYANG_STATE_DIR` (`cursor-<id>`) so a restart does not repeat or lose it. `GET /v1/triggers`
  shows `lastRunAt`, `nextRunAt`, failures, last error. In-memory runs are per process: with several replicas each would run the schedule, so run one.
* Needs no secret, connector or provider.

Templates `daily-brief-telegram` and `daily-brief-slack`: params `chatId` / `channelId`, `topic` (required), `cron` (default `0 8 * * 1-5`), `timezone`, `model`, `persona`,
and the optional lookup fields. An agent `writer` (no tools unless a lookup is given, cannot act) writes the brief and the process sends it to the one chat or channel you named;
there is no approval step because nothing but its own text can go out, only to you. Created switched off.

Templates `webhook-alert-telegram` and `webhook-alert-slack` need **no agent** (they are the integration profile's starters): a secret webhook whose JSON body field `message` is
sent as `Alert: <message>` (at most 1000 characters) to the one chat or channel you named. The destination is a literal in the process, never read from the body, so a caller
holding the secret can choose the text but not the place. The webhook secret is shown once in the creation report; the trigger starts disabled with `rateLimit` 60 per hour and 500 per day.
One of each kind per tenant (fixed ids). Not verified: a real delivery to Telegram or Slack.

Verified: cron semantics and DST by unit tests with mutation checks; server and release schedule logic against a fake clock; templates through the real importer; trigger store contract
(in-memory and on PostgreSQL 16). Not verified: a real run at a real wall-clock time, a real Telegram/Slack send, Studio compile (parse-checked only), Quarkus scheduler wiring.

## Approvals in the chat itself

A Telegram or Slack trigger with `approvals: true` (the two assistant templates set it) treats a plain **yes** or **no** from the trigger's allowed chat as the answer to the
**oldest** approval (a waiting user task) that the same trigger's runs hold for that same conversation, instead of as a new message. The task is completed with
`approved: true|false`, so a process can branch on `<task>.approved`. Rules:

* Only short exact answers count: `yes y ok okay approve approved send` / `no n reject rejected cancel drop stop`, case-insensitive, trailing `.`/`!` ignored. "yes please send it now" is an ordinary message.
* Only runs of this trigger, in this chat (Telegram `chatId`, Slack `channel`), are matched; a different chat's "yes" starts a normal run. With nothing waiting, "yes" is an ordinary message.
* Several waiting approvals in one chat are answered oldest first, one answer each. Anyone who may talk in the allowed chat can answer (a group chat is as trusted as the allow-list says).
* Works on the server and in a release (POLL for Telegram, WEBHOOK with the Slack signature). Other sources refuse `approvals: true`; so do schedules and plain webhooks.
* The templates' process now sends the draft to the chat ("Draft reply held for your approval. Answer yes to send it or no to drop it") before waiting, so the person sees exactly what would go out.
  The same task is still listed in the console (`/v1/tasks`) and can be completed there; whichever answers first wins.

Verified by tests with fakes: the server and the release paths, the Slack signed webhook, and the template process end to end with a fake Telegram connector. Not verified: a real Telegram or Slack round trip.

## Agent tools that need approval (calendar)

An agent tool listed in `requireApproval` is **recorded, not run**, in a release: the agent's result gets `pendingCalls` (tool, connector type, the tool's configuration still holding
`${secret:..}` / `${config:..}` references, and the model's arguments) and `pendingSummary` (one line each). The model is told it is waiting for the person's yes. A process decides what happens next;
the assistant templates ask in the chat (the policy table holds any draft with `pendingCalls`; the question lists the actions) and after a yes the step `agent-actions` (connector, built in to
every release target) runs them, looking the references up only then, so no secret value ever sits in a run's variables. A no drops everything. At most 5 actions per agent run and 10 per step,
never to a connector the release lacks. A failed action is reported per call, without secret values, and does not stop the others. Missing or empty `calls` is a no-op.

Both assistant templates take `calendar: true` (+ `calendarConnection`, default `google`, an OAuth connection, see `docs/EMAIL.md`): tools `calendar_list` (free) and `calendar_create` (approval).
The model proposes events with an RFC 3339 time; attendees are never notified. Before a yes nothing is created.
At design time (a server run of the same process) no agent runtime records pending calls, so the step does nothing there; the full flow runs in a release.

Verified by tests with a scripted model and fake Telegram and calendar connectors: release plan and secrets, nothing created before the yes, created with the resolved token after it, nothing after a no,
no secret value in any run variable, and bugs injected into the policy, the flow and the approval list are caught. Not verified: a real model choosing the tool well, a real Google Calendar.

## Guardrails: spend, rate and untrusted output

* **Token accounting.** The built-in model loop adds `usage {inputTokens, outputTokens, totalTokens}` to an agent's result, as the provider reports it (an estimate of one token per four characters when it reports nothing).
* **Per run.** Agent options `maxTotalTokens` (default none; the assistant templates set 30000) stops the loop before another model call once reached, and `maxToolCalls` (default 25, templates 10, at most 100) fails the run on the call over it, before that tool is run.
* **Per day.** `WAYANG_LLM_DAILY_TOKENS` caps the model tokens a release spends per UTC day; once used up every model call fails with a clear message until 00:00 UTC. A call already in flight can overshoot once. In memory: a restart starts the day at zero, and replicas count separately.
* **Per chat.** A trigger setting `rateLimit: {"perHour": N, "perDay": M}` (either or both, each 1 to 10000; the templates use 60 and 300, so one chat is bounded in what it can cost per day even when its hourly rate is allowed) drops messages over the limit, counted per chat (Telegram `chatId`, Slack `channel`) or per trigger for others. Dropped means no run and no reply. A yes or no that answers an approval is never counted or dropped. Not for schedules or plain webhooks. The counts live in memory of one process: a restart forgets them and each replica counts on its own, so treat the day limit as a safety net, not as billing.
* **Untrusted output.** What a tool returns, and a tool's error text, reaches the model inside `<tool_result>` tags (a closing tag inside it is neutralised), and the system prompt of an agent with tools says such text is data, never instructions. This reduces prompt injection; it does not make a model immune, which is why writes need approval.

Verified by tests with a scripted model and fake clock, and by injected bugs in each check. Not verified: real provider usage figures or real injection attempts against a real model; the per-chat limit is tested on the poll path (server and release), the Slack webhook path shares the same code but has no test of its own.

## Guarantees and limits

* **At-least-once.** The cursor advances only after every event of the batch started a run. A failed start (for
  example the process is not published) keeps the cursor and retries with exponential backoff (capped at 1 h); nothing is
  lost, but a crash mid-batch re-delivers the events already started. Make processes tolerate a repeat.
* **Least privilege.** Runs start as a principal holding only `RUN_START`. Creating a trigger needs both
  `DEFINITION_WRITE` and `RUN_START`.
* **Errors never contain secrets**: resolved secret values are redacted from `lastError`.
* **Single node.** Two nodes ticking would poll twice; there is no lease yet.
* **Webhooks have no rate limit** (put one in front) and the secret cannot be rotated except by recreating the trigger.
* The Studio has no trigger screen yet (REST only).

## Verification

Executed (JUnit shim): `TriggerService` and the Telegram connector/poller against a **fake Bot API server** (allow-list,
dropped strangers, cursor and `offset`, token never in run data or errors, bad token/oversized text/missing chat refused,
strict SSRF guard blocks a loopback base URL, bad bot answer keeps the cursor); webhook secret handling (wrong secret,
unknown trigger and other tenant give the same error). `JdbcTriggerStore` passes `TriggerStoreContract` on PostgreSQL 16.
Mutation-checked: dropping the allow-list and advancing the cursor on failure each fail tests.
**Slack/WhatsApp**: signature checks tested (Slack against its documented example vector, reproduced independently with openssl; WhatsApp against HMAC computed in the test), handshakes, allow-lists, bot filtering, retry dedupe, senders against local fake servers, missing-secret and literal-credential refusals; removing the signature check, the dedupe or the credential rule each fail tests. The assistant example's tests fail if the approval rules are removed.
**Not verified:** the real Telegram, Slack and WhatsApp APIs (payload shapes written from their documentation), REST/scheduler wiring (compiled
against stubs only), multi-node behaviour.


## Other sources

`source: imap` (any IMAP mailbox) is described in `MAIL.md`, together with the `smtp` and `discord` connectors.

## GitHub webhook and RSS

```json
{ "id": "gh-issues", "kind": "WEBHOOK", "processId": "triage",
  "config": { "auth": "github", "secret": "${secret:GH_HOOK}", "allowedRepos": ["me/*"], "events": ["issues", "issue_comment"] } }
{ "id": "news", "kind": "POLL", "processId": "digest",
  "config": { "source": "rss", "url": "https://example.org/feed.xml", "limit": 20, "everySeconds": 900 } }
```
GitHub: set the same secret on the webhook (content type `application/json`). Events are `issues`, `issue_comment`, `pull_request`, `push`;
run input has `type, action, repo, sender, number, title, text, url`. RSS: input is `id, title, link, summary, published, feed`.
Both were tested against fakes only, never a real GitHub delivery or a live feed.

### Start from the template

`POST /api/v1/templates/assistant-telegram/instantiate` with `{"params": {"chatId": "123456", "model": "openai/gpt-4o-mini", "persona": "..."}, "dryRun": false}`
creates a project with the pieces above: the policy decision, a `replier` agent, the `personal-assistant` process (all published) and
a Telegram poll trigger `assistant-inbox` restricted to your `chatId`. The trigger is **created disabled**: set the secret
`TELEGRAM_TOKEN`, then enable it. The report lists what is still missing. The result releases as is (`docs/RELEASE.md`); the model's
key becomes the release secret `llm.<provider>.api-key`. Never run against a live Telegram or model here: only the import and release steps are tested.

`assistant-slack` is the same assistant on Slack: params `channelId` (a `C…`, `D…` or `G…` id), `model`, `persona`, `name`. It creates a
WEBHOOK trigger with `auth: slack` restricted to that channel (`allowedChannels`), replying through the `slack` connector. Set the secrets
`SLACK_BOT_TOKEN` and `SLACK_SIGNING_SECRET`, enable the trigger, and point the Slack app's Events API request URL at the webhook.
Bot messages are ignored by the connector, so the assistant does not answer itself. Both templates accept an optional `lookupUrl` (https, no credentials; plus `lookupDescription`): the agent gets one read-only `lookup` tool that GETs that fixed address with query parameters it chooses, never the host. `memoryTurns` (0 to 30, default 10) is how many earlier exchanges of the same chat the agent is told about; 0 turns memory off. `lookupSecret` (a secret name) sends that secret as a bearer token; only the name is stored, the value stays a `${secret:}` reference and becomes a release secret. Tool output is told to be treated as data, not instructions. **Forgetting:** a message that is exactly `/forget` or `forget me` makes the assistant clear that chat's memory (an agent step with `forget: true`, no model call) and say so in the chat; with memory off it says it keeps none. Memory is private to tenant + process + agent, so a forget step only clears the agent of its own process. Tested against fakes only; the Studio card has a Telegram/Slack switch and the lookup fields (Dart parse-checked, never compiled).
