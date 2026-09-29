# nanofaas Rust Function SDK

Rust SDK for authoring nanofaas functions: a warm HTTP runtime (`/invoke`, `/health`, `/metrics`)
with the same wire contract, limits and callback behavior as the Go, Java, Python and JavaScript
SDKs. Conformance to `sdks/runtime-contract/README.md` is checked by executing every scenario of
`saturation-wire-corpus.json` in `cargo test`.

## Usage

The crate is not published; depend on it by path. The package is `nanofaas-sdk`, the library is
`nanofaas`:

```toml
[dependencies]
nanofaas-sdk = { path = "../../sdks/rust" }
serde = { version = "1", features = ["derive"] }
tokio = { version = "1", features = ["rt-multi-thread", "macros"] }
```

```rust
use nanofaas::{BoxError, Context, Runtime};
use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
struct Input {
    text: String,
}

#[derive(Serialize)]
struct Output {
    words: usize,
}

async fn word_count(_ctx: Context, input: Input) -> Result<Output, BoxError> {
    Ok(Output { words: input.text.split_whitespace().count() })
}

#[tokio::main]
async fn main() {
    let stopped = Runtime::from_env().register("word-count", word_count).start().await;
    if let Err(error) = stopped {
        eprintln!("{error}");
        // Exit now: returning from main would wait for stuck spawn_blocking work (see Stopping).
        std::process::exit(1);
    }
}
```

- A handler takes a `Context` and any `Deserialize` input, and returns any `Serialize` output.
  `serde_json::Value` works for dynamic input or output.
- Input that does not deserialize into the handler's type is rejected with `400` before the
  handler starts.
- Return `HandlerResponse::new(value, status).header(name, value).encoding("base64")` to choose
  the HTTP status and headers. Only the headers allowed by `ResponseHeaderPolicy` in
  `platform/common` reach the caller.
- `Context` exposes `execution_id()`, `trace_id()` and the request's `metadata()` and `headers()`.
  Every handler runs inside a `tracing` span carrying `execution_id` and `trace_id`; install any
  subscriber to see them.
- `start()` binds `0.0.0.0:$PORT` and serves until SIGTERM or Ctrl-C.
- The runtime starts without system CA certificates (a `scratch` image): plain HTTP callbacks,
  the in-cluster case, work, and HTTPS callbacks are counted as drops. Ship a CA bundle, as the
  `fn-init` Rust template does, when the function or its callbacks use HTTPS.

## Timeouts and blocking work

When `NANOFAAS_HANDLER_TIMEOUT` expires, or the client disconnects, the runtime reports the
outcome (`504 HANDLER_TIMEOUT`, or the `INVOCATION_CANCELLED` callback) and **drops the handler's
future**. An async handler therefore stops at its next `.await`, and its handler slot and input
bytes are released then.

A blocking section cannot be dropped. Run CPU-bound or blocking work with
`ctx.spawn_blocking(|| ...)`: the closure keeps the invocation's handler slot reserved until it
really returns, so the runtime never admits more work than `NANOFAAS_MAX_CONCURRENT_HANDLERS`.
Inside the closure, poll `ctx.is_cancelled()` to return early. Blocking directly inside an async
handler stalls a tokio worker thread and delays the timeout itself.

## Environment variables

Same names, defaults and maxima as the Go SDK. Durations are milliseconds. Zero, unparsable or
above-maximum values fall back to the default.

| Variable | Default | Maximum |
| --- | --- | --- |
| `PORT` | `8080` | |
| `EXECUTION_ID`, `TRACE_ID` | unset | fallback identity when the request carries no header |
| `CALLBACK_URL` | unset | control-plane callback base URL |
| `FUNCTION_HANDLER` | unset | handler to use when several are registered |
| `NANOFAAS_HANDLER_TIMEOUT` | `30000` | one hour |
| `NANOFAAS_MAX_CONCURRENT_HANDLERS` | `32` | `4096` |
| `NANOFAAS_MAX_INPUT_BYTES` | `1048576` | 64 MiB |
| `NANOFAAS_MAX_OUTPUT_BYTES` | `1048576` | 64 MiB |
| `NANOFAAS_MAX_PENDING_CALLBACKS` | `128` | `65536` |
| `NANOFAAS_MAX_PENDING_CALLBACK_BYTES` | `16777216` | 1 GiB |
| `NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES` | `2097152` | 64 MiB, and at most the pending-bytes cap |
| `NANOFAAS_BODY_READ_TIMEOUT` | `5000` | five minutes |
| `NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT` | `5000` | five minutes |
| `NANOFAAS_CALLBACK_MAX_ATTEMPTS` | `3` | `10` |
| `NANOFAAS_SHUTDOWN_TIMEOUT` | `5000` | five minutes |

The runtime reserves one full callback payload before starting a handler. Effective callback
concurrency is therefore bounded by both `NANOFAAS_MAX_PENDING_CALLBACKS` and
`NANOFAAS_MAX_PENDING_CALLBACK_BYTES / NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES`.

## Stopping

On SIGTERM the runtime stops admitting invocations (new ones get `503 RUNTIME_STOPPING`), lets
running handlers finish and queued callbacks drain, all within `NANOFAAS_SHUTDOWN_TIMEOUT`.
Callbacks still undelivered at the deadline are cancelled and counted in
`nanofaas_runtime_callback_drops_total`. `start()` returns `Error::ShutdownTimedOut` if handler
work was still running at the deadline.

Returning from `main` drops the tokio runtime, which waits for every `spawn_blocking` closure to
finish, so a closure that never returns would keep the process alive past the deadline until the
orchestrator kills it. End the process with `std::process::exit` on `Error::ShutdownTimedOut`, as
the example above does.

## Connections

Like the Go SDK's `http.Server`, every wait on a connection is bounded by
`NANOFAAS_BODY_READ_TIMEOUT`: a client that does not finish sending its request headers or body in
time, or that accepts no response bytes for that long, is disconnected, and an idle keep-alive
connection is closed after the same interval. A response keeps its bytes counted in the runtime's
output budget until the server has handed its last frame to the connection.

## Development

```bash
cd sdks/rust
cargo test                                   # unit, conformance corpus (needs python3), public API
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```
