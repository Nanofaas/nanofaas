# SDK runtime saturation and failure contracts

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

## Runtime execution and failure coverage

The historical P16a inventory is preserved in [ADR 238](../../docs/architecture/adr-238-engine-boundaries.md#historical-p16a-inventory). Its parser-only descriptions are not statements about the current runtimes. The saturation adapters now execute production runtime paths, including the existing callback saturation and exhaustion scenarios.

`failure-wire-corpus.json` is a companion to saturation/v3. Its `contractDefinitions` supply the four named lifecycles; `config` supplies deadlines, callback attempts and stable execution/dispatch/trace identities; `finalCounters` requires physical drain. The same validator validates both documents. Test adapters execute the fixture explicitly with real callback servers returning 503 or holding their responses. They assert HTTP outcome, callback payload/identity, attempt count and physical resource release before shutdown. The control plane remains the sole redispatch owner.

| Runtime | Envelope failure | Callback rejection / I/O deadline | Ingress deadline |
| --- | --- | --- | --- |
| Java | Throwing output getter through InvokeController | Real JDK transport and CallbackDispatcher | Blocking servlet stream through RuntimePayloadLimitFilter; stream released |
| Java-lite | Throwing output getter through InvokeHandler | Real JDK transport and InvokeHandler on HttpServer | Blocking HttpExchange stream; TCP limitation below |
| Python | Unserializable HandlerResponse output | Real HTTP callback endpoint through runtime.invoke | Pending ASGI body stream |
| Go | Unserializable HandlerResponse output | Real HTTP callback endpoint through Runtime.Handler | Blocking request body through Runtime.Handler |
| JavaScript | Circular HandlerResponse output | Real HTTP callback endpoint through createRuntime | Partial TCP upload with no body completion |
| Rust | Invalid envelope status through production encoding rejection | Real HTTP callback endpoint through TestRuntime | Pending body through production router |

Rust's public envelope contains only serde_json::Value, so an arbitrary unserializable envelope output cannot be constructed. Its invalid-status case proves the real rejection/callback/drain path; existing typed-output serialization tests cover serialization outside an envelope. No production fault-injection API is introduced.

Known differences are explicit fixture data, validated and applied by the named adapter: Python and JavaScript classify output encoding exceptions as `RUNTIME_OUTPUT_TOO_LARGE`; Java-lite classifies its normalization exception as `HANDLER_ERROR`; JavaScript uses `RUNTIME_BODY_TIMEOUT` for ingress. These are preserved public behaviors, not claims of cross-runtime code equality. Reclassification requires a separate compatibility change.

A real partial TCP upload to Java-lite revealed that closing the JDK HttpServer request stream can block the deadline path. The blocking-stream test proves its runtime logic but does not establish a finite TCP ingress deadline. This remains an explicit conformance gap requiring a transport fix; it is not silently skipped or counted as TCP conformance.

Run schema/mutation tests with `python -m pytest sdks/runtime-contract`; runtime tests run with each SDK's normal test command. Java and Java-lite use SharedFailureWireCorpusTest; Python test_failure_wire_corpus.py; Go TestSharedFailureWireCorpus; JavaScript runtime-failure-corpus.test.ts; Rust failure_corpus_tests. Resources without a corresponding SDK aggregate counter are checked through their owner (handler/callback reservations or closed ingress stream), not invented gauges.
