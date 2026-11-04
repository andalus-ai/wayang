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

Serving or sending the image (a Telegram/Slack photo upload and a download endpoint are the next step), audio (speech to text, text to
speech), image input and vision, ComfyUI batch/multiple outputs, bundling the module into generated releases (today it is available where it
is on the classpath: the server and embedded runtime), and a Studio node form.
