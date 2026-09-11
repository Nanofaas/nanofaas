# SDK runtime saturation and memory contract

This contract applies to direct `POST /invoke` calls as well as control-plane dispatches. The
machine-readable cases are in `saturation-wire-corpus.json`; every SDK has a thin adapter test that
parses that one file. P16a freezes the policy and inventory. P16b implements all count/byte limits
and runs each case against every runtime; P17 owns Python physical-work tracking and P18 owns
Java-lite shutdown resources.

## Common wire policy

Admission is fail-fast. A runtime must reserve input, handler and callback count/byte capacity
before starting the handler or making an avoidable large serialized copy. A callback requested by
the invocation is part of admission: the runtime must not accept work and silently discard its
terminal callback. Callback saturation is `429 RUNTIME_CALLBACK_SATURATED`; handler admission
saturation is `429 RUNTIME_HANDLER_SATURATED`. Oversized ingress is
`413 RUNTIME_INPUT_TOO_LARGE`. Output discovered after handler execution is
`500 RUNTIME_OUTPUT_TOO_LARGE`: the handler did start, its oversized output is not retained,
and a bounded error callback is still required. A request accepted after stop begins is
`503 RUNTIME_STOPPING`; after listener closure, connection refusal is the equivalent observable
outcome. These errors use `{"error":{"code":"...","message":"..."}}`; retryable admission
errors include `Retry-After: 1`.

All waits are finite and configurable. `HANDLER_TIMEOUT` is an attempt-level `504`, not P01's
per-waiter `408`: it may produce an error callback and then follow the control plane's configured
retry policy. Across dispatch retries, `X-Execution-Id` stays stable and
`X-Dispatch-Attempt` increments for each new invocation attempt. Callback delivery retries echo
the current invocation's attempt unchanged. SDK runtimes never redispatch; the control plane owns
that decision and caller idempotency.

Every acquired count and byte reservation remains owned until the represented work and retained
payload are physically gone. Release is mandatory on success, error, cancellation and stop.
Timing out an HTTP waiter does not prove a non-cooperative handler stopped. Callback delivery
exhaustion occurs after the invoke response, so it has no second HTTP response; it must be visible
through a bounded-cardinality failure metric and structured log,
and must release callback count, bytes and serialized body. Health remains independent enough to
report while invoke admission is saturated.

The corpus `deadlineMs` values are finite harness deadlines, not production defaults. Ordered
actions use named barriers; P16b/P17/P18 runtime harnesses must implement them with latches,
events, contexts or abort signals. Elapsed sleep is not ordering evidence.

## Retained-resource inventory at P16a

“Bound” below means an enforced retention bound, not a worker-count assumption.

| Runtime | Retained queues/tasks and actual count bounds | Input/output/callback bytes | Owner and release/stop path | Gap owner |
| --- | --- | --- | --- | --- |
| Java | `HandlerExecutor` uses a virtual-thread-per-task executor: no admission/queue count cap. `CallbackDispatcher` has 2 workers by default plus an `ArrayBlockingQueue(128)`, so up to 130 active/queued callback jobs; worker count is configurable, queue capacity is fixed. | Spring request decoding has no SDK byte cap or runtime-owned finite body-read deadline. Handler output is normalized to a `JsonNode` before callback admission; queued jobs retain it and `CallbackClient` later serializes another `byte[]`. | Spring owns both executors through `@PreDestroy`. Callback shutdown waits 5 s then interrupts; handler shutdown interrupts without observable drain. Callback connect/read limits are 5 s/10 s. | P16b for admission, bytes, ingress deadline and physical handler accounting; P18 verifies Spring HTTP-client ownership. |
| Java-lite | `HttpServer` uses an unreferenced virtual-thread-per-task executor, and each invoke starts another virtual thread: neither has admission count bounds. Callback executor defaults to 2 workers plus `ArrayBlockingQueue(128)` (130 active/queued), both configurable. | Request decode has neither a byte cap nor an application-owned finite body-read deadline. Response/callback serialization has no payload or aggregate byte cap. | `stop()` does not retain/close the server executor or HTTP client and does not await callback drain. Callback connect timeout is 5 s; request/read timeout is absent. | P18 for executor/client/start-stop ownership; P16b for quotas and body/callback request deadlines. |
| Python | Each request is an ASGI task. Sync handlers use `asyncio.to_thread`: default worker count is not a queue bound and its executor queue is unbounded. `BoundedSemaphore(128)` caps callback coroutines; Starlette background tasks and callback `to_thread` calls use externally owned/default executors. | `request.json()` has no SDK byte cap or runtime-owned finite body-read deadline. Handler output, response and callback dictionaries have no byte caps. | ASGI owns request/background tasks; the SDK owns no explicit handler/callback executor. Callback attempts have a 5 s timeout and three attempts; lifespan has no stop/drain cleanup. Timed-out sync work can retain payloads. | P17 for physical work/executor/shutdown truth; P16b for byte/direct admission and ingress deadline. |
| Go | `net/http` request goroutines and per-invoke handler goroutines have no admission cap; handler timeout does not stop non-cooperative work. Callback capacity is exactly `queueSize` buffered plus `workerCount` active (defaults 128 + 2). | Streaming decode has no max body and the server has no `ReadTimeout`/`ReadHeaderTimeout`. Callback jobs retain objects; active delivery retains one marshalled body across retries. | Dispatcher workers start in `NewRuntime`. If `ListenAndServe` returns a bind error, `Start` returns without dispatcher shutdown, leaking those workers. Cancellation shutdown can also return before workers are observed drained. | P16b owns bind-failure cleanup, ingress deadlines, quotas, physical handlers and bounded stop. |
| JavaScript | Node owns one request task per connection with no invoke cap. `callbacks: Set<Promise>` is an aggregate active-callback cap, but `callbackQueueSize` accepts zero, negatives, `NaN` and `Infinity`; non-finite values can disable the intended bound. Handler promises can outlive `Promise.race`. | `readJson` buffers all chunks with no byte cap or request-body deadline. Response and callback each `JSON.stringify` without byte caps. | Runtime owns the server, callback abort controller and callback set. Callback fetch and server close lack independent finite deadlines. | P16b owns finite positive config validation, ingress/callback/stop deadlines, quotas and physical handlers. |

Worker count alone is never recorded as a queue bound. Count limits that already exist (Java,
Java-lite, Python, Go and JavaScript callback admission) should be reused by P16b rather than
replaced merely for API uniformity.

## What executes in P16a

The v3 `contractDefinitions` object is the only policy authority. Scenarios reference its
vocabularies, actor/action permissions, size operators, handler- and callback-behavior lifecycle
maps, exact wire outcomes, callback transport template and payload envelopes, identity and
cross-field rules, observation sets, and final-counter rule. Scenario response and callback
projections are executable fixture data: the validator requires them to equal the referenced
definition, so they cannot become an independent policy oracle.

`validate_saturation_wire_corpus.py` implements only schema mechanics, reference resolution and
generic operators. It checks finite configurations/deadlines, required fields, action/barrier
references, behavior/lifecycle compatibility, callback-URL requirement, exact response status,
content type, body/error/message/headers, exact callback method/URL/headers/payload, callback
required/attempted/delivered/count relations, P01 identity projections, observations and drained
final counters. Declarative maps also connect handler lifecycle to wire outcome and wire outcome
to callback envelope, while declarative action and cardinality rules require request-bearing
actions to name a request and require one dispatch-attempt entry per callback delivery attempt.
The embedded mutation suite includes the seven round-two contradictions plus broken-reference and
projection-drift probes. Standalone tests additionally reject the reviewed coordinated changes
across size relation, lifecycle, outcome, envelope, scenario kind and request/action role. Exact
per-kind action, actor, request-target, barrier and ordered outcome sequences anchor causal phases
such as capacity fill, stop, restart and control-plane redispatch. Per-kind barrier declarations
and initial states plus handler/callback producer sequences connect backend signals to those
action waits. Request order is canonical per kind, and backend plus expected collections must
follow it, binding dispatch metadata and ordered outcomes to the request IDs targeted by actions.
Each kind also fixes its dispatch-attempt sequence, including `[1, 2]` for control-plane retry and
`[2]` for the standalone second-attempt callback-delivery scenario.

Each language adapter runs that validator with a finite 10 s process deadline and then parses the
same JSON source. Java and Java-lite deserialize the complete typed model; Go disallows unknown
fields and uses pointer booleans for presence; Python and JavaScript presence-check every scenario
section and meaningfully project definitions, requests, actions, lifecycle references, exact
callback requests, observations, identities and counters. The adapters prove validator execution,
single-source parsability and typed/presence-safe consumption. They do not independently prove the
policy values and they do not execute a runtime.

These are **policy-schema and semantic-model assertions**, not timed runtime conformance. No
adapter in P16a starts a language runtime, fills a real queue, transfers payload bytes, measures
live counters, or proves a real deadline. P16b must interpret the action vocabulary against direct
runtime invocations; P17/P18 provide the owned lifecycle primitives required by those tests.

| Scenario | Current nonconformance owner |
| --- | --- |
| `success-drain`, `input-too-large`, `output-too-large`, `callback-saturated` | P16b in every runtime. |
| `handler-timeout`, `cancellation` | P17 for Python physical work, P18 for Java-lite ownership, P16b for wire/counters and other runtimes. |
| `health-under-saturation` | P17/P18 for their isolation/ownership prerequisites; P16b for cross-runtime conformance. |
| `stop-with-full-queue`, `restart` | P17 for Python shutdown truth, P18 for Java-lite lifecycle, P16b for Go/JavaScript/Java and common wire/counters. |
| `callback-delivery-exhausted` | P17 for Python callback executor/drain, P18 for Java-lite client/executor shutdown, P16b for delivery bounds/observation elsewhere. |
| `dispatch-retry-identity` | P16b for all runtime wire conformance; the control plane remains the sole redispatch owner. |
