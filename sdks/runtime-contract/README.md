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
terminal callback. Saturation is `429 RUNTIME_CALLBACK_SATURATED`; a single over-limit payload is
`413 RUNTIME_PAYLOAD_TOO_LARGE`. A request accepted by the listener after stop begins is
`503 RUNTIME_STOPPING`; after listener closure, connection refusal is the equivalent observable
outcome. These errors use `{"error":{"code":"...","message":"..."}}`; retryable admission
errors include `Retry-After: 1`.

All waits are finite and configurable. `HANDLER_TIMEOUT` is an attempt-level `504`, not P01's
per-waiter `408`: it may produce an error callback and then follow the control plane's configured
retry policy. Runtime retries preserve the supplied `X-Execution-Id` and `X-Dispatch-Attempt`;
SDKs do not mint a new identity, redispatch autonomously, or weaken caller idempotency. A direct
caller should likewise retry with the same identifiers.

Every acquired count and byte reservation remains owned until the represented work and retained
payload are physically gone. Release is mandatory on success, error, cancellation and stop.
Timing out an HTTP waiter does not prove a non-cooperative handler stopped. Callback delivery
exhaustion occurs after the invoke response, so it has no second HTTP response (`httpStatus: 0` in
the corpus); it must be visible through a bounded-cardinality failure metric and structured log,
and must release callback count, bytes and serialized body. Health remains independent enough to
report while invoke admission is saturated.

The corpus `timeoutMs` values are deterministic test deadlines, not production defaults. Test
adapters must coordinate blocked work with latches, events, contexts or abort signals; elapsed
sleep is not ordering evidence.

## Retained-resource inventory at P16a

“Bound” below means an enforced retention bound, not a worker-count assumption.

| Runtime | Retained queues/tasks and actual count bounds | Input/output/callback bytes | Owner and release/stop path | Gap owner |
| --- | --- | --- | --- | --- |
| Java | `HandlerExecutor` uses a virtual-thread-per-task executor: no admission/queue count cap. `CallbackDispatcher` has 2 workers by default plus an `ArrayBlockingQueue(128)`, so up to 130 active/queued callback jobs; worker count is configurable, queue capacity is fixed. | Spring request decoding has no SDK byte cap. Handler output is normalized to a `JsonNode` before callback admission; queued jobs retain it and `CallbackClient` later serializes another `byte[]`. No single-payload or pending-callback-byte cap. | Spring context owns both executors through `@PreDestroy`. Callback shutdown waits 5 s then interrupts; handler shutdown interrupts without an observable drain. JDK callback connect/read limits are 5 s/10 s and delivery retries at most 3 attempts. | P16b for admission/bytes and physical handler ownership; P18 verifies the Spring HTTP-client ownership pattern. |
| Java-lite | `HttpServer` uses an unreferenced virtual-thread-per-task executor, and each invoke starts another virtual thread: neither has admission count bounds. Callback executor defaults to 2 workers plus `ArrayBlockingQueue(128)` (130 active/queued), both configurable. | Request decode and response serialization have no SDK byte cap. Callback jobs retain `InvocationResult`; `CallbackClient` serializes only after dequeue, with no payload or aggregate byte cap. | `NanofaasRuntime` owns the server only partially; `stop()` calls callback `shutdownNow()` and server stop, but does not retain/close the server executor or HTTP client and does not await callback drain. Callback connect timeout is 5 s; request/read timeout is absent. | P18 for exact executor/client/start-stop ownership; P16b for quotas and finite callback request timeout. |
| Python | Each request is an ASGI task. Sync handlers use `asyncio.to_thread`: default worker count is not a queue bound and its executor queue is unbounded. Async handlers are tasks. `BoundedSemaphore(128)` caps admitted callback coroutines in aggregate; Starlette background tasks and `to_thread(requests.post)` add work to externally owned/default executors. | FastAPI request JSON, handler output, response and callback dictionaries have no byte caps. A callback slot is acquired before background-task registration, but not by bytes and not before input/output materialization. | The ASGI server/event loop owns request/background tasks; Python owns no explicit handler/callback executor. Callback slot releases when its coroutine exits; HTTP attempts use a 5 s timeout and at most 3 attempts. Lifespan has no stop/drain cleanup. A timed-out sync wait can release request accounting while its thread still owns input/output. | P17 for physical work, executor isolation and shutdown; P16b for byte/direct-admission limits. |
| Go | `net/http` creates request goroutines without an SDK admission cap. Each invoke creates another handler goroutine and a buffered result channel; timeout does not stop a non-cooperative handler. Callback dispatcher has exactly `queueSize` buffered jobs plus `workerCount` active jobs (defaults 128 + 2). | Streaming JSON decode has no max body. Handler result/channel and callback jobs retain input/output objects; each active callback retains one marshalled body across retries. No payload or callback-byte cap. | `Runtime.Start` owns the server and dispatcher. Context cancellation closes the server and cancels callback HTTP work; dispatcher shutdown can return immediately on the already-cancelled context before workers are observed drained. Callback client timeout is 5 s with at most 3 attempts. | P16b for admission/bytes, physical handlers, and bounded observable stop. |
| JavaScript | Node owns one async request task per connection with no invoke count cap. `callbacks: Set<Promise>` is an enforced aggregate active-callback count cap (default 128); it is not a worker queue. Handler promises have no count cap and can outlive `Promise.race` if they ignore abort. | `readJson` retains all body chunks then concatenates/parses them. Response and callback each call `JSON.stringify`; no single-payload or pending-callback-byte cap. Callback count admission occurs before callback serialization. | Runtime state owns the server, callback `AbortController` and callback promise set. `stop()` aborts and awaits callbacks, then awaits server close, but neither callback fetch nor server close has an independent finite deadline. Callback failures are metric/log observable. | P16b for invoke/byte caps, physical handlers and finite callback/stop deadlines. |

Worker count alone is never recorded as a queue bound. Count limits that already exist (Java,
Java-lite, Python, Go and JavaScript callback admission) should be reused by P16b rather than
replaced merely for API uniformity.

## Corpus execution boundary

The five adapters parse the corpus and execute its embedded runner invariants now, which makes
schema or policy incompleteness fail in every toolchain. The corpus includes saturation, payload, timeout, post-response callback failure
and stop cases with finite deadlines and explicit release sets. P16a does **not** claim the runtime
quota paths conform yet. P16b must drive the same cases through real direct runtime invocations and
prove live count/bytes return to zero; P17/P18 supply their owned lifecycle primitives first where
listed above.

The adapter assertions prove only that every language can parse the **single JSON policy source**
and that its cases are complete, unique, finite and internally consistent. They are not runtime
conformance assertions: the expected status/error/release values are deliberately not copied into
the five adapters. Current nonconformance is assigned as follows:

| Corpus case | Current runtime gap | Implementation owner |
| --- | --- | --- |
| `callback-saturated` | Every runtime can still accept handler work and then drop/refuse its callback without returning the corpus rejection. Existing callback count bounds are reusable but insufficient. | P16b in all runtimes. |
| `payload-too-large` | No runtime enforces both input/output single-payload bytes and pending callback bytes before avoidable large copies. | P16b in all runtimes. |
| `handler-timeout` | Direct response bodies and physical-work accounting are not uniformly conforming; non-cooperative work can outlive the HTTP timeout. | P17 for Python thread/task ownership; P18 for Java-lite owned executors; P16b for wire/direct-invocation conformance and the other runtimes. |
| `callback-delivery-exhausted` | Delivery timeout, failure observation and count/byte/body release are not all finite and observable in every runtime. | P17 for Python executor/drain; P18 for Java-lite client/executor shutdown; P16b for quotas, wire observation and the other runtimes. |
| `runtime-stopping` | Accepted requests are not uniformly fenced with `503`, and several runtimes cannot prove bounded physical drain. | P17 for Python shutdown truth; P18 for Java-lite start/stop ownership; P16b for admission fencing and the other runtimes. |
