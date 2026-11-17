# Studio: agent form

The **Agents** screen has a **Form** view next to the JSON view (the toggle at the top of the editor). The JSON document stays the source of truth:
every form edit rewrites it, keys the form does not know are kept, and a change made in the JSON view or by opening another agent refills the form.
Nothing is sent anywhere until you use Save draft, Validate or Publish, and the server's validation is authoritative.

| Section | Edits | Notes |
|---|---|---|
| Basics | `model`, `name`, `maxSteps` (1-50), `timeoutSeconds` (1-3600) | numbers are clamped; blank removes the key and the server default applies |
| Persona | `options.persona`: role, tone, reply language, boundaries | blank persona removes the option; other `options` (e.g. `memory`) are untouched |
| Instructions | `systemPrompt`, `inputTemplate` | the preview shows what the model is told: persona block, then the prompt with `{{ }}` still unfilled |
| Guardrails | `options.guardrails`: redact email/phone/card, max output characters, blocked terms | enforced by Wayang on the agent's returned text; see `docs/RELEASE.md` (best-effort redaction, no prompt or tool filtering) |
| Tools | name, connector `type`, description, `config` (JSON), "needs human approval" | `requireApproval` is kept equal to the tools that need approval, so renaming or removing a tool cannot leave a dangling name |

Tool hints (missing name/type, literal credentials, malformed `${...}`) mirror the publish checks (`AgentToolCheck`) so they show up while typing;
a tool's `config` is written back only while it is a valid JSON object. Credentials go in as `${secret:NAME}` references, never literals.

Not in the form yet: the memory option, `outputKeys`, picking MCP servers or connectors from a list, and running a test against the agent.

Verification: the pure helpers (`studio/lib/data/agent_form.dart`) have unit tests (`studio/test/agent_form_test.dart`); the widget itself has no widget test and
is checked only by `flutter analyze` and the tests in CI. It has not been looked at in a running app.
