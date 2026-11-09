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
* Storage follows `wayang.store`: `jdbc` uses `wayang_feedback` (INSERT/SELECT only, like the audit log); `memory` keeps
  `wayang.feedback.memory-entries-per-tenant` (default 2000) and loses them on restart. No retention job, and no update or delete.

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
* A failed run has "Report this error": it sends the run's error text and its run id, process id and version, never its variables or outputs.

Server-side failures are already visible in the audit log (`outcome: FAILED`) and the run's own error; this is for the person's
view of what went wrong and why it mattered.

## Not done

* Thumbs up/down under assistant replies in Telegram and Slack (needs inline buttons and callback handling in the pollers).
* A Studio screen for admins to read the feedback (the API lists it today).
* Retention, status (open/done) and export.

## Verification

`FeedbackTest` (10 tests: access rules, tenant isolation, validation and size limits, credential masking, rate limit, paging, bounded memory
store) was run here with a small stand-in JUnit runner and passes; `JdbcFeedbackStore`, the REST classes and the Studio code were only
compiled or read, never run (no database, Quarkus or Flutter SDK here). `feedback_model_test.dart` is pure Dart and unrun. CI is the real check.
