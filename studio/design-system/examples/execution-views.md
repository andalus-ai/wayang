# WDS execution-view composition examples

These examples show how product modes can assemble a consistent execution
experience from WDS primitives without moving domain concepts into the design
system. They are composition guidance, not runtime contracts.

The examples use the execution API introduced in the M6 change
(\`execution.dart\`). They become directly importable from
\`package:wayang_studio/design_system/wds.dart\` once that change is merged.

## Shared composition pattern

An execution detail page generally has four regions:

1. **Identity and lifecycle** — run name, identifier, status and host actions.
2. **Progress or trace** — ordered domain steps rendered as a timeline.
3. **Events** — diagnostic events, filtered and ordered by the host.
4. **Context** — selected step details, input/output summaries and related
   resources.

The host controls responsive breakpoints, page routing, data loading, live
updates, filtering and authorization. WDS supplies the consistent primitives
inside these regions.

\`\`\`dart
Column(
  crossAxisAlignment: CrossAxisAlignment.stretch,
  children: [
    WdsPageHeader(
      title: run.displayName,
      description: run.reference,
      actions: [
        WdsExecutionStatusIndicator(status: mapRunStatus(run.status)),
      ],
    ),
    const SizedBox(height: WdsSpacing.lg),
    WdsExecutionTimeline(
      steps: run.steps.map(toWdsStep).toList(growable: false),
      activeStepId: selectedStepId,
      onStepSelected: (step) => selectStep(step.id),
    ),
    const SizedBox(height: WdsSpacing.lg),
    WdsExecutionEventList(
      events: visibleEvents.map(toWdsEvent).toList(growable: false),
      onEventSelected: (event) => inspectEvent(event.id),
    ),
  ],
)
\`\`\`

The mapping functions are intentionally host-owned. A product mode decides
which domain object becomes a display step, what lifecycle states mean, and
which events are visible to a given user.

## Agent Builder — agent run inspector

**Primary question:** what did the agent do, which tools did it call, and where
did it fail or need human input?

Suggested composition:

- Run header: agent/version, trigger, run identifier and overall status.
- Timeline: prompt/context preparation, model invocation, tool calls,
  approvals and final response. These are illustrative host-defined stages;
  WDS does not prescribe an agent execution lifecycle.
- Event list: tool request/response summaries, model/provider errors,
  guardrail outcomes and approval events.
- Context panel: selected step's sanitized input/output, token/cost metadata,
  tool arguments and trace references, rendered by the Agent Builder feature.

\`\`\`dart
WdsExecutionTimeline(
  steps: agentRun.stages.map(toWdsStep).toList(growable: false),
  activeStepId: selectedStageId,
  onStepSelected: (step) => selectAgentStage(step.id),
)
\`\`\`

Keep sensitive prompt content, credentials and tool payload redaction in the
feature/service boundary. WDS should receive only the already-authorized,
already-redacted display data.

## Integration / ESB — connector trace

**Primary question:** how did a message travel through connectors and
transformations, and which hop failed?

Suggested composition:

- Trace header: correlation ID, message key, route and final outcome.
- Timeline: route hops, connector invocations, transformations and delivery
  attempts, in the exact order supplied by the trace service.
- Event list: connection failures, retries, mapping errors, acknowledgements
  and dead-letter transitions.
- Context panel: endpoint identity, elapsed time, sanitized headers and
  payload preview, retry policy reference and delivery metadata.

\`\`\`dart
WdsExecutionTimeline(
  steps: messageTrace.hops.map(toWdsStep).toList(growable: false),
  activeStepId: selectedHopId,
  onStepSelected: (step) => inspectHop(step.id),
)
\`\`\`

The ESB feature owns correlation, distributed trace stitching, connector
semantics, payload masking and retry interpretation. Do not make WDS aware of
EIP patterns, connector types or delivery guarantees.

## Business Automation — BPMN process instance

**Primary question:** which activities, gateways, timers or human tasks have
been reached, and what is waiting for action?

Suggested composition:

- Process header: process definition/version, instance key and lifecycle.
- Timeline: selected process-instance history, with activity labels supplied
  by the BPMN feature.
- Event list: task created/completed, timer fired, boundary event, incident,
  variable update and operator action.
- Context panel: selected activity, assigned user/group, due date, variables
  and incident details. BPMN diagram highlighting remains a feature-specific
  canvas responsibility.

\`\`\`dart
WdsExecutionTimeline(
  steps: processInstance.history.map(toWdsStep).toList(growable: false),
  activeStepId: selectedActivityId,
  onStepSelected: (step) => focusActivity(step.id),
)
\`\`\`

The BPMN engine and feature own token flow, gateway evaluation, activity
identity, task assignment, DMN decisions and incident semantics. A timeline is
a complementary history view, not a replacement for the BPMN diagram.

## Intelligent BI — pipeline execution

**Primary question:** which data preparation, enrichment, analysis or
publishing stage produced the current result?

Suggested composition:

- Pipeline header: dataset/report, execution window, freshness and outcome.
- Timeline: source acquisition, validation, transformation, enrichment,
  aggregation, model/agent analysis and publication, as defined by the
  pipeline feature.
- Event list: schema drift, quality threshold, source lag, transformation
  failure and publication events.
- Context panel: row/record counts, data-quality metrics, lineage references,
  artifact links and chart/report outputs.

\`\`\`dart
WdsExecutionTimeline(
  steps: pipelineRun.stages.map(toWdsStep).toList(growable: false),
  activeStepId: selectedStageId,
  onStepSelected: (step) => inspectPipelineStage(step.id),
)
\`\`\`

The BI feature owns lineage, data quality rules, dataset semantics, metrics,
artifact access and visualization. Avoid embedding BI concepts in WDS status
or timeline APIs.

## Ownership boundary

| Concern | WDS | Product mode / host |
|---|---|---|
| Status visual, badge and timeline row | Render | Map domain lifecycle |
| Ordered step and event display | Render supplied data | Fetch, order and filter |
| Selection callback | Expose | Own selected item and navigation |
| Retry and cancellation | Display supplied state | Execute and authorize |
| Payload masking and permissions | No policy decisions | Enforce before rendering |
| Correlation, BPMN tokens, agent traces, BI lineage | No domain semantics | Own domain model |
| Live updates and pagination | No subscriptions | Own streams and paging |

## Recommended responsive behavior

- Wide desktop: timeline and context side-by-side, with events below or in a
  dedicated tab.
- Medium layout: timeline first, context in a collapsible inspector.
- Compact/mobile: status and key metadata first; timeline and events stacked;
  selected-step details opened as a sheet or route.
- Preserve a stable step/event identity across updates so selection does not
  jump when new events arrive.
- Keep large payloads, stack traces and artifacts lazy-loaded by the host;
  pass a concise summary to the shared presentation layer.

## Avoid these anti-patterns

- Adding \`WdsAgentToolCall\`, \`WdsBpmnActivity\` or \`WdsBiStage\` to the generic
  design system.
- Making a WDS timeline subscribe directly to runtime or event-bus services.
- Treating display order as a guaranteed execution dependency.
- Assuming all modes share identical retry, cancellation or success semantics.
- Passing unredacted secrets or unrestricted payloads to generic widgets.
