# Multimodal (extension module `wayang-multimodal`)

The first extension built on `WayangExtension` (see `docs/PROFILES.md`). It adds two connectors, loaded only when the profile enables the
`media` feature (`full` and `agentic` do; `automation` and `integration` do not).

| Connector | What it does |
|---|---|
| `image-generate` | Text to image through any OpenAI-compatible `/images/generations` endpoint (`baseUrl`, `model`, `size`, `apiKey` as `${secret:NAME}`) |
| `comfyui` | Runs a ComfyUI workflow (API-format JSON in `workflow`, `{{prompt}}` marks where the prompt goes), waits up to `timeoutSeconds` (max 600), keeps the first image |

Use them from a SERVICE_TASK (`connector: image-generate`, input `prompt`) or as agent tools (`type: image-generate`).

## Design

- **Claim check.** Image bytes never enter process variables. The result is `{mediaId, mimeType, bytes}`; bytes live in
  `WAYANG_MEDIA_DIR` (else `WAYANG_STATE_DIR/media`). Ids are `<32 hex>.<png|jpg|webp|gif>`, so an id can never be a path. Without a directory
  the connectors fail with a clear message instead of guessing one.
- **Provider output is not trusted.** The type is sniffed from the bytes (PNG, JPEG, WebP, GIF), never taken from a header; anything else is
  refused and nothing is written. 20 MiB cap.
- **Prompt injection into the workflow is structurally impossible.** ComfyUI substitution is done per string value on the parsed workflow,
  not on its JSON text, so a prompt cannot add nodes. Output file names from ComfyUI are validated before `/view` is requested.
- **Same network rules as other connectors.** Every URL passes `UrlGuard`; a local ComfyUI on `127.0.0.1` needs
  `WAYANG_CONNECTORS_ALLOW_PRIVATE=true` (or an allowed-hosts entry). No redirects are followed.
- **Scaling.** The `media` feature has its own executor type (`wayang-media`), so a GPU-backed worker pool can serve it separately from the
  BPMN engine once per-feature worker pools exist on Gamelan.

## Not done

Slack image upload (its upload is a three-step flow), a download endpoint, audio (speech to text, text to
speech), image input and vision, ComfyUI batch/multiple outputs, and a Studio node form.

## Sending an image (Telegram)

The `telegram` connector has operation `sendPhoto`: input `mediaId` (from `image-generate` or `comfyui`) and optional `caption` (at most 1024
characters) upload the stored file as multipart. `chatId` in the *configuration* fixes the destination: use that when the call is an agent
tool, so the model cannot redirect the picture to another chat. `MediaStore` now lives in `wayang-connectors` so connectors can read it.

## In the assistant and in releases

- Telegram assistant template: `images: true` (optional `imageModel`, default `gpt-image-1`) adds `generate_image` and `send_image`. The key is
  `${secret:IMAGE_API_KEY}`; `send_image` has the chat id fixed in its configuration and takes only `mediaId` and `caption` from the model.
  Slack is not supported yet.
- Releases: a bundle whose process or agent tools use `image-generate` or `comfyui` is accepted (the extension is found on the publisher's
  classpath under a profile with `media`), and the generated project then depends on `wayang-multimodal`; other releases do not. At run
  time the embedded runtime and the Gamelan worker both load the extension through `ServiceLoader`. Set `WAYANG_MEDIA_DIR` or
  `WAYANG_STATE_DIR` for the stored images.
## Voice notes (speech to text)

Telegram voice notes flow through the same claim-check store as images:

1. Trigger `telegram` with `"voice": true` turns a voice note (at most 5 minutes, from an allowed chat) into an event with empty `text` plus `voiceFileId` and `voiceSeconds`. Without `voice: true` only text messages are events.
2. Connector `telegram`, operation `downloadFile` (input `fileId`) fetches the file into the media store (at most 10 MiB, content sniffed as ogg/mp3/wav/m4a) and returns `mediaId`.
3. Connector `transcribe` (input `mediaId`; config `model` default `whisper-1`, `apiKey` as `${secret:NAME}`, `baseUrl` default OpenAI, optional `language`) returns `text` (at most 8000 characters). Any OpenAI-compatible `/audio/transcriptions` server works, including a local Whisper.

The transcript is what someone said, so it is untrusted input like any message. `transcribe` is a MEDIA-profile connector and is added to the multimodal dependency of generated releases.

Verified: unit tests against local fake servers only. Not run against real Telegram or a real transcription provider.

### Template option `voice`

`assistant-telegram` accepts `voice: true` (MEDIA profile required; Telegram only). The process then starts with `kind`: a message with `event.voiceFileId` goes `fetch` (telegram `downloadFile`) → `hear` (`transcribe`, secret `OPENAI_API_KEY`) → `draft`; everything else follows the normal path. The agent reads `{{ event.text }}{{ hear.text }}`, and the approval policy now checks the typed text **and** the transcript for pay / transfer / password / delete (case-insensitive), so a spoken request is held for approval exactly like a typed one. Replies to voice notes are text.

## Images on Slack

Connector `slack`, operation `uploadFile` shares a stored image in a channel using Slack's external upload flow (`files.getUploadURLExternal`, the bytes to the returned URL, `files.completeUploadExternal`). The Slack app needs the `files:write` scope; `channel` must be a channel **id** (C…, G…, D…). The bot token goes only to `slack.com`, never to the upload host. Inputs: `mediaId`, optional `text` (shown with the image), `threadTs`, `title`; output `fileId`, `channel`. Template `assistant-slack` accepts `images: true` like the Telegram one: the `send_image` tool has the channel fixed in its configuration, so the model cannot post elsewhere.

Verified against a local fake Slack only, not the real Slack API.

## Spoken replies (text to speech)
Connector `speak` (MEDIA feature, agentic profile): `{"type": "speak", "config": {"voice": "alloy", "apiKey": "${secret:OPENAI_API_KEY}"}}`, input `text` (at most 4000 characters).
It calls an OpenAI-compatible `/audio/speech` endpoint (OpenAI or a local server through `baseUrl`), stores the audio in the media store and returns `mediaId`.
`format` is `opus` (default, Ogg/Opus: Telegram shows it as a voice note), `mp3` or `wav`. Send it with `telegram` operation `sendVoice` (`mediaId`, fixed `chatId` in the
configuration): Ogg goes out as a voice note, other audio as an audio file. Flow: `speak` node, then `telegram sendVoice` node. Template option for the assistant: not wired yet.
Tested against a fake provider and a fake Telegram server only.
