# Live smoke checklist

Everything in Wayang is tested against fakes here; nothing has been run against a real Telegram, Slack, model provider or Google account. This checklist is the shortest
way to find out, on your own machine or server, what actually works. Do it in order; each step says what you should see.

## 0. Before anything is switched on

1. Build and start the release (STANDALONE or SERVICE target): `java -jar app.jar doctor`.
   Expect: no `[FAIL]`. Fix each `[warn]` you do not accept (state directory, API key, token cap, rate limit).
2. `java -jar app.jar doctor --live` with the real secrets set. Expect `[ ok ]` for `live:model:<provider>` and for each `live:telegram:*` / `live:slack:*`.
   A `[FAIL]` says which credential was rejected, never its value. Both probes are read-only (`GET /models`, `getMe`, `auth.test`).

## 1. The model alone (no chat)

3. `java -jar app.jar run personal-assistant --input event.json` with `{"event":{"chatId":1,"text":"hello"},"trigger":"manual"}`.
   Expect a run that ends WAITING (approval) or COMPLETED, with the model's draft in the output. If it fails: wrong model name (`provider/model`), key, or `WAYANG_LLM_<PROVIDER>_URL`.
4. Look at the `usage` of the agent step: tokens are counted. Set `WAYANG_LLM_DAILY_TOKENS=1` and repeat: the second call must fail with "daily limit ... used up".

## 2. Telegram (poll)

5. Create the trigger from the template with *your* chat id, set `TELEGRAM_TOKEN`, enable it, `serve`.
6. Send "hello" from that chat. Expect a reply within about 10 s (the poll interval). Send from another account: expect no reply.
7. Send "please pay my rent": expect the draft **in the chat** with "Answer yes to send it or no to drop it". Answer "no": nothing more arrives. Ask again, answer "yes": the reply arrives.
8. Restart with `WAYANG_STATE_DIR` set: the old messages are not answered again.

## 3. Slack (webhook)

9. Point the Slack app's Events API request URL at `https://<host>/hooks/<trigger-id>`; Slack's challenge must be answered (the URL shows "Verified").
10. Message the allowed channel or DM: reply arrives. A message in another channel: nothing. The bot's own messages must not loop.

## 4. Calendar (if enabled)

11. Ask "what is on my calendar tomorrow?": the answer lists real events (needs the OAuth connection).
12. Ask "put lunch with Ani tomorrow at noon": the chat shows the draft **and** "Actions waiting for the same yes: - calendar_create ...". Nothing exists in the calendar yet. Answer "yes": the event appears; answer "no" to another: it does not.

## 5. Schedules (if any)

13. A schedule set for two minutes ahead: expect exactly one run at that time (check `GET /v1/triggers`: `lastRunAt`, `nextRunAt`). Restart the release before the time: still one run, not zero or two.

## 6. Limits

14. Send more than `rateLimit.perHour` messages from the allowed chat: the extra ones get no reply; "yes"/"no" still work.

## What to report

For each step that does not match, note the step number, the `doctor` output and the release's log line. Never paste secret values: the doctor output and the logs are written not to contain them,
but check before sharing.
