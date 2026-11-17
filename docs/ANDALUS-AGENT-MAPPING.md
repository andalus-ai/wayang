# Running a Wayang agent on Andalus: findings and proposed changes

Status: **design note, read-only investigation. Nothing here has been changed on the Andalus side, and nothing was run.** It is based on reading
`andalus-ai/andalus` at `9d717f6` and `andalus-ai/andalus-framework` at `d979007` (both as cloned on 2026-11-17). Anything marked *to confirm* is
something I could not establish from the code alone. Wayang's roadmap rule stands: Andalus owns agent execution; Wayang must not grow a second agent runtime.

## What Wayang hands over

`AgentSpec` (id, model, `systemPrompt`, `inputTemplate`, tools, `maxSteps`, `timeoutSeconds`, `requireApproval`, `outputKeys`, `options.memory`,
`options.persona`, `options.guardrails`). Today `AndalusAgentInvoker` calls `ExecutionService.startAgent(spec.id(), inputs)` and passes the limits as
input variables (`wayang.maxSteps`, `wayang.timeoutSeconds`, `wayang.requireApproval`) "for the agent's guardrails to honour".
Wayang itself enforces only what it can on the way out: persona composition and output guardrails in `AgentHandler` (see `docs/RELEASE.md`).

## What Andalus does with an agent (as read)

| Wayang concept | Andalus today | Gap |
|---|---|---|
| Entry point | `ExecutionService.startAgent(AgentDefinition, inputs)` is declared in the framework (`andalus-engine`). The runtime's real entry is `AgentExecutionService.create(AgentDefinition, AgentRequest, ExecutionBudget)`. I found **no implementation of `ExecutionService.startAgent`** in either repo outside tests (*to confirm: it may live in another artifact*). | The adapter may have nothing to call. Needs an owner's answer before any mapper is worth writing. |
| System prompt | `DefaultAgentExecution` uses **`agent.goal()`** as the system prompt (fallback "You are a helpful AI assistant."). `role()` and `instructions()` are not read there. | Persona and `systemPrompt` have to be put in `goal`, or Andalus should use `instructions`/`role`. |
| `maxSteps`, `timeoutSeconds` | `ExecutionBudget(maxDuration, maxSteps, maxToolCalls, token limits, ...)` exists. In `DefaultAgentExecution` the budget's `maxSteps`/`maxDuration` are read only to classify a profile (lines ~675-678); I did not find them enforced there (*to confirm in the ReAct loop*). Through `startAgent(definition, inputs)` there is no way to pass a budget at all. | Limits Wayang passes as inputs are advisory at best. |
| Tools | The tools offered to the model come from `AgentToolExecutor.ToolAware.availableTools()`: the deployment's global tool set. `AgentDefinition.tools()` (references) is not used to pick them. | No per-agent tool list: an agent may be offered tools its Wayang definition never declared. |
| `requireApproval` | Approval comes from CDI `ToolPolicy` beans evaluated by `DefaultToolPolicyEvaluator` (`RequireApproval` becomes `WaitForApproval`). The ReAct agent is built with `withAutoApproveTools(true)`. There is no per-agent input. | A Wayang `requireApproval` list is not honoured; whether any deployed policy covers it is a deployment matter. |
| Guardrails | `ToolGuardrailCheck` (SPI) / `DefaultToolGuardrailCheck` runs `GuardrailsService.preCheck` on tool name and arguments and **fails open** on error or timeout (5 s). `AgentDefinition.constraints()` is a free map that no framework or runtime code reads. | No per-agent guardrail config; fail-open is documented as flippable "in production" but there is no switch. |
| Memory | Wayang passes `input.history`; the adapter does not use it (documented). | Out of scope here. |

## Proposed split

**Wayang side (small, after the owners confirm the entry point):** an `AgentSpecMapper` in `wayang-adapter-andalus` producing an `AgentDefinition`:
`identity` from `id`; `goal` and `instructions` = persona block + rendered system prompt (same composition as `AgentPersona.compose`); `role` = persona role;
`model` reference from `model`; `tools` references by name; `constraints` = `{maxSteps, timeoutSeconds, requireApproval, guardrails}` so the data travels
even before Andalus reads it. Wayang keeps enforcing output guardrails itself, so a missing Andalus feature never weakens them.

**Andalus side (needs the owners; each is a separate small change, branch + PR to the Andalus repos):**
1. Make one documented entry point accept a budget (or read `constraints.maxSteps` / `timeoutSeconds` into an `ExecutionBudget`), and enforce `maxDuration` and `maxSteps` in the loop.
2. Offer the model only the tools named in `agent.tools()` when that list is non-empty (a per-agent allow-list), and refuse a call to anything else.
3. A per-agent `ToolPolicy` that returns `RequireApproval` for names in `constraints.requireApproval` (and a failing default when no approver is wired, instead of auto-approve).
4. Use `instructions` (and `role`) in the system prompt, or document that `goal` is the prompt.
5. A fail-closed option for `DefaultToolGuardrailCheck`, and read `constraints.guardrails` into the policy context.

Order of value: 3 and 2 (they stop an agent doing more than its definition says), then 1, then 4 and 5.

## Not decided here

Whether `ExecutionService` has an implementation elsewhere; whether Andalus wants `constraints` keys or a typed field; whether per-agent policy belongs in the
agent definition or in Andalus's deployment policy. I would ask the Andalus owners these three before writing code on either side.
