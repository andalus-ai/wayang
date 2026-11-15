# UI surface actions: design proposal (not implemented)

Status: proposal. Today a surface button may only `refresh` or `navigate` (see UI-SURFACES.md). This note says how buttons could do real
work without turning a surface into a way to run arbitrary calls. Nothing here is built; please object before it is.

## The risk

A surface is data written by an agent or a workflow, possibly influenced by untrusted input. If a button can name any endpoint, token or
payload, whoever shapes the surface can make a signed-in person call it with their own rights (a confused-deputy problem). So a button must
never carry a URL, a header, a free-form body or a secret.

## Proposal

1. **Closed set of operations.** The button names an operation from a short server-side allowlist, not a path:
   `task.complete` (with an `outcome` chosen from the choices the task itself declares), `run.cancel`, `run.start` (a named, published process).
   Anything else stays refused.
2. **References, not capabilities.** Arguments are ids (`taskId`, `runId`, `processId`) plus small plain values. The surface never holds a
   task token; the server looks the token up for the signed-in user.
3. **The person's rights, checked again.** Studio calls the existing endpoint with the viewer's own session. The server re-checks that the
   viewer may act on that task, run or process in the current tenant and project. A surface cannot grant anything.
4. **Binding to its origin.** A surface drawn from a run may only act on that run, its tasks, or processes of the same project. Playground
   surfaces (pasted by hand) get no acting buttons.
5. **Confirmation.** Studio shows what will happen ("Approve task 'Review order 42'") and asks before sending. The label on the button is
   not trusted for this: the confirmation text comes from the operation and the referenced object.
6. **Audit.** Each action is recorded with the viewer, surface id, operation and ids, never the values of outputs.
7. **One-shot.** After an action succeeds, Studio reloads the object; a second click re-checks state, so a stale surface cannot repeat it.

## Open questions

- Should `run.start` exist at all, or is `navigate` to a start screen enough?
- Where does the audit record live: the feedback store's journal pattern, or Gamelan's event log?
- How does A2UI's `Button` action name map onto these operations: an explicit mapping table per surface source?

## Tests that would be required

Refusal of unknown operations and extra keys; an action naming another tenant's task; a pasted playground surface with an acting button;
a surface carrying a token-like value; confirmation text coming from the object, not the label; double submit.
