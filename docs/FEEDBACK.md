# Feedback

People can tell the operator what works and what does not, from Studio or the API.

## API

* `POST /api/v1/feedback`, any signed-in user:
  `{kind, rating?, message?, context?: {name: text}}`, answered with `{id}`.
  * `kind`: `rating` (needs `rating: "up"|"down"`, no message needed), `comment`, `bug`, `idea`, `error` (these need a `message`).
  * `context`: at most 12 short named details (`runId`, `screen`, `platform`, `stack`); names `[A-Za-z][A-Za-z0-9_.-]{0,39}`, values
    up to 500 characters (`stack` up to 8000), the message up to 4000. Longer is refused, not cut.
  * 10 submissions a minute per user (in memory, per process; 429 over REST).
* `GET /api/v1/feedback?kind=&limit=&before=`, tenant-wide ADMIN, newest first, own tenant only.
* `PATCH /api/v1/feedback/{id}` with `{status: "open" | "done"}`, ADMIN, own tenant: mark an entry as dealt with (or reopen it); entries
  start `open` and every listed entry carries its `status`. JDBC keeps the entry itself untouched and records `done` in `wayang_feedback_status`.
* `GET /api/v1/feedback/summary`, ADMIN: `{open, byKind, up, down, counted, sampled}` over the newest 500 entries of the tenant (`byKind` counts only open ones; `sampled` means older entries exist and are not counted)
* `GET /api/v1/feedback/export?kind=&limit=`, ADMIN: the newest entries (up to 500) as `feedback.csv`
  (`time,user,kind,rating,status,message,context`). Every cell is quoted and one that starts with `= + - @`, tab or return gets a leading
  apostrophe, so a bug report that says `=HYPERLINK(...)` is text, not a formula, when opened in a spreadsheet.
* `DELETE /api/v1/feedback?before=<ISO-8601>`, tenant-wide ADMIN: deletes this tenant's entries older than `before` and answers
  `{deleted}`. `before` is required and must be at least a day ago, so a call cannot wipe today's feedback. Nothing runs it for you.
* Storage follows `wayang.store`: `jdbc` uses `wayang_feedback` (INSERT/SELECT, plus that age-based DELETE; rows are never edited); `memory` keeps
  `wayang.feedback.memory-entries-per-tenant` (default 2000) and loses them on restart. There is no automatic retention job: run the DELETE above on whatever schedule you want.

## Credentials are masked before storage

Error reports and pasted text are where secrets leak, so every text (message and context) is scrubbed on the server first:
`Authorization`/`Bearer` values, `token=`/`password:`/`api_key=`-style pairs, URL passwords and query strings, JWTs, well-known token
shapes (OpenAI-style `sk-`, Slack, GitHub, Telegram bot tokens) and any 40+ character run of token characters. This is a safety net, not
a guarantee: it errs on the side of masking, and the form tells people not to paste secrets.

## Studio

* The top bar has a feedback button. The form shows **exactly what will be sent** ("What will be sent"); nothing leaves until Send.
* When the app catches an error itself (`FlutterError.onError`, `PlatformDispatcher.onError`) it is only remembered; the button turns into
  a warning and opens an error report pre-filled with the message and, if the person leaves it on, the stack trace. It is never sent
  automatically, and a successful send clears it.
* The **Feedback** section lists what was sent (newest first, filter by kind; expand a row for its details and stack). Reading needs the
  ADMIN role, so anyone else sees the server's refusal there but can still use Send feedback.
* In the Feedback section each entry has **Mark as done / Reopen**.
* A line under the title shows how many entries are open and the thumbs up/down (from `/feedback/summary`; a `+` means the server counted only its newest 500), and the kind chips show their open counts.
* A failed run has "Report this error": it sends the run's error text and its run id, process id and version, never its variables or outputs.

Server-side failures are already visible in the audit log (`outcome: FAILED`) and the run's own error; this is for the person's
view of what went wrong and why it mattered.

## Thumbs under assistant replies (Telegram and Slack)

Template parameter `ratings` (off by default) on the Telegram and Slack assistants:

* the reply is sent with 👍 and 👎 buttons (`sendMessage` input `rate: true`, on the one step that sends the reply, not on the
  confirmations or approval questions);
* the trigger gets `ratings: true`, so the poller also asks Telegram for button presses. A press from an allowed chat (and allowed user,
  when `allowedUserIds` is set) on one of our two buttons is acknowledged ("Thanks for the feedback") and becomes a feedback event; any other
  button, chat or person is ignored;
* the trigger records it as `kind: rating` with `context`: `source`, `trigger`, `processId`, `chatId`, `messageId` and the **first 200
  characters of the rated reply**, under the user id `telegram-<id>`. The press never starts a run. The excerpt is the one place reply
  content reaches the feedback log (admin-readable, scrubbed like everything else), so leave `ratings` off if replies are too private for that;
* a press is dropped silently if the feedback store rate limit (10 a minute per user) is hit, and never fails the poll.

`ratings` is for Telegram poll triggers and Slack webhook triggers only (the API refuses it elsewhere). **Slack** works the same way, with Block Kit buttons under the reply (a long reply is split into 3000-character sections; the full text stays
as the notification text). Slack sends button presses to the app's **Interactivity Request URL**, which must be set (Interactivity & Shortcuts
in the Slack app settings) to the same webhook URL as the Events subscription: Wayang verifies the signature, reads the form-encoded
`payload`, and handles a press only from an allowed channel and, if `allowedUsers` is set, an allowed person, on our two buttons. A retry of
the same press (same `action_ts`) counts once. Recorded users are `slack-<id>`; context has `channel` and `messageTs` instead of `chatId` and `messageId`.

A **generated release** drops these presses (it has no feedback
store, and a press is not a message for the process), so buttons there would only be acknowledged, not recorded.

## Not done

* Thumbs under assistant replies in Slack (Telegram has them, below), and in generated releases (no feedback store there).
* Automatic retention, and a download button in Studio (the CSV is an API call today).

## Verification

`FeedbackTest` (10 tests: access rules, tenant isolation, validation and size limits, credential masking, rate limit, paging, bounded memory
store) was run here with a small stand-in JUnit runner and passes, as were `TelegramRatingsTest` (6), the new trigger/template/release tests and the existing `TriggerServiceTest` (43), `TemplateServiceTest` (29), the Telegram tests and `TriggerTest` (16); `JdbcFeedbackStore`, the REST classes and the Studio code were only
compiled or read, never run (no database, Quarkus or Flutter SDK here). `feedback_model_test.dart` is pure Dart and unrun. CI is the real check.
