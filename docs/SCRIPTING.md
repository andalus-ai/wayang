# Scripting

A service task with connector `script` runs a short script over the node's inputs. Three languages:

| language | what | availability |
|---|---|---|
| `ces` (default) | **Common Expression Script**: a few statements of the Wayang expression language | always on |
| `javascript` / `js` | any JSR-223 JavaScript engine (OpenJDK **Project Detroit**, GraalJS, ...) | **off** until the operator enables it |
| `python` / `py` | any JSR-223 Python engine (Project Detroit, GraalPy, ...) | **off** until the operator enables it |

Config: **`code`** (written in the definition, never taken from the node's inputs; at most 100,000 characters), `language`, `timeoutMs` (default 5000, 100 to 60000),
`outputMap` (variable to path into the result, e.g. `{"total": "result.total"}`), `engine` (a JSR-223 engine name to use instead of the defaults).
Output: `result` (JSON-style, at most 1 MiB) plus the `outputMap` variables. Errors are `Script error: ...`, never retried.

## CES

The language of conditions and DMN, with statements:

```
// statements end at a new line or a semicolon; breaks inside (), [] and {} do not end one
total := sum(items.price)
tax := total * 0.11
`grand total` := total + tax
if `grand total` > 1000 then "review" else "ok"      // the last statement is the result
```

- `name := expression` binds a value for the later statements (backticks allow spaces); it shadows an input of the same name. `return expression` is the same as the bare expression. An assignment yields its value.
- Each input is a variable. The whole script is parsed first (errors say "Statement N"), then runs with **one shared step budget**; at most 20,000 characters and 100 statements.
- Same constraints as every expression: no I/O, reflection, regex or randomness. This is the one to prefer.

## JavaScript and Python (JSR-223)

Wayang only talks to the standard `javax.script` interface, so it compiles against no engine and works with whatever is on the class path. Put the engine on the server's
class path (for example a Project Detroit build, which is early-access OpenJDK work and not part of a stock JDK), then enable the language:

```
WAYANG_SCRIPT_LANGUAGES=ces,javascript,python     # default: ces only; a typo is a start-up error
```

The script sees one object, **`input`** (a JSON-style copy of the inputs: maps, lists, text, numbers, booleans); the value of its last expression is the result, converted the same way
(anything else becomes its text). The engine is looked up by name (`javascript`/`js`, `python`/`graalpy`) on every call and gets fresh bindings.

**These engines are not a sandbox.** They run in the server process with its privileges; depending on the engine a script may read files, reach the network or use native code.
A runaway script cannot be stopped by force: the timeout frees the caller and interrupts the thread, and that is all (a script that ignores interrupts keeps its thread and one of
the 4 slots until it ends; at most 4 foreign scripts run at once, further calls fail with a retryable error). So:

- enable them only where **every author of a definition is trusted**, or run that work in a separate worker process;
- code is fixed in the definition (an agent tool of type `script` has fixed code and the model only supplies data in `input`);
- the connector test button never runs `script`.

## Verified / not

CES: unit tests (statements, shadowing, bracket/string/comment splitting, error positions, shared step budget). The connector: unit tests with a stand-in JSR-223 engine
(allowlist, copy of inputs, result conversion, timeout, error text, the 4-slot limit). **Not tested with a real Project Detroit, GraalJS or GraalPy engine**, and nothing here evaluates
whether any of them is safe for untrusted code. "CES" is implemented as described above; if you meant a different "Common Expression Script", tell me the spec.
