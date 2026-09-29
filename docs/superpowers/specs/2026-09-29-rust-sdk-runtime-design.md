# Rust SDK runtime (`sdks/rust`) — design

Date: 2026-09-29. Sub-project 1 of 3 toward full parity with the Go SDK:

1. **This spec** — the `sdks/rust` runtime crate and its conformance tests.
2. `functions/rust/{word-stats,json-transform,roman-numeral,qr-code}` with Dockerfiles, fixture tests
   and `functions/contract-tests/run.sh` wiring.
3. Tooling: `tools/fn-init` Rust template, `scripts/sonar.sh` manifest path, docs.

Sub-projects 2 and 3 get their own spec/plan cycle.

## Goal

A Rust function SDK equivalent to `sdks/go`: a warm HTTP runtime that implements the wire contract
shared with the control plane and the other SDKs, and conforms to
`sdks/runtime-contract/README.md` (fail-fast admission, count/byte bounds, finite waits, resource
ownership until work is physically gone, bounded stop).

Success: `cargo test`, `cargo clippy -- -D warnings` and `cargo fmt --check` pass in `sdks/rust`,
including a runtime adapter that executes every scenario of `saturation-wire-corpus.json`.

## Stack

Same crates and major versions as `runtimes/watchdog`: `tokio`, `axum 0.8`, `reqwest 0.13`
(rustls, json), `serde`/`serde_json`, `tracing`, `prometheus-client 0.25`; plus `http-body-util`
for bounded body reads. No other runtime dependencies.

Rejected: hyper alone (re-implements routing and graceful shutdown axum already provides); a sync
server (incompatible with async handlers).

## Crate and public API

- Location `sdks/rust/`, package `nanofaas-sdk`, lib name `nanofaas`, edition 2024.
- Consumed by path dependency (as Go functions use `replace`); not published to crates.io.

```rust
Runtime::from_env()                        // PORT, EXECUTION_ID, TRACE_ID, CALLBACK_URL, FUNCTION_HANDLER, NANOFAAS_*
Runtime::with_settings(RuntimeSettings)
    .register(name, |ctx: Context, input: I| async move { /* -> Result<O, BoxError> */ })
    .start().await                         // binds PORT, stops on SIGTERM / ctrl-c
runtime.serve(listener, shutdown).await    // explicit listener and shutdown future (tests)

Context::execution_id() / trace_id()       // &str / Option<&str>
Context::metadata() / headers()            // the request body's `metadata` and `headers` objects
Context::is_cancelled()                    // true once the runtime gave up (timeout or disconnect)
Context::spawn_blocking(f)                 // blocking work that keeps the handler slot while it runs
HandlerResponse::new(output: serde_json::Value, status).header(k, v).encoding("base64")
```

- `I: DeserializeOwned`, `O: Serialize + Send + 'static`, `BoxError = Box<dyn Error + Send + Sync>`.
  `serde_json::Value` covers dynamic input/output.
- Input that does not deserialize into `I` is `400 {"error":"Malformed request body"}` before the
  handler starts; both reservations are released and no callback is sent (Go's malformed-JSON path).
- `HandlerResponse` is non-generic (`output: serde_json::Value`, `status_code: u16`,
  `headers: HashMap<String, String>`, `encoding: Option<String>`). Detection is nominal: the runtime
  downcasts the returned `O` through `Any`. Any other `O` is an implicit 200 with no extra headers.
- Every handler future runs inside a `tracing` span carrying `execution_id` and `trace_id`, so a
  handler's own log events carry them without an explicit API.
- Handler selection matches Go: `FUNCTION_HANDLER` if set; else the single registered handler; else
  500 `Handler not configured`.
- `RuntimeSettings` uses Go's environment names, defaults and production maxima
  (`sdks/go/README.md`); out-of-range or unparsable values fall back to the default. Durations
  accept milliseconds.

## `/invoke` flow

Admission is closed until `serve` starts (and again once stop begins), so `/invoke` answers
`503 RUNTIME_STOPPING` then. Otherwise the admission order matches
`sdks/go/nanofaas/http_invoke.go` so each failure yields the same code:

1. Non-POST → 405. Stopping → `503 RUNTIME_STOPPING`.
2. No execution id (neither `X-Execution-Id` nor `EXECUTION_ID`) → 400 `Execution ID not
   configured`; no resolvable handler → 500 `Handler not configured`.
3. Read-only saturation check → `429 RUNTIME_HANDLER_SATURATED`; callback payload cap below the
   256-byte terminal minimum → `429 RUNTIME_CALLBACK_SATURATED`.
4. `CallbackReservation` (one count + `max_callback_payload_bytes`) → else
   `429 RUNTIME_CALLBACK_SATURATED`.
5. `HandlerReservation` (one slot) → else 429 `RUNTIME_HANDLER_SATURATED`, or 503 if stopping.
6. Body read: `Content-Length` pre-check, `Limited` at `max_input_bytes`, `tokio::time::timeout`
   at `body_read_timeout` → `413 RUNTIME_INPUT_TOO_LARGE`, `408 RUNTIME_BODY_READ_TIMEOUT`, or 400.
   The read byte count is recorded on the `HandlerReservation`.

Runtime errors are `{"error":{"code":"...","message":"..."}}`; retryable ones add `Retry-After: 1`.
Codes and messages are copied from Go verbatim.

### Ownership

Both reservations are RAII guards: `Drop` releases idempotently and wakes waiters through
`tokio::sync::Notify`. Release therefore happens on success, error, panic, abort and stop without
explicit calls. `HandlerReservation` lives in an `Arc`, so `Context::spawn_blocking` can hold a
clone inside the blocking closure.

### Execution

The invocation runs in its own tokio task, not in axum's request future. The task owns both
reservations and sends the HTTP response back through a `oneshot`.

- **Timeout**: `tokio::time::timeout(handler_timeout, handler_future)`. On expiry the handler
  future is dropped (aborted), releasing its input and slot unless a `spawn_blocking` closure still
  holds the reservation. Then callback `HANDLER_TIMEOUT` + `504`.
- **Client disconnect**: axum drops its future and so the `oneshot::Receiver`; the task observes
  `Sender::closed()` in a `select!`, drops the handler future and sends callback
  `INVOCATION_CANCELLED` with no HTTP response.
- **Panic**: caught as the handler task's `JoinError` → callback `HANDLER_ERROR` + 500.
- **Handler `Err`**: logged with execution id → callback `HANDLER_ERROR` + 500 `Handler failed`.
- The runtime sets the cancellation flag before dropping the handler future, so
  `Context::is_cancelled()` is true in both cases. An async handler is dropped at that point
  anyway; the flag is for blocking closures started with `spawn_blocking`, which cannot be dropped.

### Output

Serialization goes through `serde_json::to_writer` into a `Vec` wrapper that errors once the limit
is exceeded, so the bound is exact and allocation stops at the limit. This replaces Go's
`bounded_json.go` pre-traversal: serde has no arbitrary pre-allocating marshaler to guard against.

- Output over `max_output_bytes` → callback `RUNTIME_OUTPUT_TOO_LARGE` + 500.
- Serialization failure → callback `OUTPUT_SERIALIZATION_ERROR` + 500.
- Envelope `status_code` outside 200–599 → callback `OUTPUT_SERIALIZATION_ERROR`
  (`Handler returned invalid statusCode: N`) + 500.
- Success: serialize the callback `InvocationResult` under `max_callback_payload_bytes`; enqueue it
  with its reservation, which shrinks to the serialized size; reply 200 or the envelope status.
  Envelope headers are filtered by the allow-list mirrored from `ResponseHeaderPolicy`
  (case-insensitive, one entry per name); values HTTP cannot carry (CR/LF) are dropped from the
  response and the callback alike. They are joined by `X-NanoFaaS-Function-Status: true` and, when
  set, `X-NanoFaaS-Encoding`. `Content-Type: application/json` unless the envelope set one.
- First invocation adds `X-Cold-Start: true` and `X-Init-Duration-Ms`, and increments
  `nanofaas_runtime_cold_starts_total`.
- Output bytes stay counted until the response body is written.

`InvocationResult` wire keys are camelCase (`success`, `output`, `error`, `statusCode`, `headers`,
`encoding`), matching `platform/common`.

## Callbacks

- Bounded `tokio::sync::mpsc` channel and 2 worker tasks. Real capacity is bounded by the
  reservations: `max_pending_callbacks` by count and `max_pending_callback_bytes` by bytes.
- Each job owns its `CallbackReservation`; dropping the job after delivery or exhaustion releases
  count, bytes and the serialized body.
- `reqwest` client, per-attempt timeout `callback_attempt_timeout`, delays 100/500/2000 ms with the
  last repeated up to `callback_max_attempts`. 2xx is success; 4xx other than 408/429 is final;
  everything else retries.
- URL `{base}/{executionId}:complete`, stripping a trailing `/...:complete` from the base as Go does.
  Headers `Content-Type: application/json`, `X-Trace-Id`, and `X-Dispatch-Attempt` echoed unchanged
  on every delivery attempt.
- Exhaustion: `nanofaas_runtime_callback_drops_total` + `tracing::warn!` with `execution_id`.

## Stop and restart

All within one `shutdown_timeout` budget:

1. Stop admission (new `/invoke` → 503).
2. axum graceful shutdown.
3. Wait for active handlers to reach zero.
4. Close the callback channel and let workers drain; at the deadline, cancel in-flight and queued
   deliveries, each counted in `nanofaas_runtime_callback_drops_total` with a warning log.

`serve` returns `Err(ShutdownTimedOut)` only when handler work was still running at the deadline;
cancelled callbacks are reported through the metric, not the return value.

`serve` may be called again on the same `Runtime` only when every counter is zero; otherwise it
returns `Err(NotDrained)`. A bind failure also shuts the dispatcher down, so no workers leak.

## Health and metrics

- `/health` → `{"status":"ok"}`, independent of invoke admission.
- `/metrics` (prometheus-client text format): `nanofaas_runtime_invocations_total{status}`
  (`success|error|timeout`), `nanofaas_runtime_handler_duration_seconds` (Prometheus default
  buckets, listed explicitly), `nanofaas_runtime_callback_drops_total`,
  `nanofaas_runtime_cold_starts_total`.

## Testing

Ordering uses `Notify`/`oneshot` barriers, never elapsed sleeps.

- **Unit**: settings parsing and clamping, header filter, callback URL, retry classification, the
  bounded writer.
- **Runtime** (in-crate `#[cfg(test)]` modules): a served runtime plus a fake axum callback
  server, with requests sent straight to the router (as the Go tests call `ServeHTTP`). Covers
  success, envelope, cold start, every error code, limits and admission order.
- **Public API** (`tests/public_api.rs`): only the public API, over real TCP.
- **Wire parity**: exact `X-NanoFaaS-Function-Status` / `X-NanoFaaS-Encoding` constants.
- **Saturation corpus**:
  - run `validate_saturation_wire_corpus.py` with a 10 s process deadline;
  - parse the corpus into typed structs with `#[serde(deny_unknown_fields)]`;
  - run a runtime adapter executing every scenario (success-drain, input-too-large,
    output-too-large, callback-saturated, handler-timeout, cancellation, health-under-saturation,
    stop-with-full-queue, restart, callback-delivery-exhausted, dispatch-retry-identity) and
    asserting responses, callback requests and drained final counters.
  - The adapter is an in-crate `#[cfg(test)]` module, like Go's in-package test, so it reads the
    counters (`activeHandlers`, `inputBytes`, `outputBytes`, `pendingCallbacks`,
    `pendingCallbackBytes`, `serializedCallbackBytes`) without a cargo feature or public API.
- **Quality**: `cargo clippy --all-targets --all-features -- -D warnings`, `cargo fmt --check`.

## Out of scope

Example functions, `contract-tests/run.sh`, fn-init template, Sonar wiring and repo-level docs
(sub-projects 2 and 3). The crate ships its own `README.md` (API, environment variables,
timeout/abort semantics, `spawn_blocking` guidance).
