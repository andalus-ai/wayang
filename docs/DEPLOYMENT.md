
## Worker pools (Gamelan)
One worker image can run as several pools. Gamelan registers every Wayang executor the worker's CDI container offers, and each executor has a switch, **on unless you
turn it off**: `wayang.worker.<name>.enabled` with name `service` (connector calls), `agent` (AI steps), `bpmn` (events and gateways, timers, message catches), `dmn`, `eip`, `human`
(environment form: `WAYANG_WORKER_AGENT_ENABLED=false`). Examples: a pool for connector calls and integration only (`agent` off) next to a pool with the model access and the larger
memory the agent steps need (everything else off). Every executor type must be on in at least one pool of the same Gamelan deployment, otherwise the nodes that need it wait for a worker
that is not there (the run stays in progress): a Wayang node maps to exactly one executor type, there is no fallback to another pool.
The switch is read when a worker starts (Quarkus `@LookupIfProperty`). Not verified: no live Gamelan run, no worker started with a switch off; CI only compiles this and checks the declarations.
