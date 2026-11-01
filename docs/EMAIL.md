# Email (Gmail) and connected accounts

Gmail is reached through **OAuth2 connections**, not passwords. A connection is a per-tenant authorised account; the
workflow refers to it as `${secret:oauth.<connection-id>}` and gets a fresh access token at run time.

## Setup

1. In Google Cloud, create an OAuth client (type *Web application*), enable the Gmail API, and register the redirect URI
   `https://<your-wayang>/oauth/callback`.
2. Configure Wayang: `wayang.secrets.key=...` (secrets must be on) and `wayang.oauth.redirect-uri=<that exact URI>`.
3. Store the client secret: `PUT /api/v1/secrets/GOOGLE_CLIENT_SECRET {"value": "..."}`.
4. Create the connection (needs `SECRET_MANAGE`):
   `POST /api/v1/oauth/connections {"id":"work","provider":"google","clientId":"...","clientSecretName":"GOOGLE_CLIENT_SECRET","scopes":["https://www.googleapis.com/auth/gmail.readonly","https://www.googleapis.com/auth/gmail.send"]}`
5. `POST /api/v1/oauth/connections/work/authorize` returns `{"url": ...}`; open it, grant access. Google redirects to
   `/oauth/callback`, the connection becomes `CONNECTED`.

Allowed scopes: `gmail.readonly`, `gmail.send`, `gmail.metadata`, `gmail.compose`, `calendar.events`, `calendar.readonly`, `spreadsheets`. The full-mailbox scope
(`https://mail.google.com/`) is deliberately not offered.

In the Studio: **Channels → Connected accounts → Connect Gmail**, then *Authorise* (shows the consent link to open in a
browser; the app has no URL launcher dependency) and *Refresh*. **Channels → Triggers** creates the poll/webhook triggers
below from per-channel config templates.

## Triggers and replies

```json
{ "id":"inbox", "kind":"POLL", "processId":"assistant",
  "config": { "source":"gmail", "everySeconds":60,
              "accessToken":"${secret:oauth.work}",
              "allowedSenders":["boss@example.com"] } }
```

* `allowedSenders` is **required** (or an explicit `acceptAnySender: true`): anyone can email you.
* `requireAuthenticated` (default true): the *first* `Authentication-Results` header, added by Gmail's own MX, must show
  `dkim=pass` or `spf=pass`. A `From:` header alone is trivially forged, and the allow-list is only as good as it.
* Metadata and snippet only by default; `includeBody: true` adds the plain-text body (clipped to 8000 chars).
* The first poll records "now" and returns nothing: no backfill of the whole mailbox. The cursor then advances past
  rejected mail too. Event: `messageId, threadId, from, subject, snippet, date, rfcMessageId, body?`.

Sending is the `gmail` connector (`operation: send`; inputs `to, subject, text, threadId?, inReplyTo?`). Header
injection (CR/LF) is refused, subjects are RFC 2047 encoded, and `allowedRecipients` in the node config pins who can be
emailed. As everywhere else: let the agent *draft*, gate the send with a DMN policy and a human approval task.

## Security model

* **Refresh tokens** are stored AES-256-GCM sealed under the reserved name `oauth-refresh.<id>`. Names starting with
  `oauth.` / `oauth-` cannot be created, listed or deleted through the secrets API, and a workflow's
  `${secret:oauth-refresh.<id>}` resolves to nothing. Only access tokens (≈1 h) ever reach a node.
* **Authorization code + PKCE (S256)**; `state` is random, single use, expires after 10 minutes and is bound to tenant
  and connection (JDBC: `DELETE ... RETURNING`, so concurrent callbacks cannot both consume it). The redirect URI comes
  from configuration, never from the caller. The callback reflects nothing from the request.
* `invalid_grant` (user revoked access) marks the connection `REVOKED` and stops retrying; transient failures do not.
* Access tokens are cached per tenant+connection until 60 s before expiry; rotated refresh tokens are persisted.
* A workflow that can call an arbitrary URL could send its access token there, like any other secret: keep
  `wayang.connectors.http.allowed-hosts` tight.

## Verification status

Tested against a fake token endpoint and a fake Gmail (JDK HTTP server): PKCE verifier/challenge match, refresh and
rotation, single-use/expiry of state, revocation, tenant isolation, header injection, sender allow-list, DKIM/SPF
gating, MIME round-trip; JDBC store against PostgreSQL 16. **Not** verified against real Google: endpoint paths follow
Google's documentation, but no live token exchange or Gmail call has been made, and the REST resources compile only
against hand-written stubs.
