# ADR 238: engine responsibilities and operational comments

Status: accepted implementation decision for issue #238. Source revision: ef856960e99a6c56b93be6a978965535f7a3eba7, 4 October 2026. This increment changes comments and documentation, not engine behavior.

## Decision and ownership

Retain SchedulerEngine and AttemptCoordinator. SchedulerEngine owns pending entries, the active scheduling index, admission accounting and the wake sequence. SchedulingIndex only orders tickets. Admission and index switching linearize under the engine gate; selection/claim publication rechecks removal and generation before submission. Capacity leases, provider calls, record inspection, submit and lifecycle notifications happen outside that gate. The outward lock edge is gate → capacity-registry entry; registry callbacks run after releasing the entry lock. Record → gate publication remains safe because the engine does not take record monitors under its gate.

AttemptCoordinator owns dispatch/retry/completion orchestration, not a second copy of execution state. ExecutionRecord owns outcome selection, retry state and its terminal guard; ExecutionStore owns retention/archive; FunctionCapacityRegistry owns generation and leases. Transport provides AttemptHandle outcome and physical-drain signals; observer records notifications after generation checks. Logical completion selects one answer, but physical drain determines when capacity can be released. Stale attempts cannot replace a selected outcome or act on a new generation. Future completion, observer callbacks and retry publication run outside the record monitor.

| Option | Assessment |
| --- | --- |
| Keep current boundaries | Preserves one owner and the existing race tests; selected. |
| Extract a pure helper | Permissible when a repeated, independently testable calculation appears. No such need justifies an additional abstraction here. |
| Transfer state to another component | Adds an ownership/synchronization boundary without a demonstrated benefit; rejected for this increment. |

SchedulerSwitchRaceTest verifies concurrent admission/switching; SchedulerEngineRemoveAllForGateDisciplineTest verifies removal callbacks outside the gate; SchedulerEngineDeadlineGuardRegressionTest guards expired work and cleanup. AttemptCoordinatorTest covers logical completion before physical drain (also across a strategy switch), stale/duplicate refusal, simultaneous HTTP/callback completion and retry ownership. GenerationLifecycleTest and CompletionMetricsGenerationFenceRegressionTest also cover removal/re-registration and late completion generation fences. RuntimeArchitectureTest and control-plane CoreArchitecture tests enforce dependency boundaries. These existing tests are the reason no speculative extraction or redundant state owner is introduced.

Further hot-path changes require comparable baseline/candidate campaigns through NanoLab: identical hardware/configuration, warm-up, at least five alternate replicas, throughput, latency percentiles, errors, allocation and memory. Freeze acceptance thresholds from baseline noise before examining the candidate. Do not reopen rejected #216 optimizations without new evidence. Tool migration/profile transfer belongs to #240; retain one profile implementation and transfer its characterization tests with its commit.

## Preserved historical evidence

Original build.gradle comments at the source revision recorded Reactor Netty 1.3.7 stalls in roughly 1/4 two-CPU calibration runs, 1.3.8-SNAPSHOT in 5/8, and none on 1.3.6. The independent keep-alive issue reactor/reactor-netty#4361 also affects 1.3.6; fixing it is not proof that the configured :enqueue load passes. Upgrade only after P07ConfiguredHttpCalibrationTest passes with two pinned CPUs (`taskset -c 0,1`, `--no-daemon`). The pin and this condition remain beside configuration.

The original native optimization comment recorded -O3 versus -Os at 9,251 versus 8,398 requests/s (+10%), startup 0.068 versus 0.173 s, idle 39 versus 121 MiB, image 397 versus 195 MB, and equal 744 MiB peaks under a heap ceiling. Under a 1 GB limit both reached about 3,490 requests/s. These are preserved historical observations from that comment, not newly reproduced measurements. The original collector comment recorded serial GC consuming 32% of wall time with pauses up to 1.9 s; G1 requires Oracle GraalVM on Linux and its licensing choice remains opt-in.

The native executable guard originated in [scheduler switching FINAL §7.3](../experiments/scheduler-switching-2026-09/FINAL.md) and issue #208: the release must not accidentally consume a shared library. Its scope remains the released control plane; the unused Java-lite native main target needs its own artifact decision. [TASK12](../experiments/scheduler-switching-2026-09/TASK12.md) and [retry backoff results](../experiments/retry-backoff-2026-09/RESULTS.md) preserve experiments and raw provenance. Raw outputs, checksums and pin values are unchanged.

The original nativeBuildMemory comment recorded a builder OOM/SIGKILL after 9m50s on a 12 GB VM also running k3s, the control plane and Prometheus. The builder heap budget remains configurable independently of the image heap.

AttemptCoordinator's former “step 4” references described the completed AttemptTransportAdapter extraction; its operational boundary and generation fence remain in the class documentation.

## Historical P16a inventory

The following is the original runtime-contract README inventory at the source revision. It describes that historical stage, not current conformance.

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
