# Security notes

What is enforced, how it is tested, and what is **not** covered. Report anything you find that this page does not describe.

## Outbound calls (SSRF)
- Every connector that takes a URL goes through `UrlGuard`: http/https only, no credentials in the URL, the host must resolve **only** to public addresses
  (unless the operator sets `WAYANG_CONNECTORS_ALLOW_PRIVATE=true` or lists the host), no redirects are followed.
- Addresses judged internal include loopback, private, link-local (cloud metadata), carrier-grade NAT, documentation and benchmarking ranges, multicast/reserved,
  IPv6 unique-local (also the AWS IPv6 metadata address), Teredo, and every IPv6 form that **embeds** an IPv4 address (IPv4-mapped, IPv4-compatible, NAT64 `64:ff9b::/96`,
  6to4 `2002::/16`): those are judged by the embedded address. Tested with literals and tricky URLs (`UrlGuardHardeningTest`).
- Raw-socket protocols (SMTP/IMAP) resolve once and connect to that address (`checkHost`).
- **Not covered: DNS rebinding for HTTP and WebSocket connectors.** The guard resolves the name, the JDK client resolves it again when it connects, so a hostile DNS server can
  answer differently the second time. The JDK offers no per-request pinned resolution (a literal-IP URL would break TLS name checks). For hard guarantees run the server behind an
  egress firewall or an egress proxy that applies the same rules.

## Secrets
- Definitions, bundles and releases carry only `${secret:NAME}` references, never values; secret values are not written to logs, errors or reports.
- Webhook secrets are compared in constant time. Bot tokens / API keys are sent only to the provider's host (the Slack upload URL, for example, never receives the bot token).

## Untrusted input limits
- HTTP body: 1 MiB (`quarkus.http.limits.max-body-size`); webhook body 1 MiB; connectors cap response sizes; media 20 MiB (audio 10 MiB).
- Expressions, scripts, JSON, XML (no DOCTYPE, no external entities, no XInclude), CSV: tested with deep nesting, huge numbers, entity bombs and costly arithmetic
  (`AdversarialInputTest`, `AdversarialJsonTest`, `AdversarialFormatsTest`); all end in an ordinary error, quickly. The JSON parser refuses number literals over 64 characters or
  with an exponent beyond +-400 (a million-digit number took seconds to parse; `1e999999999` would need a gigabyte to print).
- The expression engine has a step budget and no I/O, reflection, regex or randomness. CES scripts share one budget.

## Scripts
`javascript` / `python` (JSR-223) are **off by default** and are **not a sandbox**; see `docs/SCRIPTING.md`. Enable only for trusted authors.

## HTTP API
Responses carry `nosniff`, `X-Frame-Options: DENY`, `no-referrer`, `Cache-Control: no-store` and a restrictive CSP; CORS is closed unless configured. The headers are configuration,
verified only by reading the properties (no running server in CI).

## Tenants, triggers and chat channels
- Every API call is scoped to the caller's tenant (runs, tasks, definitions, projects, secrets, triggers); a suspended tenant is refused before anything runs. Cross-tenant tests cover runs, tasks, cancel, subscribe, messages and sweeps.
- **Messages:** waiting message events and early (buffered) messages are keyed by tenant + name + correlation key, so one tenant's message can never be delivered to, or consumed by, another tenant's run. (This was a known gap until the tenant-messages fix; waits stored by an older version keep their unscoped key, so drain or restart such runs when upgrading.)
- **Webhooks:** the secret is compared in constant time; a caller with the right secret is still held to the trigger's `rateLimit` (429) when one is set. Rate limits are opt-in: set one on any trigger that is reachable from outside.
- **Chat triggers:** Telegram needs `allowedChatIds`, Slack `allowedChannels` (a trigger without them never fires, and the Doctor fails a release that has one). In a group or channel every member can write, so add `allowedUserIds` (Telegram) / `allowedUsers` (Slack) to accept only named senders. The assistant template uses a private chat and `approvals: true` for risky tools.

## Not verified here
No penetration test, no fuzzing campaign beyond the cases above, no review of the Quarkus runtime configuration of a deployed server, no live Gamelan/Numu/Postgres run in CI.
