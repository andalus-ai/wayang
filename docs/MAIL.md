# Email with any provider (SMTP / IMAP) and Discord

Gmail has its own OAuth connector (see `EMAIL.md`). For every other provider (Fastmail, Zoho, Outlook with an app password,
your own Postfix/Dovecot, a transactional relay) there are plain protocol connectors. No JavaMail dependency: the clients
are small, in `wayang-connectors`, and bounded (line, literal and response sizes, timeouts, recipient and body caps).

## Sending: connector `smtp`

```json
{"connector":"smtp","host":"smtp.example.com","security":"starttls","username":"bot@example.com",
 "password":"${secret:SMTP_PASSWORD}","from":"bot@example.com","fromName":"Wayang",
 "inputs":{"to":["a@example.org"],"cc":[],"bcc":[],"subject":"Report","text":"...","html":"<b>...</b>","inReplyTo":"<id@host>"}}
```

* `security`: `tls` (implicit, port 465), `starttls` (587), `none` (port 25, **refused whenever credentials are set**).
  Default: `tls` on 465, otherwise `starttls`.
* STARTTLS must be advertised by the server; if it is not, the send fails and **no credentials are sent** (no silent downgrade).
  Certificates and host names are verified against the JVM trust store.
* `AUTH PLAIN` only, and only inside TLS. Output: `messageId`, `recipients`.
* Every address and header value is rejected if it contains control characters (header and command injection); a
  string such as `a@x.org\r\nRCPT TO:<evil@y.net>` is an error, not a silently different recipient. Non-ASCII subjects and
  display names are RFC 2047 encoded; text and HTML go out base64 (multipart/alternative when both are given). `bcc` is envelope-only.
* Errors: 4xx and network failures are retryable, 5xx (including wrong password) are not. Error text never contains the password.
* Limits: 50 recipients, 1,000,000 body characters.
* Targets are checked with the same SSRF guard as the http connector (`wayang.connectors.http.allow-private`, `allowed-hosts`):
  the host is resolved once and the socket connects to that address.

## Receiving: trigger source `imap`

```json
{"source":"imap","host":"imap.example.com","username":"bot@example.com","password":"${secret:IMAP_PASSWORD}",
 "mailbox":"INBOX","allowedSenders":["boss@example.org"],"authservId":"imap.example.com","everySeconds":60}
```

* Read-only: `EXAMINE` and `BODY.PEEK`. Nothing is marked read, moved or deleted.
* Cursor `uidValidity:lastUid`. The first poll records the position only (old mail is not replayed); a changed UIDVALIDITY
  resets to the current position instead of re-delivering the mailbox. At most `maxResults` (1..50) per poll; the rest follows.
  Mail that is filtered out still counts as seen. At-least-once: the cursor is stored after the events were handed over.
* **Anyone can email you**, so `allowedSenders` is required (or an explicit `acceptAnySender: true`), exactly as for Gmail.
  Sender addresses are taken from the address part of `From`, not the display name.
* `requireAuthenticated` (default true) trusts only the **first** `Authentication-Results` header (receiving servers prepend
  theirs; later ones can be forged) and requires `dkim=pass` or `spf=pass`. Set **`authservId`** to your server's identifier
  (the first token of that header) and headers from any other authority are ignored. Without it any first header counts, which
  is weaker if your server does not stamp mail.
* Event: `uid, messageId, from, subject (decoded), date (epoch ms), mailbox`, plus `body` with `includeBody: true`
  (text/plain preferred, HTML stripped as a fallback, first 64 KiB fetched, 20,000 characters kept).
* `password` must be a `${secret:NAME}` reference (the trigger API refuses literals).

## Discord: connector `discord`

`{"connector":"discord","webhookUrl":"${secret:DISCORD_HOOK}","inputs":{"text":"Done","username":"Wayang","threadId":"..."}}`.
Channel webhook only (no gateway/bot). The URL is a secret and must be `https://discord.com/api/webhooks/<id>/<token>`. Mentions are
disabled so untrusted text cannot ping `@everyone`. Output: `messageId`, `channelId`. 429/5xx are retryable. The URL is never echoed in errors.

## What was and was not verified

Tested in-JVM against scripted fake SMTP and IMAP servers (plain, STARTTLS and implicit TLS with a throw-away certificate made by
`keytool`; hostname mismatch, untrusted certificate, downgrade, wrong password, injection attempts, UID quirks, batching, UIDVALIDITY
reset, MIME decoding) and a fake Discord HTTP server. **Never run against a real mail server.** Real servers differ (folded or
oddly ordered FETCH responses, `[UIDNEXT]` missing on EXAMINE, AUTH PLAIN disabled in favour of XOAUTH2/LOGIN, IMAP4rev2). No OAuth2 (XOAUTH2)
for Outlook/Office 365 yet: use an app password or an OAuth proxy.

# More connectors

All of these take credentials as `${secret:NAME}`, validate every id that ends up in a URL path (no traversal, no extra segments),
cap sizes, and never echo a token or webhook URL in an error. Where a connector can write somewhere, the **allow-list is required**:
an agent or a hostile input can only touch what you named.

| Connector | Operations | Required config | Notes |
|---|---|---|---|
| `github` | `createIssue`, `comment`, `getIssue`, `listIssues` | `token`, `allowedRepos` (`owner/name`, `owner/*`) | PRs are not listed; Enterprise via `baseUrl` |
| `notion` | `createPage` (database row), `appendText`, `queryDatabase` | `token`, `allowedDatabases` / `allowedPages` | Text is chunked to Notion's 2000-char blocks (max 90 blocks); the title can't be overridden through `properties` |
| `teams` | post (Adaptive Card) | `webhookUrl` | Workflows / legacy incoming webhooks only (logic.azure.com, webhook.office.com, outlook.office.com, powerplatform.com) |
| `gcal` | `list`, `create`, `delete` | `accessToken: ${secret:oauth.X}` | Attendees are **not** emailed unless `notifyAttendees: true`; `start`/`end` are RFC 3339 with offset or `yyyy-MM-dd`; optional `allowedCalendars` |
| `twilio` | `send` (SMS, or WhatsApp via Twilio) | `accountSid`, `authToken`, `from`, `allowedTo` (exact E.164) | Form-encoded, Basic auth; body ≤ 1600 chars; a WhatsApp sender only reaches `whatsapp:` recipients |
| `resend` | `send` | `apiKey`, `from`, `allowedRecipients` (address or `@domain`) | ≤ 20 recipients; subject must have no control characters |
| `airtable` | `createRecord`, `updateRecord`, `listRecords` | `token`, `allowedTables` (`appXXXX/Table`, `appXXXX/*`) | Table name is URL-encoded; ids are pattern-checked; `filterByFormula` is passed through, so do not build it from untrusted text |
| `todoist` | `createTask`, `listTasks`, `closeTask` | `token`, `allowedProjects` | `closeTask` looks the task up first and checks its project |
| `linear` | `createIssue`, `comment`, `listIssues` | `token`, `allowedTeams` | GraphQL with variables only; `comment` checks the issue's team first; API errors are not echoed |
| `trello` | `createCard`, `listCards` | `apiKey`, `token`, `allowedLists` | Credentials in the `Authorization` header, never the URL |
| `soap` | any operation in `allowedOperations`; `mode: describe` lists them | `wsdl` (URL or text), `allowedOperations` | See below |
| `gsheets` | `read`, `append` | `accessToken`, `allowedSpreadsheets` | Written `RAW`: `=FORMULA()` stays text. Max 1000 cells per call |

Triggers: the `rss` poller (RSS 2.0 and Atom; config `url`, `limit`, `emitExisting`; the first poll only remembers what is there; XML with a DOCTYPE is refused)
and the `github` webhook provider (`auth: github`, config `secret`, `allowedRepos`, optional `events`; checks `X-Hub-Signature-256` over the raw body,
drops bot senders so a workflow that comments cannot trigger itself, answers `ping`).

Google connectors use the OAuth connection from `EMAIL.md`; create it with the extra scopes (`calendar.events`, `calendar.readonly`, `spreadsheets`).

Verified against a fake HTTP server that records requests (shape, headers, path encoding, allow-lists, traversal attempts, size caps,
error mapping). **Never called against the real GitHub, Notion, Teams, Google Calendar or Sheets, Twilio, Resend, Airtable, Todoist, Linear or Trello APIs, and no real RSS feed or GitHub delivery was used**: field names and limits come from
their public documentation as I know it, and any of them may differ in detail.

## SOAP / WSDL (`soap`)

```yaml
type: SERVICE_TASK
config:
  connector: soap
  wsdl: https://erp.example.com/ShopService?wsdl     # or the WSDL text itself
  operation: GetPrice
  allowedOperations: [GetPrice]                       # required
  username: svc-wayang
  password: ${secret:ERP_PASSWORD}                    # HTTP Basic; refused over plain http
  inputMapping: { sku: order.sku }                    # the content of the request element
  outputMapping: { price: "result.price" }
```
The connector reads the WSDL (cached 10 minutes), builds the SOAP envelope for the operation, posts it with the right `SOAPAction` / content type
and returns `result`: the response element as JSON (namespaces dropped, repeated elements become lists, attributes are `@name`, every value a string
because no XSD types are applied). Inputs: object keys become child elements, `"@x"` attributes, `"#text"` the text, lists repeat; for rpc style or several
message parts, key by part name. Use `mode: describe` (no `allowedOperations` needed) to list operations, their `soapAction` and input element.

Safety: operation names and every XML name are pattern-checked, values are escaped, control characters refused; DOCTYPE is refused in the WSDL and in
responses (no XXE); WSDL `import`/`include` are refused (they would make the server fetch other URLs: flatten the WSDL); a WSDL that fetched from host A
may not point at host B unless you set `endpoint`; the private-network guard covers both the WSDL and the endpoint; credentials never go over plain http
unless `allowInsecure: true`. A SOAP fault is a non-retryable failure with the fault code and text (retryable only for HTTP 429/503/504).

WS-Security **UsernameToken**: add `wsSecurity: usernameToken` (with `username`/`password`) and optionally `passwordType: digest` (default; `base64(sha1(nonce + created + password))`, the
profile's own formula, the server must hold the plain password) or `text` (needs https). It replaces HTTP Basic. Checked against the formula, not against a real WCF/CXF server.

**Studio:** on a `soap` service node, set the WSDL and press *Load operations from WSDL*; picking an operation sets `operation` and adds it to
`allowedOperations`. It calls `POST /api/v1/connectors/describe` (`{connector: "soap", config: {wsdl, endpoint?, port?}}`, needs DEFINITION_WRITE): one GET of the WSDL through the
same private-network guard, no operation is called, and credentials/headers in the node are never sent. Backend tested (`ConnectorInspectorTest`); the REST resource could not be
compiled here (it shares `Principals` with gRPC classes) and the Dart widget is parse-checked only.

Not supported: signatures/encryption (the rest of WS-Security), WS-Addressing, MTOM/attachments, SOAP encoding (rpc/encoded), XSD validation or typing, WSDL 2.0, wsdl imports.
Tested against a **fake SOAP server** only (SOAP 1.1 and 1.2, document and rpc literal); never against a real ERP or a WSDL generated by
Axis, CXF, WCF or SAP, and those often use features listed above, so expect to hit a limit with real-world WSDLs. `http` also gained `returnErrorBody`.

## Test node (Studio)

Under a service node whose operation only reads (`http` GET, `github` getIssue/listIssues, `notion` queryDatabase, `gcal` list, `gsheets` read, `trello` listCards,
`todoist` listTasks, `linear` listIssues, `airtable` listRecords, `mcp` listTools, `soap` describe) the inspector shows **Test node**: sample inputs, a confirmation
dialog, one real call. `POST /api/v1/connectors/test` `{connector, config, inputs, confirm: true}` needs DEFINITION_WRITE **and** RUN_START and refuses, before any call,
every operation that could send or change something (the server decides, not the button). `${secret:NAME}` is resolved in `config` only, with the caller's tenant; secret values are
scrubbed from errors and output; output is capped at 200 000 characters. A SOAP operation call is not testable here (it may change data): describe, or run the process.
Backend: `ConnectorTesterTest` (8 tests, 12 mutants killed; a test also checks the Dart offer list against the server's list). Both `/connectors/describe` and `/connectors/test` share a limit of 20 calls per minute per user (`RateLimiter`, fixed window, `429` over REST, `RESOURCE_EXHAUSTED` over gRPC). The limit is in memory and per process, so with several replicas it is the limit times the replicas: it protects third parties from a runaway Studio tab, it is not a quota. Requests refused by the read-only allow-list or by validation do not use up the budget. The REST resource
could not be compiled here; the Dart widget is parse-checked only.
