# Rust SDK Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `sdks/rust`, a Rust function SDK whose warm HTTP runtime matches the Go SDK's wire contract and passes every scenario of the shared saturation corpus.

**Architecture:** An axum router serves `/invoke`, `/health` and `/metrics`. Each admitted invocation owns two RAII reservations: a handler slot with its input bytes, and one callback's count and bytes. It runs in its own tokio task, so a timeout or client disconnect drops the handler future and still sends the terminal callback. Callbacks go through a bounded two-worker dispatcher that drains on stop until `NANOFAAS_SHUTDOWN_TIMEOUT`.

**Tech Stack:** Rust 2024 edition, tokio 1, axum 0.8, reqwest 0.13 (rustls), serde/serde_json, prometheus-client 0.25, tracing. These are the same crates and versions as `runtimes/watchdog`.

**Spec:** `docs/superpowers/specs/2026-09-29-rust-sdk-runtime-design.md`

**Provenance:** Every code block below was compiled and tested before this plan was written. The crate was rebuilt task by task, and each state passed `cargo test`. The final state also passed `cargo clippy --all-targets -- -D warnings` and `cargo fmt --check`, and ten consecutive full runs pinned to two CPUs (`taskset -c 0,1`) without a failure. Type the code as given. If a step's result differs from the one stated, stop and investigate instead of adjusting the test.

## Global Constraints

- Work on the branch `feat/rust-sdk-runtime` (it already holds the spec commit). Commit once per task, with the trailer lines shown in each commit step.
- `cargo` is installed in `~/.cargo/bin`, which may be missing from a non-interactive shell's `PATH`. Run `export PATH="$HOME/.cargo/bin:$PATH"` first if `cargo` is not found. `rustfmt` and `clippy` are installed.
- Rust edition 2024 with `rust-version = "1.85"`; the toolchain on this machine is 1.98.1.
- The only allowed dependencies are the ones in Task 1's `Cargo.toml`. Do not add crates.
- Seed `sdks/rust/Cargo.lock` from `runtimes/watchdog/Cargo.lock` (Task 1), so versions match the vetted watchdog set. Cargo prunes the unused entries.
- Wire codes and messages are copied verbatim from `sdks/go/nanofaas/http_invoke.go`: `RUNTIME_STOPPING` / `Runtime is stopping`, `RUNTIME_HANDLER_SATURATED` / `Runtime handler capacity exhausted`, `RUNTIME_CALLBACK_SATURATED` / `Runtime callback capacity exhausted`, `RUNTIME_INPUT_TOO_LARGE` / `Runtime input exceeds configured byte limit`, `RUNTIME_BODY_READ_TIMEOUT` / `Runtime request body read timed out`, `RUNTIME_OUTPUT_TOO_LARGE` / `Runtime output exceeds configured byte limit`, `OUTPUT_SERIALIZATION_ERROR` / `Handler output could not be serialized`, `HANDLER_ERROR` / `Handler failed`, `HANDLER_TIMEOUT` / `Handler exceeded configured timeout`, `INVOCATION_CANCELLED` / `Invocation cancelled`. Legacy 400/500 bodies are `{"error":"Execution ID not configured"}`, `{"error":"Handler not configured"}` and `{"error":"Malformed request body"}`.
- Environment variable names, defaults and maxima are the Go SDK's (`sdks/go/README.md`). Durations are milliseconds.
- Callback JSON keys are camelCase (`success`, `output`, `error`, `statusCode`, `headers`, `encoding`). `output` and `error` are always present, even when `null`.
- Code comments and docs are in English. Use rustfmt's defaults (4-space indentation).
- Tests never use elapsed sleeps as ordering evidence. Ordering comes from `Notify`, `oneshot`, `watch` or barriers.
- Every task ends with `cargo test` green. Until Task 8 wires the modules together, dead-code warnings are expected; Task 10 makes clippy warning-free.

## Review Focus

Five inputs the spec implies but none of the per-component tests would otherwise exercise, most likely to bite first. Each one is pinned by a test in the owning task:

1. **A body exactly at `NANOFAAS_MAX_INPUT_BYTES`** must be accepted (off-by-one at the limit). Pinned by Task 8: `accepts_a_body_exactly_at_the_input_limit`.
2. **A request without `input`** must reach a `serde_json::Value` handler as `null`, not fail with 400. Pinned by Task 8: `a_missing_input_reaches_the_handler_as_null`.
3. **An envelope header value HTTP cannot carry** (CR/LF) must be dropped from both the response and the callback, never fail the response or split a header. Pinned by Task 8: `envelope_header_values_http_cannot_carry_are_dropped_everywhere`.
4. **A burst far above `NANOFAAS_MAX_CONCURRENT_HANDLERS`** must yield only 200 or 429 and drain every counter to zero. Pinned by Task 8: `a_burst_above_the_handler_cap_gets_only_ok_or_429_and_drains`.
5. **No `CALLBACK_URL`** (a local run) must still answer 200 and count the undeliverable callback as a drop instead of leaking it. Pinned by Task 10: `without_a_callback_url_invocations_answer_and_count_a_drop`.

## File Structure

All paths are under `sdks/rust/`.

| File | Responsibility |
| --- | --- |
| `Cargo.toml`, `Cargo.lock`, `.gitignore` | Crate manifest (`nanofaas-sdk`, library `nanofaas`), pinned lockfile, ignored `target/` |
| `src/lib.rs` | Module list and the public re-exports |
| `src/settings.rs` | `RuntimeSettings`: environment parsing, defaults and maxima, identity resolution |
| `src/types.rs` | Wire types: `HandlerResponse`, callback `InvocationResult`, request body, header allow-list |
| `src/bounded.rs` | JSON encoding that fails as soon as it passes a byte limit |
| `src/limits.rs` | Handler admission, input/output byte accounting, `CountedBody` |
| `src/callback.rs` | Callback URL, retry policy, one delivery with cancellation |
| `src/dispatcher.rs` | Callback reservations, bounded queue, two workers, drain-then-cancel close |
| `src/metrics.rs` | The Go SDK's four Prometheus metrics |
| `src/context.rs` | `Context` handed to handlers, including `spawn_blocking` |
| `src/handler.rs` | Type erasure of typed handlers; nominal envelope detection |
| `src/runtime.rs` | `Runtime`: registration, router, `serve`/`start`, stop and restart |
| `src/invoke.rs` | `/invoke`: admission order, the handler's task, terminal callback, response |
| `src/test_support.rs` | Test-only fixtures: fake callback endpoint, served test runtime |
| `src/corpus_tests.rs` | Test-only execution of `sdks/runtime-contract/saturation-wire-corpus.json` |
| `tests/public_api.rs` | The public API over real TCP |
| `README.md` | Usage, timeout semantics, environment variables |

---
### Task 1: Crate scaffold and settings

**Files:**
- Create: `sdks/rust/Cargo.toml`, `sdks/rust/.gitignore`, `sdks/rust/Cargo.lock` (seeded), `sdks/rust/src/lib.rs`, `sdks/rust/src/settings.rs`

**Interfaces:**
- Consumes: nothing.
- Produces: `pub struct RuntimeSettings` (all fields `pub`: `port: u16`, `execution_id`, `trace_id`, `callback_url`, `function_handler: Option<String>`, `handler_timeout`, `body_read_timeout`, `callback_attempt_timeout`, `shutdown_timeout: Duration`, `max_concurrent_handlers`, `max_input_bytes`, `max_output_bytes`, `max_pending_callbacks`, `max_pending_callback_bytes`, `max_callback_payload_bytes`, `callback_max_attempts: usize`), `impl Default`, `pub fn from_env() -> Self`, `pub(crate) fn normalized(self) -> Self`, `pub(crate) fn resolve_identity(&self, header_execution_id: Option<&str>, header_trace_id: Option<&str>) -> (Option<String>, Option<String>)`.

- [ ] **Step 1: Create the crate skeleton**

`sdks/rust/Cargo.toml`:

```toml
[package]
name = "nanofaas-sdk"
version = "0.1.0"
edition = "2024"
rust-version = "1.85"
description = "nanofaas function SDK: warm HTTP runtime for Rust handlers"
license = "MIT"
publish = false

[lib]
name = "nanofaas"

[dependencies]
axum = { version = "0.8", default-features = false, features = ["http1", "tokio"] }
bytes = "1"
http-body = "1"
http-body-util = "0.1"
prometheus-client = "0.25"
reqwest = { version = "0.13", default-features = false, features = ["rustls"] }
serde = { version = "1", features = ["derive"] }
serde_json = { version = "1", features = ["raw_value"] }
tokio = { version = "1", features = ["rt", "net", "time", "sync", "signal", "macros"] }
tracing = "0.1"

[dev-dependencies]
tokio = { version = "1", features = ["rt-multi-thread", "macros"] }
tower = { version = "0.5", features = ["util"] }
tracing-subscriber = { version = "0.3", features = ["json"] }
```

`sdks/rust/.gitignore`:

```text
/target
```

Seed the lockfile from the watchdog so dependency versions match the set the repo already vets:

```bash
cp /home/michele/Documenti/nanofaas/runtimes/watchdog/Cargo.lock /home/michele/Documenti/nanofaas/sdks/rust/Cargo.lock
```

- [ ] **Step 2: Write the failing tests**

Create `sdks/rust/src/settings.rs` containing only its test module:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn from(vars: &[(&str, &str)]) -> RuntimeSettings {
        let vars: HashMap<String, String> = vars
            .iter()
            .map(|(name, value)| (name.to_string(), value.to_string()))
            .collect();
        RuntimeSettings::from_lookup(|name| vars.get(name).cloned())
    }

    #[test]
    fn empty_environment_yields_go_defaults() {
        let settings = from(&[]);
        assert_eq!(settings, RuntimeSettings::default());
        assert_eq!(settings.port, 8080);
        assert_eq!(settings.handler_timeout, Duration::from_secs(30));
        assert_eq!(settings.max_pending_callbacks, 128);
        assert_eq!(settings.max_callback_payload_bytes, 2 * 1024 * 1024);
    }

    #[test]
    fn reads_explicit_values() {
        let settings = from(&[
            ("PORT", "9000"),
            ("EXECUTION_ID", " exec-1 "),
            ("TRACE_ID", "trace-1"),
            ("CALLBACK_URL", "http://cp:8080/v1/internal/executions"),
            ("FUNCTION_HANDLER", "echo"),
            ("NANOFAAS_HANDLER_TIMEOUT", "1500"),
            ("NANOFAAS_MAX_CONCURRENT_HANDLERS", "4"),
            ("NANOFAAS_MAX_INPUT_BYTES", "2048"),
            ("NANOFAAS_MAX_OUTPUT_BYTES", "4096"),
            ("NANOFAAS_MAX_PENDING_CALLBACKS", "8"),
            ("NANOFAAS_MAX_PENDING_CALLBACK_BYTES", "65536"),
            ("NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES", "1024"),
            ("NANOFAAS_BODY_READ_TIMEOUT", "250"),
            ("NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT", "300"),
            ("NANOFAAS_CALLBACK_MAX_ATTEMPTS", "5"),
            ("NANOFAAS_SHUTDOWN_TIMEOUT", "700"),
        ]);
        assert_eq!(settings.port, 9000);
        assert_eq!(settings.execution_id.as_deref(), Some("exec-1"));
        assert_eq!(settings.trace_id.as_deref(), Some("trace-1"));
        assert_eq!(
            settings.callback_url.as_deref(),
            Some("http://cp:8080/v1/internal/executions")
        );
        assert_eq!(settings.function_handler.as_deref(), Some("echo"));
        assert_eq!(settings.handler_timeout, Duration::from_millis(1500));
        assert_eq!(settings.max_concurrent_handlers, 4);
        assert_eq!(settings.max_input_bytes, 2048);
        assert_eq!(settings.max_output_bytes, 4096);
        assert_eq!(settings.max_pending_callbacks, 8);
        assert_eq!(settings.max_pending_callback_bytes, 65536);
        assert_eq!(settings.max_callback_payload_bytes, 1024);
        assert_eq!(settings.body_read_timeout, Duration::from_millis(250));
        assert_eq!(
            settings.callback_attempt_timeout,
            Duration::from_millis(300)
        );
        assert_eq!(settings.callback_max_attempts, 5);
        assert_eq!(settings.shutdown_timeout, Duration::from_millis(700));
    }

    #[test]
    fn rejects_zero_unparsable_and_above_maximum_values() {
        let settings = from(&[
            ("PORT", "not-a-port"),
            ("NANOFAAS_HANDLER_TIMEOUT", "0"),
            ("NANOFAAS_MAX_CONCURRENT_HANDLERS", "4097"),
            ("NANOFAAS_MAX_INPUT_BYTES", "-1"),
            ("NANOFAAS_MAX_OUTPUT_BYTES", "67108865"),
            ("NANOFAAS_MAX_PENDING_CALLBACKS", "65537"),
            ("NANOFAAS_MAX_PENDING_CALLBACK_BYTES", "1073741825"),
            ("NANOFAAS_BODY_READ_TIMEOUT", "300001"),
            ("NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT", "5s"),
            ("NANOFAAS_CALLBACK_MAX_ATTEMPTS", "11"),
            ("NANOFAAS_SHUTDOWN_TIMEOUT", ""),
        ]);
        assert_eq!(settings, RuntimeSettings::default());
    }

    #[test]
    fn caps_callback_payload_at_pending_callback_bytes() {
        let settings = RuntimeSettings {
            max_pending_callback_bytes: 1024,
            max_callback_payload_bytes: 4096,
            ..RuntimeSettings::default()
        }
        .normalized();
        assert_eq!(settings.max_callback_payload_bytes, 1024);
    }

    #[test]
    fn resolve_identity_prefers_trimmed_headers_over_environment() {
        let settings = RuntimeSettings {
            execution_id: Some("env-exec".into()),
            trace_id: Some("env-trace".into()),
            ..RuntimeSettings::default()
        };
        assert_eq!(
            settings.resolve_identity(Some(" hdr-exec "), Some("hdr-trace")),
            (Some("hdr-exec".into()), Some("hdr-trace".into()))
        );
        assert_eq!(
            settings.resolve_identity(Some("   "), None),
            (Some("env-exec".into()), Some("env-trace".into()))
        );
        assert_eq!(
            RuntimeSettings::default().resolve_identity(None, None),
            (None, None)
        );
    }
}
```

Create `sdks/rust/src/lib.rs`:

```rust
//! nanofaas function SDK for Rust: a warm HTTP runtime serving `/invoke`, `/health` and
//! `/metrics` under the admission, byte-limit and callback rules of
//! `sdks/runtime-contract/README.md`.

mod settings;

pub use settings::RuntimeSettings;
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: compile errors: `cannot find type RuntimeSettings` and similar.

- [ ] **Step 4: Write the implementation**

Insert at the top of `sdks/rust/src/settings.rs`, above `#[cfg(test)]`:

```rust
use std::time::Duration;

const MAXIMUM_HANDLER_TIMEOUT: Duration = Duration::from_secs(3600);
const MAXIMUM_CONCURRENT_HANDLERS: usize = 4096;
const MAXIMUM_PAYLOAD_BYTES: usize = 64 * 1024 * 1024;
const MAXIMUM_PENDING_CALLBACKS: usize = 65_536;
const MAXIMUM_PENDING_CALLBACK_BYTES: usize = 1024 * 1024 * 1024;
const MAXIMUM_IO_TIMEOUT: Duration = Duration::from_secs(300);
const MAXIMUM_CALLBACK_ATTEMPTS: usize = 10;

/// Runtime identity and limits. `from_env` reads the same variables, defaults and maxima as the
/// Go SDK (`sdks/go/README.md`); zero, unparsable or above-maximum values fall back to defaults.
/// Durations are read from the environment as milliseconds.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RuntimeSettings {
    pub port: u16,
    pub execution_id: Option<String>,
    pub trace_id: Option<String>,
    pub callback_url: Option<String>,
    pub function_handler: Option<String>,
    pub handler_timeout: Duration,
    pub max_concurrent_handlers: usize,
    pub max_input_bytes: usize,
    pub max_output_bytes: usize,
    pub max_pending_callbacks: usize,
    pub max_pending_callback_bytes: usize,
    pub max_callback_payload_bytes: usize,
    pub body_read_timeout: Duration,
    pub callback_attempt_timeout: Duration,
    pub callback_max_attempts: usize,
    pub shutdown_timeout: Duration,
}

impl Default for RuntimeSettings {
    fn default() -> Self {
        Self {
            port: 8080,
            execution_id: None,
            trace_id: None,
            callback_url: None,
            function_handler: None,
            handler_timeout: Duration::from_secs(30),
            max_concurrent_handlers: 32,
            max_input_bytes: 1024 * 1024,
            max_output_bytes: 1024 * 1024,
            max_pending_callbacks: 128,
            max_pending_callback_bytes: 16 * 1024 * 1024,
            max_callback_payload_bytes: 2 * 1024 * 1024,
            body_read_timeout: Duration::from_secs(5),
            callback_attempt_timeout: Duration::from_secs(5),
            callback_max_attempts: 3,
            shutdown_timeout: Duration::from_secs(5),
        }
    }
}

impl RuntimeSettings {
    pub fn from_env() -> Self {
        Self::from_lookup(|name| std::env::var(name).ok())
    }

    fn from_lookup(lookup: impl Fn(&str) -> Option<String>) -> Self {
        let text = |name: &str| {
            lookup(name)
                .map(|value| value.trim().to_owned())
                .filter(|value| !value.is_empty())
        };
        let count = |name: &str| text(name).and_then(|v| v.parse().ok()).unwrap_or(0);
        let millis = |name: &str| {
            Duration::from_millis(text(name).and_then(|v| v.parse().ok()).unwrap_or(0))
        };
        Self {
            port: text("PORT").and_then(|v| v.parse().ok()).unwrap_or(0),
            execution_id: text("EXECUTION_ID"),
            trace_id: text("TRACE_ID"),
            callback_url: text("CALLBACK_URL"),
            function_handler: text("FUNCTION_HANDLER"),
            handler_timeout: millis("NANOFAAS_HANDLER_TIMEOUT"),
            max_concurrent_handlers: count("NANOFAAS_MAX_CONCURRENT_HANDLERS"),
            max_input_bytes: count("NANOFAAS_MAX_INPUT_BYTES"),
            max_output_bytes: count("NANOFAAS_MAX_OUTPUT_BYTES"),
            max_pending_callbacks: count("NANOFAAS_MAX_PENDING_CALLBACKS"),
            max_pending_callback_bytes: count("NANOFAAS_MAX_PENDING_CALLBACK_BYTES"),
            max_callback_payload_bytes: count("NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES"),
            body_read_timeout: millis("NANOFAAS_BODY_READ_TIMEOUT"),
            callback_attempt_timeout: millis("NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT"),
            callback_max_attempts: count("NANOFAAS_CALLBACK_MAX_ATTEMPTS"),
            shutdown_timeout: millis("NANOFAAS_SHUTDOWN_TIMEOUT"),
        }
        .normalized()
    }

    /// Replaces zero or above-maximum values with defaults, then caps the single-callback payload
    /// at the aggregate callback budget, as the Go SDK does for programmatic settings too.
    pub(crate) fn normalized(self) -> Self {
        let d = Self::default();
        let port = if self.port == 0 { d.port } else { self.port };
        let max_pending_callback_bytes = bounded(
            self.max_pending_callback_bytes,
            d.max_pending_callback_bytes,
            MAXIMUM_PENDING_CALLBACK_BYTES,
        );
        let max_callback_payload_bytes = bounded(
            self.max_callback_payload_bytes,
            d.max_callback_payload_bytes,
            MAXIMUM_PAYLOAD_BYTES,
        )
        .min(max_pending_callback_bytes);
        Self {
            port,
            handler_timeout: bounded(
                self.handler_timeout,
                d.handler_timeout,
                MAXIMUM_HANDLER_TIMEOUT,
            ),
            max_concurrent_handlers: bounded(
                self.max_concurrent_handlers,
                d.max_concurrent_handlers,
                MAXIMUM_CONCURRENT_HANDLERS,
            ),
            max_input_bytes: bounded(
                self.max_input_bytes,
                d.max_input_bytes,
                MAXIMUM_PAYLOAD_BYTES,
            ),
            max_output_bytes: bounded(
                self.max_output_bytes,
                d.max_output_bytes,
                MAXIMUM_PAYLOAD_BYTES,
            ),
            max_pending_callbacks: bounded(
                self.max_pending_callbacks,
                d.max_pending_callbacks,
                MAXIMUM_PENDING_CALLBACKS,
            ),
            max_pending_callback_bytes,
            max_callback_payload_bytes,
            body_read_timeout: bounded(
                self.body_read_timeout,
                d.body_read_timeout,
                MAXIMUM_IO_TIMEOUT,
            ),
            callback_attempt_timeout: bounded(
                self.callback_attempt_timeout,
                d.callback_attempt_timeout,
                MAXIMUM_IO_TIMEOUT,
            ),
            callback_max_attempts: bounded(
                self.callback_max_attempts,
                d.callback_max_attempts,
                MAXIMUM_CALLBACK_ATTEMPTS,
            ),
            shutdown_timeout: bounded(
                self.shutdown_timeout,
                d.shutdown_timeout,
                MAXIMUM_IO_TIMEOUT,
            ),
            ..self
        }
    }

    /// Execution and trace ids for one request: a non-blank header wins over the environment.
    pub(crate) fn resolve_identity(
        &self,
        header_execution_id: Option<&str>,
        header_trace_id: Option<&str>,
    ) -> (Option<String>, Option<String>) {
        let pick = |header: Option<&str>, fallback: &Option<String>| {
            header
                .map(str::trim)
                .filter(|value| !value.is_empty())
                .map(str::to_owned)
                .or_else(|| fallback.clone())
        };
        (
            pick(header_execution_id, &self.execution_id),
            pick(header_trace_id, &self.trace_id),
        )
    }
}

fn bounded<T: PartialOrd + Default>(value: T, fallback: T, maximum: T) -> T {
    if value <= T::default() || value > maximum {
        fallback
    } else {
        value
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: `test result: ok. 5 passed`. Cargo prunes `Cargo.lock` to this crate's dependency set on this first build.

- [ ] **Step 6: Commit**

Stage, run the GitNexus change analysis CLAUDE.md requires (new Rust files typically report `changed_count: 0`; a `partial` or `error` flag is not a pass — rerun), then commit:

```bash
cd /home/michele/Documenti/nanofaas
git add sdks/rust/Cargo.toml sdks/rust/Cargo.lock sdks/rust/.gitignore sdks/rust/src/lib.rs sdks/rust/src/settings.rs
GITNEXUS="$(python3 -c "import json;print(json.load(open('.gitnexus/meta.json'))['runnerIdentity']['invokedArtifact']['path'].split('/dist/')[0])")"
node --input-type=module -e "const {LocalBackend}=await import('$GITNEXUS/dist/mcp/local/local-backend.js'); console.log(JSON.stringify(await new LocalBackend().callTool('detect_changes',{scope:'staged',repo:'nanofaas'}))); process.exit(0)"
git commit -F - <<'EOF'
Add the Rust SDK crate and its runtime settings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019F6VX47yLJ3prsE2mDcaU1
EOF
```

---

### Task 2: Wire types and bounded JSON encoding

**Files:**
- Create: `sdks/rust/src/types.rs`, `sdks/rust/src/bounded.rs`
- Modify: `sdks/rust/src/lib.rs`

**Interfaces:**
- Consumes: nothing.
- Produces: in `types.rs`: `pub(crate) const FUNCTION_STATUS_HEADER`, `ENCODING_HEADER: &str` (lowercase), `pub struct HandlerResponse` with `pub fn new(output: serde_json::Value, status_code: u16)`, `pub fn header(self, name, value) -> Self`, `pub fn encoding(self, encoding) -> Self`, `pub(crate) fn into_parts(self) -> (Value, u16, Vec<(String, String)>, Option<String>)`; `pub(crate) fn is_status_code_valid(u16) -> bool`; `pub(crate) fn filter_allowed_headers(Vec<(String, String)>) -> Vec<(String, String)>`; `pub(crate) struct WireRequest<'a> { input: Option<&'a RawValue>, metadata, headers: HashMap<String, String> }`; `pub(crate) struct ErrorInfo { code: &'static str, message: String }`; `pub(crate) struct InvocationResult<'a>` with `success(&RawValue)`, `envelope(&RawValue, u16, &[(String, String)], Option<&str>)`, `failure(&'static str, impl Into<String>)`. In `bounded.rs`: `pub(crate) enum EncodeError { TooLarge, Serialization }`, `pub(crate) fn to_vec_bounded<T: Serialize + ?Sized>(&T, limit: usize) -> Result<Vec<u8>, EncodeError>`.

- [ ] **Step 1: Write the failing tests**

Create `sdks/rust/src/types.rs` containing only its test module:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn raw(json: &str) -> &RawValue {
        serde_json::from_str(json).unwrap()
    }

    #[test]
    fn plain_success_omits_envelope_fields_but_keeps_null_error() {
        let encoded = serde_json::to_value(InvocationResult::success(raw(r#"{"a":1}"#))).unwrap();
        assert_eq!(
            encoded,
            json!({"success": true, "output": {"a": 1}, "error": null})
        );
    }

    #[test]
    fn failure_keeps_null_output() {
        let encoded =
            serde_json::to_value(InvocationResult::failure("HANDLER_ERROR", "Handler failed"))
                .unwrap();
        assert_eq!(
            encoded,
            json!({"success": false, "output": null,
                   "error": {"code": "HANDLER_ERROR", "message": "Handler failed"}})
        );
    }

    #[test]
    fn envelope_is_always_success_and_uses_camel_case_keys() {
        let headers = vec![("Content-Type".to_string(), "image/png".to_string())];
        let encoded = serde_json::to_value(InvocationResult::envelope(
            raw(r#""aGk=""#),
            503,
            &headers,
            Some("base64"),
        ))
        .unwrap();
        assert_eq!(
            encoded,
            json!({"success": true, "output": "aGk=", "error": null, "statusCode": 503,
                   "headers": {"Content-Type": "image/png"}, "encoding": "base64"})
        );
    }

    #[test]
    fn status_code_validity_is_200_to_599() {
        assert!(!is_status_code_valid(199));
        assert!(is_status_code_valid(200));
        assert!(is_status_code_valid(599));
        assert!(!is_status_code_valid(600));
    }

    #[test]
    fn filter_keeps_allowed_headers_in_original_casing_and_drops_the_rest() {
        let filtered = filter_allowed_headers(vec![
            ("Content-Type".into(), "text/plain".into()),
            ("X-Execution-Id".into(), "spoofed".into()),
            ("X-NanoFaaS-Function-Status".into(), "false".into()),
            ("ETag".into(), "\"v1\"".into()),
        ]);
        assert_eq!(
            filtered,
            vec![
                ("Content-Type".to_string(), "text/plain".to_string()),
                ("ETag".to_string(), "\"v1\"".to_string()),
            ]
        );
    }

    #[test]
    fn filter_keeps_only_the_first_of_case_insensitive_duplicates() {
        let filtered = filter_allowed_headers(vec![
            ("Content-Type".into(), "a".into()),
            ("content-type".into(), "b".into()),
        ]);
        assert_eq!(
            filtered,
            vec![("Content-Type".to_string(), "a".to_string())]
        );
    }

    #[test]
    fn wire_request_defaults_missing_fields() {
        let request: WireRequest = serde_json::from_str(r#"{"input":{"x":1}}"#).unwrap();
        assert_eq!(request.input.unwrap().get(), r#"{"x":1}"#);
        assert!(request.metadata.is_empty() && request.headers.is_empty());
        let empty: WireRequest = serde_json::from_str("{}").unwrap();
        assert!(empty.input.is_none());
    }
}
```

Create `sdks/rust/src/bounded.rs` containing only its test module:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[test]
    fn encodes_a_value_that_fits_exactly() {
        let encoded = to_vec_bounded(&"abc", 5).unwrap();
        assert_eq!(encoded, br#""abc""#);
    }

    #[test]
    fn rejects_a_value_one_byte_over_the_limit() {
        assert_eq!(to_vec_bounded(&"abc", 4), Err(EncodeError::TooLarge));
    }

    #[test]
    fn rejects_a_large_value_without_buffering_it() {
        let huge = "x".repeat(10 * 1024 * 1024);
        assert_eq!(to_vec_bounded(&huge, 1024), Err(EncodeError::TooLarge));
    }

    #[test]
    fn reports_values_json_cannot_represent_as_serialization_errors() {
        let mut map = HashMap::new();
        map.insert((1, 2), 3);
        assert_eq!(to_vec_bounded(&map, 1024), Err(EncodeError::Serialization));
    }
}
```

Add the new modules to `sdks/rust/src/lib.rs`, which becomes:

```rust
//! nanofaas function SDK for Rust: a warm HTTP runtime serving `/invoke`, `/health` and
//! `/metrics` under the admission, byte-limit and callback rules of
//! `sdks/runtime-contract/README.md`.

mod bounded;
mod settings;
mod types;

pub use settings::RuntimeSettings;
pub use types::HandlerResponse;
```


- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: compile errors: `cannot find type InvocationResult`, `cannot find function to_vec_bounded` and similar.

- [ ] **Step 3: Write the implementation**

Insert at the top of `sdks/rust/src/types.rs`, above `#[cfg(test)]`:

```rust
use std::collections::{BTreeMap, HashMap};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use serde_json::value::RawValue;

/// Marker telling the control plane that the handler chose the status and headers. These names
/// are the frozen wire contract shared with platform/common and every other SDK.
pub(crate) const FUNCTION_STATUS_HEADER: &str = "x-nanofaas-function-status";
pub(crate) const ENCODING_HEADER: &str = "x-nanofaas-encoding";

/// Mirrors ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS in platform/common. Keep the two in sync.
const ALLOWED_RESPONSE_HEADERS: [&str; 8] = [
    "content-type",
    "location",
    "cache-control",
    "etag",
    "content-disposition",
    "content-language",
    "retry-after",
    "vary",
];

/// Optional envelope a handler returns instead of a plain value, to choose the HTTP status,
/// response headers and the encoding marker. Detection is nominal: a handler returning any other
/// type gets an implicit 200 with no extra headers.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct HandlerResponse {
    output: Value,
    status_code: u16,
    headers: Vec<(String, String)>,
    encoding: Option<String>,
}

impl HandlerResponse {
    pub fn new(output: Value, status_code: u16) -> Self {
        Self {
            output,
            status_code,
            headers: Vec::new(),
            encoding: None,
        }
    }

    /// Adds a response header. Only the allow-listed names reach the caller; for a name repeated
    /// case-insensitively, the first one wins.
    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.headers.push((name.into(), value.into()));
        self
    }

    /// Marks how `output` is encoded, e.g. `"base64"` for binary bodies.
    pub fn encoding(mut self, encoding: impl Into<String>) -> Self {
        self.encoding = Some(encoding.into());
        self
    }

    pub(crate) fn into_parts(self) -> (Value, u16, Vec<(String, String)>, Option<String>) {
        (self.output, self.status_code, self.headers, self.encoding)
    }
}

pub(crate) fn is_status_code_valid(status_code: u16) -> bool {
    (200..=599).contains(&status_code)
}

/// Keeps allow-listed headers only, at most one per case-insensitive name (the first seen), in
/// their original casing.
pub(crate) fn filter_allowed_headers(raw: Vec<(String, String)>) -> Vec<(String, String)> {
    let mut filtered: Vec<(String, String)> = Vec::new();
    for (name, value) in raw {
        let lower = name.to_ascii_lowercase();
        let allowed = ALLOWED_RESPONSE_HEADERS.contains(&lower.as_str());
        let duplicate = filtered
            .iter()
            .any(|(kept, _)| kept.eq_ignore_ascii_case(&name));
        if allowed && !duplicate {
            filtered.push((name, value));
        }
    }
    filtered
}

/// The `/invoke` request body. `input` stays raw so it is deserialized once, straight into the
/// handler's own input type.
#[derive(Deserialize)]
pub(crate) struct WireRequest<'a> {
    #[serde(borrow, default)]
    pub input: Option<&'a RawValue>,
    #[serde(default)]
    pub metadata: HashMap<String, String>,
    #[serde(default)]
    pub headers: HashMap<String, String>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub(crate) struct ErrorInfo {
    pub code: &'static str,
    pub message: String,
}

/// The callback body. Keys are camelCase to match platform/common's InvocationResult: a mismatch
/// is silent, the field just vanishes on the control plane. `output` and `error` are always
/// present; the envelope fields only when set.
#[derive(Debug, Serialize)]
pub(crate) struct InvocationResult<'a> {
    pub success: bool,
    pub output: Option<&'a RawValue>,
    pub error: Option<ErrorInfo>,
    #[serde(rename = "statusCode", skip_serializing_if = "Option::is_none")]
    pub status_code: Option<u16>,
    #[serde(skip_serializing_if = "BTreeMap::is_empty")]
    pub headers: BTreeMap<&'a str, &'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub encoding: Option<&'a str>,
}

impl<'a> InvocationResult<'a> {
    pub fn success(output: &'a RawValue) -> Self {
        Self {
            success: true,
            output: Some(output),
            error: None,
            status_code: None,
            headers: BTreeMap::new(),
            encoding: None,
        }
    }

    /// A function-decided result. `success` is always true: the control plane retries on
    /// `!success`, so a response the function chose must never be retried, 5xx included.
    pub fn envelope(
        output: &'a RawValue,
        status_code: u16,
        headers: &'a [(String, String)],
        encoding: Option<&'a str>,
    ) -> Self {
        Self {
            status_code: Some(status_code),
            headers: headers
                .iter()
                .map(|(name, value)| (name.as_str(), value.as_str()))
                .collect(),
            encoding,
            ..Self::success(output)
        }
    }

    pub fn failure(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            success: false,
            output: None,
            error: Some(ErrorInfo {
                code,
                message: message.into(),
            }),
            status_code: None,
            headers: BTreeMap::new(),
            encoding: None,
        }
    }
}
```

Insert at the top of `sdks/rust/src/bounded.rs`, above `#[cfg(test)]`:

```rust
use std::io;

use serde::Serialize;

/// Why a value could not be encoded within its byte limit.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum EncodeError {
    TooLarge,
    Serialization,
}

/// Serializes `value` to JSON, failing as soon as the encoding would exceed `limit` bytes: the
/// buffer never holds more than `limit` bytes, whatever the value's size.
pub(crate) fn to_vec_bounded<T: Serialize + ?Sized>(
    value: &T,
    limit: usize,
) -> Result<Vec<u8>, EncodeError> {
    let mut writer = LimitedWriter {
        buffer: Vec::new(),
        limit,
    };
    match serde_json::to_writer(&mut writer, value) {
        Ok(()) => Ok(writer.buffer),
        Err(error) if error.is_io() => Err(EncodeError::TooLarge),
        Err(_) => Err(EncodeError::Serialization),
    }
}

struct LimitedWriter {
    buffer: Vec<u8>,
    limit: usize,
}

impl io::Write for LimitedWriter {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        if data.len() > self.limit - self.buffer.len() {
            return Err(io::Error::other("encoded value exceeds its byte limit"));
        }
        self.buffer.extend_from_slice(data);
        Ok(data.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
```


- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: `test result: ok. 16 passed` for the library. Warnings about unused items are expected until Task 8 wires every module into `/invoke`.

- [ ] **Step 5: Commit**

Stage, run the GitNexus change analysis CLAUDE.md requires (new Rust files typically report `changed_count: 0`; a `partial` or `error` flag is not a pass — rerun), then commit:

```bash
cd /home/michele/Documenti/nanofaas
git add sdks/rust/src/lib.rs sdks/rust/src/types.rs sdks/rust/src/bounded.rs
GITNEXUS="$(python3 -c "import json;print(json.load(open('.gitnexus/meta.json'))['runnerIdentity']['invokedArtifact']['path'].split('/dist/')[0])")"
node --input-type=module -e "const {LocalBackend}=await import('$GITNEXUS/dist/mcp/local/local-backend.js'); console.log(JSON.stringify(await new LocalBackend().callTool('detect_changes',{scope:'staged',repo:'nanofaas'}))); process.exit(0)"
git commit -F - <<'EOF'
Add the Rust SDK wire types and bounded JSON encoding

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019F6VX47yLJ3prsE2mDcaU1
EOF
```

---

### Task 3: Handler admission and byte accounting

**Files:**
- Create: `sdks/rust/src/limits.rs`
- Modify: `sdks/rust/src/lib.rs`

**Interfaces:**
- Consumes: nothing.
- Produces: `pub(crate) struct LimitSnapshot { active_handlers, input_bytes, output_bytes: usize, accepting: bool }`; `pub(crate) struct Limits` with `new(max_handlers) -> Arc<Self>` (admission starts closed), `snapshot()`, `set_accepting(bool)`, `try_reserve_handler(self: &Arc<Self>) -> Option<HandlerReservation>`, `retain_output(self: &Arc<Self>, bytes) -> OutputGuard`, `async wait_until_idle()`; `HandlerReservation::retain_input(&self, bytes)` (released on drop); `OutputGuard` (released on drop); `CountedBody::new(Bytes, OutputGuard)` implementing `http_body::Body`.

- [ ] **Step 1: Write the failing tests**

Create `sdks/rust/src/limits.rs` containing only its test module:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use http_body_util::BodyExt;

    fn open(max_handlers: usize) -> Arc<Limits> {
        let limits = Limits::new(max_handlers);
        limits.set_accepting(true);
        limits
    }

    #[test]
    fn reserves_up_to_the_handler_cap_and_releases_on_drop() {
        let limits = open(2);
        let first = limits.try_reserve_handler().unwrap();
        let _second = limits.try_reserve_handler().unwrap();
        assert!(limits.try_reserve_handler().is_none());
        drop(first);
        assert_eq!(limits.snapshot().active_handlers, 1);
        assert!(limits.try_reserve_handler().is_some());
    }

    #[test]
    fn refuses_admission_while_not_accepting() {
        let limits = Limits::new(1);
        assert!(limits.try_reserve_handler().is_none());
        limits.set_accepting(true);
        assert!(limits.try_reserve_handler().is_some());
    }

    #[test]
    fn input_bytes_stay_counted_until_the_reservation_drops() {
        let limits = open(1);
        let reservation = Arc::new(limits.try_reserve_handler().unwrap());
        reservation.retain_input(128);
        let clone = Arc::clone(&reservation);
        drop(reservation);
        assert_eq!(limits.snapshot().input_bytes, 128);
        drop(clone);
        assert_eq!(
            limits.snapshot(),
            LimitSnapshot {
                accepting: true,
                ..Default::default()
            }
        );
    }

    #[tokio::test]
    async fn wait_until_idle_resolves_when_the_last_handler_releases() {
        let limits = open(1);
        let reservation = limits.try_reserve_handler().unwrap();
        let waiter = tokio::spawn({
            let limits = Arc::clone(&limits);
            async move { limits.wait_until_idle().await }
        });
        tokio::task::yield_now().await;
        assert!(!waiter.is_finished());
        drop(reservation);
        waiter.await.unwrap();
    }

    #[tokio::test]
    async fn counted_body_keeps_output_bytes_until_dropped() {
        let limits = open(1);
        let body = CountedBody::new(Bytes::from_static(b"hello"), limits.retain_output(5));
        assert_eq!(body.size_hint().exact(), Some(5));
        assert_eq!(limits.snapshot().output_bytes, 5);
        let collected = body.collect().await.unwrap().to_bytes();
        assert_eq!(&collected[..], b"hello");
        assert_eq!(limits.snapshot().output_bytes, 0);
    }
}
```

Add the new modules to `sdks/rust/src/lib.rs`, which becomes:

```rust
//! nanofaas function SDK for Rust: a warm HTTP runtime serving `/invoke`, `/health` and
//! `/metrics` under the admission, byte-limit and callback rules of
//! `sdks/runtime-contract/README.md`.

mod bounded;
mod limits;
mod settings;
mod types;

pub use settings::RuntimeSettings;
pub use types::HandlerResponse;
```


- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: compile errors: `cannot find type Limits` and similar.

- [ ] **Step 3: Write the implementation**

Insert at the top of `sdks/rust/src/limits.rs`, above `#[cfg(test)]`:

```rust
use std::convert::Infallible;
use std::pin::Pin;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::task::Poll;

use bytes::Bytes;
use http_body::{Body, Frame, SizeHint};
use tokio::sync::Notify;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct LimitSnapshot {
    pub active_handlers: usize,
    pub input_bytes: usize,
    pub output_bytes: usize,
    pub accepting: bool,
}

/// Handler admission and the input/output bytes the runtime retains. Every count is owned by a
/// guard that returns it on drop, so release happens on success, error, panic and abort alike.
pub(crate) struct Limits {
    max_handlers: usize,
    state: Mutex<LimitSnapshot>,
    changed: Notify,
}

impl Limits {
    /// Admission starts closed; `serve` opens it.
    pub fn new(max_handlers: usize) -> Arc<Self> {
        Arc::new(Self {
            max_handlers,
            state: Mutex::new(LimitSnapshot::default()),
            changed: Notify::new(),
        })
    }

    pub fn snapshot(&self) -> LimitSnapshot {
        *self.lock()
    }

    pub fn set_accepting(&self, accepting: bool) {
        self.update(|state| state.accepting = accepting);
    }

    pub fn try_reserve_handler(self: &Arc<Self>) -> Option<HandlerReservation> {
        let mut state = self.lock();
        if !state.accepting || state.active_handlers >= self.max_handlers {
            return None;
        }
        state.active_handlers += 1;
        drop(state);
        self.changed.notify_waiters();
        Some(HandlerReservation {
            limits: Arc::clone(self),
            input_bytes: AtomicUsize::new(0),
        })
    }

    pub fn retain_output(self: &Arc<Self>, bytes: usize) -> OutputGuard {
        self.update(|state| state.output_bytes += bytes);
        OutputGuard {
            limits: Arc::clone(self),
            bytes,
        }
    }

    /// Resolves once no handler work is physically running.
    pub async fn wait_until_idle(&self) {
        loop {
            let notified = self.changed.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            if self.snapshot().active_handlers == 0 {
                return;
            }
            notified.await;
        }
    }

    fn update(&self, change: impl FnOnce(&mut LimitSnapshot)) {
        change(&mut self.lock());
        self.changed.notify_waiters();
    }

    fn lock(&self) -> MutexGuard<'_, LimitSnapshot> {
        self.state
            .lock()
            .expect("limits lock is never held across a panic")
    }
}

/// One handler slot plus the input bytes it retains; both return when the last owner drops it.
pub(crate) struct HandlerReservation {
    limits: Arc<Limits>,
    input_bytes: AtomicUsize,
}

impl HandlerReservation {
    pub fn retain_input(&self, bytes: usize) {
        self.input_bytes.fetch_add(bytes, Ordering::Relaxed);
        self.limits.update(|state| state.input_bytes += bytes);
    }
}

impl Drop for HandlerReservation {
    fn drop(&mut self) {
        let bytes = *self.input_bytes.get_mut();
        self.limits.update(|state| {
            state.active_handlers -= 1;
            state.input_bytes -= bytes;
        });
    }
}

pub(crate) struct OutputGuard {
    limits: Arc<Limits>,
    bytes: usize,
}

impl Drop for OutputGuard {
    fn drop(&mut self) {
        let bytes = self.bytes;
        self.limits.update(|state| state.output_bytes -= bytes);
    }
}

/// A response body that keeps its bytes counted until the server drops it after writing.
pub(crate) struct CountedBody {
    data: Option<Bytes>,
    _guard: OutputGuard,
}

impl CountedBody {
    pub fn new(data: Bytes, guard: OutputGuard) -> Self {
        Self {
            data: Some(data),
            _guard: guard,
        }
    }
}

impl Body for CountedBody {
    type Data = Bytes;
    type Error = Infallible;

    fn poll_frame(
        self: Pin<&mut Self>,
        _cx: &mut std::task::Context<'_>,
    ) -> Poll<Option<Result<Frame<Bytes>, Infallible>>> {
        Poll::Ready(self.get_mut().data.take().map(|data| Ok(Frame::data(data))))
    }

    fn is_end_stream(&self) -> bool {
        self.data.is_none()
    }

    fn size_hint(&self) -> SizeHint {
        SizeHint::with_exact(self.data.as_ref().map_or(0, |data| data.len() as u64))
    }
}
```


- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: `test result: ok. 21 passed` for the library. Warnings about unused items are expected until Task 8 wires every module into `/invoke`.

- [ ] **Step 5: Commit**

Stage, run the GitNexus change analysis CLAUDE.md requires (new Rust files typically report `changed_count: 0`; a `partial` or `error` flag is not a pass — rerun), then commit:

```bash
cd /home/michele/Documenti/nanofaas
git add sdks/rust/src/lib.rs sdks/rust/src/limits.rs
GITNEXUS="$(python3 -c "import json;print(json.load(open('.gitnexus/meta.json'))['runnerIdentity']['invokedArtifact']['path'].split('/dist/')[0])")"
node --input-type=module -e "const {LocalBackend}=await import('$GITNEXUS/dist/mcp/local/local-backend.js'); console.log(JSON.stringify(await new LocalBackend().callTool('detect_changes',{scope:'staged',repo:'nanofaas'}))); process.exit(0)"
git commit -F - <<'EOF'
Add handler admission and byte accounting to the Rust SDK

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019F6VX47yLJ3prsE2mDcaU1
EOF
```

---

### Task 4: Callback client and the fake control plane

**Files:**
- Create: `sdks/rust/src/test_support.rs` (test-only), `sdks/rust/src/callback.rs`
- Modify: `sdks/rust/src/lib.rs`

**Interfaces:**
- Consumes: nothing.
- Produces: in `callback.rs`: `pub(crate) struct Identity { execution_id: String, trace_id: Option<String>, dispatch_attempt: Option<String> }`, `pub(crate) struct Callback { identity: Identity, body: Bytes }`, `retry_delays(max_attempts) -> Vec<Duration>`, `callback_url(base, execution_id) -> String`, `CallbackClient::new(base_url: Option<String>, attempt_timeout: Duration, max_attempts: usize)`, `async fn deliver(&self, &Callback, cancel: &mut watch::Receiver<bool>) -> bool`, field `pub(crate) retry_delays: Vec<Duration>`. In `test_support.rs`: `enum Reply { Status(u16), HoldThen(u16) }`, `struct RecordedCallback { method, path: String, headers: HeaderMap, body: Value, status: u16 }`, `FakeCallbackServer { url }` with `accepting()`, `start(Fn(&RecordedCallback) -> Reply)`, `received()`, `release_held()`, `async wait_for(count, matches) -> Vec<RecordedCallback>`.

- [ ] **Step 1: Add the fake control-plane callback endpoint**

This fixture is test-only (`#[cfg(test)] mod test_support;`). Tasks 5, 7, 8 and 9 use it. Create `sdks/rust/src/test_support.rs`:

```rust
//! Shared test fixtures: a fake control-plane callback endpoint.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::Router;
use axum::body::Bytes;
use axum::extract::{Request, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::any;
use http_body_util::BodyExt;
use tokio::net::TcpListener;
use tokio::sync::{Notify, watch};

/// How the fake answers one callback attempt.
#[derive(Clone, Copy, Debug)]
pub(crate) enum Reply {
    Status(u16),
    /// Records the attempt, then waits for `release_held` before answering.
    HoldThen(u16),
}

#[derive(Clone, Debug)]
pub(crate) struct RecordedCallback {
    pub method: String,
    pub path: String,
    pub headers: HeaderMap,
    pub body: serde_json::Value,
    pub status: u16,
}

type Responder = dyn Fn(&RecordedCallback) -> Reply + Send + Sync;

struct FakeState {
    received: Mutex<Vec<RecordedCallback>>,
    changed: Notify,
    respond: Box<Responder>,
    release: watch::Sender<bool>,
}

pub(crate) struct FakeCallbackServer {
    pub url: String,
    state: Arc<FakeState>,
}

impl FakeCallbackServer {
    /// Answers every attempt with 204.
    pub async fn accepting() -> Self {
        Self::start(|_| Reply::Status(204)).await
    }

    pub async fn start(
        respond: impl Fn(&RecordedCallback) -> Reply + Send + Sync + 'static,
    ) -> Self {
        let state = Arc::new(FakeState {
            received: Mutex::new(Vec::new()),
            changed: Notify::new(),
            respond: Box::new(respond),
            release: watch::channel(false).0,
        });
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let app = Router::new()
            .fallback(any(receive))
            .with_state(Arc::clone(&state));
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        Self { url, state }
    }

    pub fn received(&self) -> Vec<RecordedCallback> {
        self.state.received.lock().unwrap().clone()
    }

    pub fn release_held(&self) {
        self.state.release.send_replace(true);
    }

    /// Waits until at least `count` recorded attempts satisfy `matches`, or panics after 5 s.
    pub async fn wait_for(
        &self,
        count: usize,
        matches: impl Fn(&RecordedCallback) -> bool,
    ) -> Vec<RecordedCallback> {
        let wait = async {
            loop {
                let notified = self.state.changed.notified();
                tokio::pin!(notified);
                notified.as_mut().enable();
                let found: Vec<_> = self.received().into_iter().filter(|r| matches(r)).collect();
                if found.len() >= count {
                    return found;
                }
                notified.await;
            }
        };
        tokio::time::timeout(Duration::from_secs(5), wait)
            .await
            .expect("expected callbacks never arrived")
    }
}

async fn receive(State(state): State<Arc<FakeState>>, request: Request) -> StatusCode {
    let (parts, body) = request.into_parts();
    let body: Bytes = body.collect().await.unwrap().to_bytes();
    let mut recorded = RecordedCallback {
        method: parts.method.to_string(),
        path: parts.uri.path().to_string(),
        headers: parts.headers,
        body: serde_json::from_slice(&body).unwrap_or(serde_json::Value::Null),
        status: 0,
    };
    let reply = (state.respond)(&recorded);
    let status = match reply {
        Reply::Status(status) | Reply::HoldThen(status) => status,
    };
    recorded.status = status;
    state.received.lock().unwrap().push(recorded);
    state.changed.notify_waiters();
    if let Reply::HoldThen(_) = reply {
        let mut release = state.release.subscribe();
        let _ = release.wait_for(|released| *released).await;
    }
    StatusCode::from_u16(status).unwrap()
}
```

- [ ] **Step 2: Write the failing tests**

Create `sdks/rust/src/callback.rs` containing only its test module:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{FakeCallbackServer, Reply};
    use serde_json::json;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn callback(execution_id: &str) -> Callback {
        Callback {
            identity: Identity {
                execution_id: execution_id.into(),
                trace_id: Some("trace-1".into()),
                dispatch_attempt: Some("2".into()),
            },
            body: Bytes::from_static(br#"{"success":true}"#),
        }
    }

    fn client(url: &str, attempts: usize) -> CallbackClient {
        let mut client = CallbackClient::new(Some(url.into()), Duration::from_secs(1), attempts);
        client.retry_delays = vec![Duration::ZERO; attempts];
        client
    }

    fn never_cancelled() -> watch::Receiver<bool> {
        watch::channel(false).1
    }

    #[test]
    fn builds_the_execution_complete_url() {
        assert_eq!(
            callback_url("http://cp:8080/v1/internal/executions/", "exec-1"),
            "http://cp:8080/v1/internal/executions/exec-1:complete"
        );
        assert_eq!(
            callback_url("http://cp/v1/internal/executions/old:complete", "exec-2"),
            "http://cp/v1/internal/executions/exec-2:complete"
        );
    }

    #[test]
    fn retry_delays_repeat_the_last_base_delay() {
        assert_eq!(retry_delays(1), vec![Duration::from_millis(100)]);
        let millis: Vec<u128> = retry_delays(5).iter().map(Duration::as_millis).collect();
        assert_eq!(millis, vec![100, 500, 2000, 2000, 2000]);
    }

    #[test]
    fn classifies_statuses() {
        assert_eq!(classify(204), AttemptOutcome::Delivered);
        assert_eq!(classify(400), AttemptOutcome::Permanent);
        assert_eq!(classify(408), AttemptOutcome::Retry);
        assert_eq!(classify(429), AttemptOutcome::Retry);
        assert_eq!(classify(503), AttemptOutcome::Retry);
    }

    #[tokio::test]
    async fn delivers_with_identity_headers() {
        let server = FakeCallbackServer::accepting().await;
        assert!(
            client(&server.url, 3)
                .deliver(&callback("exec-1"), &mut never_cancelled())
                .await
        );
        let received = server.received();
        assert_eq!(received.len(), 1);
        assert_eq!(received[0].method, "POST");
        assert_eq!(received[0].path, "/exec-1:complete");
        assert_eq!(received[0].headers["content-type"], "application/json");
        assert_eq!(received[0].headers["x-trace-id"], "trace-1");
        assert_eq!(received[0].headers["x-dispatch-attempt"], "2");
        assert_eq!(received[0].body, json!({"success": true}));
    }

    #[tokio::test]
    async fn does_not_retry_a_permanent_4xx() {
        let server = FakeCallbackServer::start(|_| Reply::Status(400)).await;
        assert!(
            !client(&server.url, 3)
                .deliver(&callback("e"), &mut never_cancelled())
                .await
        );
        assert_eq!(server.received().len(), 1);
    }

    #[tokio::test]
    async fn retries_429_with_the_same_dispatch_attempt() {
        let calls = AtomicUsize::new(0);
        let server = FakeCallbackServer::start(move |_| {
            Reply::Status(if calls.fetch_add(1, Ordering::SeqCst) < 2 {
                429
            } else {
                204
            })
        })
        .await;
        assert!(
            client(&server.url, 3)
                .deliver(&callback("e"), &mut never_cancelled())
                .await
        );
        let attempts: Vec<_> = server
            .received()
            .iter()
            .map(|r| r.headers["x-dispatch-attempt"].to_str().unwrap().to_owned())
            .collect();
        assert_eq!(attempts, vec!["2", "2", "2"]);
    }

    #[tokio::test]
    async fn stops_after_max_attempts() {
        let server = FakeCallbackServer::start(|_| Reply::Status(503)).await;
        assert!(
            !client(&server.url, 2)
                .deliver(&callback("e"), &mut never_cancelled())
                .await
        );
        assert_eq!(server.received().len(), 2);
    }

    #[tokio::test]
    async fn gives_up_without_a_callback_url() {
        let client = CallbackClient::new(None, Duration::from_secs(1), 3);
        assert!(!client.deliver(&callback("e"), &mut never_cancelled()).await);
    }

    #[tokio::test]
    async fn cancellation_interrupts_an_in_flight_attempt() {
        let server = FakeCallbackServer::start(|_| Reply::HoldThen(204)).await;
        let (cancel, mut cancelled) = watch::channel(false);
        let client = client(&server.url, 3);
        let payload = callback("e");
        let delivery = client.deliver(&payload, &mut cancelled);
        let trigger = async {
            server.wait_for(1, |_| true).await;
            cancel.send_replace(true);
        };
        let (delivered, ()) = tokio::join!(delivery, trigger);
        assert!(!delivered);
    }
}
```

`sdks/rust/src/lib.rs` becomes:

```rust
//! nanofaas function SDK for Rust: a warm HTTP runtime serving `/invoke`, `/health` and
//! `/metrics` under the admission, byte-limit and callback rules of
//! `sdks/runtime-contract/README.md`.

mod bounded;
mod callback;
mod limits;
mod settings;
mod types;

#[cfg(test)]
mod test_support;

pub use settings::RuntimeSettings;
pub use types::HandlerResponse;
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: compile errors: `cannot find type CallbackClient`, `cannot find function callback_url` and similar.

- [ ] **Step 4: Write the implementation**

Insert at the top of `sdks/rust/src/callback.rs`, above `#[cfg(test)]`:

```rust
use std::time::Duration;

use bytes::Bytes;
use tokio::sync::watch;

const BASE_RETRY_DELAYS_MS: [u64; 3] = [100, 500, 2000];

/// Who a callback reports on. `dispatch_attempt` is echoed unchanged on every delivery attempt:
/// only the control plane redispatches.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Identity {
    pub execution_id: String,
    pub trace_id: Option<String>,
    pub dispatch_attempt: Option<String>,
}

pub(crate) struct Callback {
    pub identity: Identity,
    pub body: Bytes,
}

/// Delay after each failed attempt: 100, 500, 2000 ms, the last repeated up to `max_attempts`.
pub(crate) fn retry_delays(max_attempts: usize) -> Vec<Duration> {
    (0..max_attempts)
        .map(|attempt| Duration::from_millis(BASE_RETRY_DELAYS_MS[attempt.min(2)]))
        .collect()
}

/// `{base}/{executionId}:complete`; a base already ending in `/<id>:complete` is cut back to its
/// parent first.
pub(crate) fn callback_url(base: &str, execution_id: &str) -> String {
    let mut base = base.trim().trim_end_matches('/');
    if let Some(suffix) = base.rfind(":complete") {
        if let Some(slash) = base[..suffix].rfind('/') {
            base = &base[..slash];
        }
    }
    format!("{base}/{execution_id}:complete")
}

#[derive(Debug, PartialEq, Eq)]
enum AttemptOutcome {
    Delivered,
    Permanent,
    Retry,
}

/// 2xx is delivered; a 4xx other than 408 and 429 will never succeed; everything else retries.
fn classify(status: u16) -> AttemptOutcome {
    match status {
        200..=299 => AttemptOutcome::Delivered,
        408 | 429 => AttemptOutcome::Retry,
        400..=499 => AttemptOutcome::Permanent,
        _ => AttemptOutcome::Retry,
    }
}

pub(crate) struct CallbackClient {
    base_url: Option<String>,
    http: reqwest::Client,
    attempt_timeout: Duration,
    pub(crate) retry_delays: Vec<Duration>,
}

impl CallbackClient {
    pub fn new(base_url: Option<String>, attempt_timeout: Duration, max_attempts: usize) -> Self {
        Self {
            base_url,
            http: reqwest::Client::new(),
            attempt_timeout,
            retry_delays: retry_delays(max_attempts),
        }
    }

    /// Delivers one serialized callback and reports whether a 2xx arrived. Gives up early on a
    /// permanent 4xx, and as soon as `cancel` turns true.
    pub async fn deliver(&self, callback: &Callback, cancel: &mut watch::Receiver<bool>) -> bool {
        let Some(base) = self.base_url.as_deref() else {
            return false;
        };
        if callback.identity.execution_id.trim().is_empty() {
            return false;
        }
        let url = callback_url(base, &callback.identity.execution_id);
        for (attempt, delay) in self.retry_delays.iter().enumerate() {
            let outcome = tokio::select! {
                outcome = self.send_once(&url, callback) => outcome,
                () = cancelled(cancel) => return false,
            };
            match outcome {
                AttemptOutcome::Delivered => return true,
                AttemptOutcome::Permanent => return false,
                AttemptOutcome::Retry => {}
            }
            if attempt + 1 == self.retry_delays.len() {
                break;
            }
            tokio::select! {
                () = tokio::time::sleep(*delay) => {}
                () = cancelled(cancel) => return false,
            }
        }
        false
    }

    async fn send_once(&self, url: &str, callback: &Callback) -> AttemptOutcome {
        let mut request = self
            .http
            .post(url)
            .timeout(self.attempt_timeout)
            .header("content-type", "application/json")
            .body(callback.body.clone());
        let identity = &callback.identity;
        if let Some(trace_id) = identity
            .trace_id
            .as_deref()
            .filter(|id| !id.trim().is_empty())
        {
            request = request.header("x-trace-id", trace_id);
        }
        if let Some(attempt) = identity
            .dispatch_attempt
            .as_deref()
            .filter(|a| !a.trim().is_empty())
        {
            request = request.header("x-dispatch-attempt", attempt);
        }
        match request.send().await {
            Ok(response) => classify(response.status().as_u16()),
            Err(_) => AttemptOutcome::Retry,
        }
    }
}

/// Resolves once `cancel` turns true; never, if its sender is gone.
async fn cancelled(cancel: &mut watch::Receiver<bool>) {
    if cancel.wait_for(|cancelled| *cancelled).await.is_err() {
        std::future::pending::<()>().await;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: `test result: ok. 30 passed`.

- [ ] **Step 6: Commit**

Stage, run the GitNexus change analysis CLAUDE.md requires (new Rust files typically report `changed_count: 0`; a `partial` or `error` flag is not a pass — rerun), then commit:

```bash
cd /home/michele/Documenti/nanofaas
git add sdks/rust/src/lib.rs sdks/rust/src/test_support.rs sdks/rust/src/callback.rs
GITNEXUS="$(python3 -c "import json;print(json.load(open('.gitnexus/meta.json'))['runnerIdentity']['invokedArtifact']['path'].split('/dist/')[0])")"
node --input-type=module -e "const {LocalBackend}=await import('$GITNEXUS/dist/mcp/local/local-backend.js'); console.log(JSON.stringify(await new LocalBackend().callTool('detect_changes',{scope:'staged',repo:'nanofaas'}))); process.exit(0)"
git commit -F - <<'EOF'
Add the Rust SDK callback client

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019F6VX47yLJ3prsE2mDcaU1
EOF
```

---

### Task 5: Metrics and the callback dispatcher

**Files:**
- Create: `sdks/rust/src/metrics.rs`, `sdks/rust/src/dispatcher.rs`
- Modify: `sdks/rust/src/lib.rs`

**Interfaces:**
- Consumes: `to_vec_bounded`, `EncodeError` (Task 2); `InvocationResult` (Task 2); `RuntimeSettings` (Task 1); `CallbackClient`, `Callback`, `Identity` (Task 4); `FakeCallbackServer`, `Reply` (Task 4, tests).
- Produces: in `metrics.rs`: `pub(crate) const CONTENT_TYPE`, `Metrics::new()`, `invocation(&str)`, `handler_duration(f64)`, `callback_drop()`, `cold_start()`, `render() -> String`, field `pub(crate) callback_drops: Counter`. In `dispatcher.rs`: `MINIMUM_TERMINAL_CALLBACK_PAYLOAD_BYTES = 256`, `CallbackSnapshot { pending_callbacks, pending_callback_bytes, serialized_callback_bytes }`, `SubmitError { TooLarge, Serialization, Closed }`, `Rejected { error, reservation }`, `NotDrained`, `Dispatcher::new(&RuntimeSettings, CallbackClient, Counter) -> Arc<Self>`, `open() -> Result<(), NotDrained>`, `try_reserve(self: &Arc<Self>) -> Option<CallbackReservation>`, `submit(&self, CallbackReservation, &Identity, &InvocationResult) -> Result<(), Rejected>`, `async close(&self, deadline: Instant)`, test-only `snapshot()` and `async wait_until_idle()`, fields `pub(crate) client: Arc<CallbackClient>`, `pub(crate) max_callback_payload_bytes: usize`.

- [ ] **Step 1: Write the failing tests**

Create `sdks/rust/src/metrics.rs` containing only its test module:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_the_go_metric_names() {
        let metrics = Metrics::new();
        metrics.invocation("success");
        metrics.invocation("timeout");
        metrics.handler_duration(0.02);
        metrics.callback_drop();
        metrics.cold_start();
        let text = metrics.render();
        assert!(
            text.contains(r#"nanofaas_runtime_invocations_total{status="success"} 1"#),
            "{text}"
        );
        assert!(
            text.contains(r#"nanofaas_runtime_invocations_total{status="timeout"} 1"#),
            "{text}"
        );
        assert!(
            text.contains("nanofaas_runtime_handler_duration_seconds_count 1"),
            "{text}"
        );
        assert!(
            text.contains(r#"nanofaas_runtime_handler_duration_seconds_bucket{le="0.025"} 1"#),
            "{text}"
        );
        assert!(
            text.contains("nanofaas_runtime_callback_drops_total 1"),
            "{text}"
        );
        assert!(
            text.contains("nanofaas_runtime_cold_starts_total 1"),
            "{text}"
        );
    }
}
```

Create `sdks/rust/src/dispatcher.rs` containing only its test module:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{FakeCallbackServer, Reply};
    use serde_json::json;
    use serde_json::value::RawValue;
    use std::time::Duration;

    fn dispatcher(
        url: &str,
        pending: usize,
        pending_bytes: usize,
        payload: usize,
    ) -> Arc<Dispatcher> {
        let settings = RuntimeSettings {
            max_pending_callbacks: pending,
            max_pending_callback_bytes: pending_bytes,
            max_callback_payload_bytes: payload,
            ..RuntimeSettings::default()
        };
        let mut client = CallbackClient::new(Some(url.into()), Duration::from_secs(1), 2);
        client.retry_delays = vec![Duration::ZERO; 2];
        let dispatcher = Dispatcher::new(&settings, client, Counter::default());
        dispatcher.open().unwrap();
        dispatcher
    }

    fn identity(execution_id: &str) -> Identity {
        Identity {
            execution_id: execution_id.into(),
            trace_id: None,
            dispatch_attempt: Some("1".into()),
        }
    }

    fn ok_result() -> InvocationResult<'static> {
        let output: &'static RawValue = serde_json::from_str(r#"{"result":"ok"}"#).unwrap();
        InvocationResult::success(output)
    }

    fn soon() -> Instant {
        Instant::now() + Duration::from_secs(2)
    }

    #[tokio::test]
    async fn reserves_count_and_maximum_payload_before_serialization() {
        let server = FakeCallbackServer::accepting().await;
        let dispatcher = dispatcher(&server.url, 2, 2048, 1024);
        let first = dispatcher.try_reserve().unwrap();
        assert_eq!(
            dispatcher.snapshot(),
            CallbackSnapshot {
                pending_callbacks: 1,
                pending_callback_bytes: 1024,
                serialized_callback_bytes: 0
            }
        );
        let _second = dispatcher.try_reserve().unwrap();
        assert!(dispatcher.try_reserve().is_none(), "count cap reached");
        drop(first);
        assert_eq!(dispatcher.snapshot().pending_callbacks, 1);
    }

    #[tokio::test]
    async fn the_byte_budget_caps_reservations_before_the_count_does() {
        let server = FakeCallbackServer::accepting().await;
        let dispatcher = dispatcher(&server.url, 10, 1024, 1024);
        let _held = dispatcher.try_reserve().unwrap();
        assert!(dispatcher.try_reserve().is_none());
    }

    #[tokio::test]
    async fn refuses_reservations_while_closed() {
        let settings = RuntimeSettings::default();
        let client = CallbackClient::new(None, Duration::from_secs(1), 1);
        let closed = Dispatcher::new(&settings, client, Counter::default());
        assert!(closed.try_reserve().is_none());
        assert_eq!(closed.snapshot(), CallbackSnapshot::default());
    }

    #[tokio::test]
    async fn a_submitted_callback_holds_its_serialized_size_until_delivered() {
        let server = FakeCallbackServer::start(|_| Reply::HoldThen(204)).await;
        let dispatcher = dispatcher(&server.url, 1, 1024, 1024);
        let reservation = dispatcher.try_reserve().unwrap();
        dispatcher
            .submit(reservation, &identity("exec-1"), &ok_result())
            .unwrap_or_else(|_| panic!("submit failed"));
        let size = serde_json::to_vec(&ok_result()).unwrap().len();
        assert_eq!(
            dispatcher.snapshot(),
            CallbackSnapshot {
                pending_callbacks: 1,
                pending_callback_bytes: size,
                serialized_callback_bytes: size
            }
        );
        let received = server.wait_for(1, |_| true).await;
        assert_eq!(received[0].path, "/exec-1:complete");
        assert_eq!(
            received[0].body,
            json!({"success": true, "output": {"result": "ok"}, "error": null})
        );
        server.release_held();
        dispatcher.wait_until_idle().await;
    }

    #[tokio::test]
    async fn an_oversized_callback_is_rejected_without_changing_counters() {
        let server = FakeCallbackServer::accepting().await;
        let dispatcher = dispatcher(&server.url, 1, 1024, 300);
        let reservation = dispatcher.try_reserve().unwrap();
        let big = format!("\"{}\"", "x".repeat(400));
        let output: &RawValue = serde_json::from_str(&big).unwrap();
        let rejected = dispatcher
            .submit(
                reservation,
                &identity("e"),
                &InvocationResult::success(output),
            )
            .err()
            .unwrap();
        assert_eq!(rejected.error, SubmitError::TooLarge);
        assert_eq!(dispatcher.snapshot().pending_callback_bytes, 300);
        drop(rejected);
        assert_eq!(dispatcher.snapshot(), CallbackSnapshot::default());
    }

    #[tokio::test]
    async fn exhausted_delivery_is_counted_and_releases_everything() {
        let server = FakeCallbackServer::start(|_| Reply::Status(503)).await;
        let dispatcher = dispatcher(&server.url, 1, 1024, 1024);
        let reservation = dispatcher.try_reserve().unwrap();
        dispatcher
            .submit(reservation, &identity("e"), &ok_result())
            .unwrap_or_else(|_| panic!("submit failed"));
        dispatcher.wait_until_idle().await;
        assert_eq!(server.received().len(), 2);
        assert_eq!(dispatcher.drops.get(), 1);
    }

    #[tokio::test]
    async fn close_drains_queued_callbacks_before_the_deadline() {
        let server = FakeCallbackServer::accepting().await;
        let dispatcher = dispatcher(&server.url, 4, 4096, 1024);
        for id in ["a", "b", "c"] {
            let reservation = dispatcher.try_reserve().unwrap();
            dispatcher
                .submit(reservation, &identity(id), &ok_result())
                .unwrap_or_else(|_| panic!("submit failed"));
        }
        dispatcher.close(soon()).await;
        assert_eq!(server.received().len(), 3);
        assert_eq!(dispatcher.snapshot(), CallbackSnapshot::default());
        assert!(dispatcher.try_reserve().is_none(), "closed after close()");
    }

    #[tokio::test]
    async fn close_cancels_in_flight_delivery_at_the_deadline() {
        let server = FakeCallbackServer::start(|_| Reply::HoldThen(204)).await;
        let dispatcher = dispatcher(&server.url, 1, 1024, 1024);
        let reservation = dispatcher.try_reserve().unwrap();
        dispatcher
            .submit(reservation, &identity("e"), &ok_result())
            .unwrap_or_else(|_| panic!("submit failed"));
        server.wait_for(1, |_| true).await;
        let started = Instant::now();
        dispatcher
            .close(Instant::now() + Duration::from_millis(50))
            .await;
        assert!(started.elapsed() < Duration::from_secs(1));
        assert_eq!(dispatcher.snapshot(), CallbackSnapshot::default());
        assert_eq!(dispatcher.drops.get(), 1);
    }

    #[tokio::test]
    async fn open_is_refused_while_an_earlier_run_owns_callbacks() {
        let server = FakeCallbackServer::accepting().await;
        let dispatcher = dispatcher(&server.url, 1, 1024, 1024);
        let held = dispatcher.try_reserve().unwrap();
        dispatcher.close(soon()).await;
        assert_eq!(dispatcher.open(), Err(NotDrained));
        drop(held);
        assert_eq!(dispatcher.open(), Ok(()));
    }
}
```

Add the new modules to `sdks/rust/src/lib.rs`, which becomes:

```rust
//! nanofaas function SDK for Rust: a warm HTTP runtime serving `/invoke`, `/health` and
//! `/metrics` under the admission, byte-limit and callback rules of
//! `sdks/runtime-contract/README.md`.

mod bounded;
mod callback;
mod dispatcher;
mod limits;
mod metrics;
mod settings;
mod types;

#[cfg(test)]
mod test_support;

pub use settings::RuntimeSettings;
pub use types::HandlerResponse;
```


- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: compile errors: `cannot find type Metrics`, `cannot find type Dispatcher` and similar.

- [ ] **Step 3: Write the implementation**

Insert at the top of `sdks/rust/src/metrics.rs`, above `#[cfg(test)]`:

```rust
use prometheus_client::encoding::text::encode;
use prometheus_client::metrics::counter::Counter;
use prometheus_client::metrics::family::Family;
use prometheus_client::metrics::histogram::Histogram;
use prometheus_client::registry::Registry;

/// Prometheus' default buckets, which the Go SDK's handler histogram uses.
const DEFAULT_BUCKETS: [f64; 11] = [
    0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0,
];

pub(crate) const CONTENT_TYPE: &str = "application/openmetrics-text; version=1.0.0; charset=utf-8";

/// The Go SDK's runtime metrics, under the same names.
pub(crate) struct Metrics {
    registry: Registry,
    invocations: Family<Vec<(String, String)>, Counter>,
    handler_duration: Histogram,
    pub(crate) callback_drops: Counter,
    cold_starts: Counter,
}

impl Metrics {
    pub fn new() -> Self {
        let mut registry = Registry::default();
        let invocations = Family::<Vec<(String, String)>, Counter>::default();
        registry.register(
            "nanofaas_runtime_invocations",
            "Total runtime invocations by status.",
            invocations.clone(),
        );
        let handler_duration = Histogram::new(DEFAULT_BUCKETS);
        registry.register(
            "nanofaas_runtime_handler_duration_seconds",
            "Handler execution duration.",
            handler_duration.clone(),
        );
        let callback_drops = Counter::default();
        registry.register(
            "nanofaas_runtime_callback_drops",
            "Callbacks rejected or exhausted before successful delivery.",
            callback_drops.clone(),
        );
        let cold_starts = Counter::default();
        registry.register(
            "nanofaas_runtime_cold_starts",
            "Total cold starts observed by the runtime.",
            cold_starts.clone(),
        );
        Self {
            registry,
            invocations,
            handler_duration,
            callback_drops,
            cold_starts,
        }
    }

    /// `status` is `success`, `error` or `timeout`.
    pub fn invocation(&self, status: &str) {
        self.invocations
            .get_or_create(&vec![("status".to_owned(), status.to_owned())])
            .inc();
    }

    pub fn handler_duration(&self, seconds: f64) {
        self.handler_duration.observe(seconds);
    }

    pub fn callback_drop(&self) {
        self.callback_drops.inc();
    }

    pub fn cold_start(&self) {
        self.cold_starts.inc();
    }

    pub fn render(&self) -> String {
        let mut text = String::new();
        encode(&mut text, &self.registry).expect("writing to a String cannot fail");
        text
    }
}
```

Insert at the top of `sdks/rust/src/dispatcher.rs`, above `#[cfg(test)]`:

```rust
use std::sync::{Arc, Mutex, MutexGuard};

use bytes::Bytes;
use prometheus_client::metrics::counter::Counter;
use tokio::sync::{Notify, mpsc, watch};
use tokio::task::JoinSet;
use tokio::time::Instant;

use crate::bounded::{EncodeError, to_vec_bounded};
use crate::callback::{Callback, CallbackClient, Identity};
use crate::settings::RuntimeSettings;
use crate::types::InvocationResult;

/// Room for every canonical runtime failure callback. A smaller payload cap could not keep the
/// promise that every admitted handler ends with a terminal callback.
pub(crate) const MINIMUM_TERMINAL_CALLBACK_PAYLOAD_BYTES: usize = 256;
const WORKERS: usize = 2;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct CallbackSnapshot {
    pub pending_callbacks: usize,
    pub pending_callback_bytes: usize,
    pub serialized_callback_bytes: usize,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum SubmitError {
    TooLarge,
    Serialization,
    Closed,
}

/// A refused submission hands its reservation back, so the caller can still send a failure.
pub(crate) struct Rejected {
    pub error: SubmitError,
    pub reservation: CallbackReservation,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct NotDrained;

struct Job {
    callback: Callback,
    _reservation: CallbackReservation,
}

struct Running {
    jobs: mpsc::Sender<Job>,
    cancel: watch::Sender<bool>,
    workers: JoinSet<()>,
}

struct DispatchState {
    counts: CallbackSnapshot,
    running: Option<Running>,
}

/// Bounded callback delivery. Capacity is reserved (one count plus the maximum payload) before a
/// handler starts, so accepted work always has room for its terminal callback.
pub(crate) struct Dispatcher {
    pub(crate) client: Arc<CallbackClient>,
    max_pending_callbacks: usize,
    max_pending_callback_bytes: usize,
    pub(crate) max_callback_payload_bytes: usize,
    drops: Counter,
    state: Mutex<DispatchState>,
    changed: Notify,
}

impl Dispatcher {
    pub fn new(settings: &RuntimeSettings, client: CallbackClient, drops: Counter) -> Arc<Self> {
        Arc::new(Self {
            client: Arc::new(client),
            max_pending_callbacks: settings.max_pending_callbacks,
            max_pending_callback_bytes: settings.max_pending_callback_bytes,
            max_callback_payload_bytes: settings.max_callback_payload_bytes,
            drops,
            state: Mutex::new(DispatchState {
                counts: CallbackSnapshot::default(),
                running: None,
            }),
            changed: Notify::new(),
        })
    }

    #[cfg(test)]
    pub fn snapshot(&self) -> CallbackSnapshot {
        self.lock().counts
    }

    /// Starts the delivery workers. Refused while running, or while an earlier run still owns
    /// callbacks.
    pub fn open(&self) -> Result<(), NotDrained> {
        let mut state = self.lock();
        if state.running.is_some() || state.counts != CallbackSnapshot::default() {
            return Err(NotDrained);
        }
        let (jobs, receiver) = mpsc::channel(self.max_pending_callbacks);
        let receiver = Arc::new(tokio::sync::Mutex::new(receiver));
        let (cancel, cancelled) = watch::channel(false);
        let mut workers = JoinSet::new();
        for _ in 0..WORKERS {
            workers.spawn(work(
                Arc::clone(&self.client),
                Arc::clone(&receiver),
                cancelled.clone(),
                self.drops.clone(),
            ));
        }
        state.running = Some(Running {
            jobs,
            cancel,
            workers,
        });
        Ok(())
    }

    pub fn try_reserve(self: &Arc<Self>) -> Option<CallbackReservation> {
        let mut state = self.lock();
        let counts = state.counts;
        let bytes_left = self.max_pending_callback_bytes - counts.pending_callback_bytes;
        if state.running.is_none()
            || counts.pending_callbacks >= self.max_pending_callbacks
            || self.max_callback_payload_bytes > bytes_left
        {
            return None;
        }
        state.counts.pending_callbacks += 1;
        state.counts.pending_callback_bytes += self.max_callback_payload_bytes;
        drop(state);
        self.changed.notify_waiters();
        Some(CallbackReservation {
            dispatcher: Arc::clone(self),
            held_bytes: self.max_callback_payload_bytes,
            serialized_bytes: 0,
        })
    }

    /// Serializes `result` within the payload cap and queues it with its reservation.
    pub fn submit(
        &self,
        mut reservation: CallbackReservation,
        identity: &Identity,
        result: &InvocationResult<'_>,
    ) -> Result<(), Rejected> {
        let body = match to_vec_bounded(result, self.max_callback_payload_bytes) {
            Ok(body) => body,
            Err(EncodeError::TooLarge) => return Err(rejected(SubmitError::TooLarge, reservation)),
            Err(EncodeError::Serialization) => {
                return Err(rejected(SubmitError::Serialization, reservation));
            }
        };
        let state = self.lock();
        let Some(running) = state.running.as_ref() else {
            drop(state);
            return Err(rejected(SubmitError::Closed, reservation));
        };
        let jobs = running.jobs.clone();
        drop(state);
        reservation.hold_serialized(body.len());
        let job = Job {
            callback: Callback {
                identity: identity.clone(),
                body: Bytes::from(body),
            },
            _reservation: reservation,
        };
        jobs.try_send(job).map_err(|error| {
            let mut reservation = error.into_inner()._reservation;
            reservation.release_serialized();
            rejected(SubmitError::Closed, reservation)
        })
    }

    /// Stops intake and lets the workers drain until `deadline`; then cancels whatever is still
    /// in flight or queued (each counted as a drop) and waits for the workers to return.
    pub async fn close(&self, deadline: Instant) {
        let Some(Running {
            jobs,
            cancel,
            mut workers,
        }) = self.lock().running.take()
        else {
            return;
        };
        drop(jobs);
        if tokio::time::timeout_at(deadline, join_all(&mut workers))
            .await
            .is_err()
        {
            cancel.send_replace(true);
            join_all(&mut workers).await;
        }
    }

    /// Resolves once no callback count, byte or serialized body is owned.
    #[cfg(test)]
    pub async fn wait_until_idle(&self) {
        loop {
            let notified = self.changed.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            if self.snapshot() == CallbackSnapshot::default() {
                return;
            }
            notified.await;
        }
    }

    fn update(&self, change: impl FnOnce(&mut CallbackSnapshot)) {
        change(&mut self.lock().counts);
        self.changed.notify_waiters();
    }

    fn lock(&self) -> MutexGuard<'_, DispatchState> {
        self.state
            .lock()
            .expect("dispatcher lock is never held across a panic")
    }
}

fn rejected(error: SubmitError, reservation: CallbackReservation) -> Rejected {
    Rejected { error, reservation }
}

async fn join_all(workers: &mut JoinSet<()>) {
    while workers.join_next().await.is_some() {}
}

async fn work(
    client: Arc<CallbackClient>,
    jobs: Arc<tokio::sync::Mutex<mpsc::Receiver<Job>>>,
    mut cancel: watch::Receiver<bool>,
    drops: Counter,
) {
    loop {
        let next = jobs.lock().await.recv().await;
        let Some(job) = next else { return };
        if !client.deliver(&job.callback, &mut cancel).await {
            drops.inc();
            tracing::warn!(
                execution_id = %job.callback.identity.execution_id,
                "callback delivery exhausted"
            );
        }
    }
}

/// One pending callback: a count plus its byte share (the maximum payload until serialized,
/// then the serialized size) and the serialized body. All of it returns on drop.
pub(crate) struct CallbackReservation {
    dispatcher: Arc<Dispatcher>,
    held_bytes: usize,
    serialized_bytes: usize,
}

impl CallbackReservation {
    /// Admission reserved the maximum payload because the output was unknown. Once encoded, the
    /// callback keeps only its own size; it never grows.
    fn hold_serialized(&mut self, len: usize) {
        let freed = self.held_bytes.saturating_sub(len);
        self.dispatcher.update(|counts| {
            counts.serialized_callback_bytes += len;
            counts.pending_callback_bytes -= freed;
        });
        self.held_bytes -= freed;
        self.serialized_bytes = len;
    }

    fn release_serialized(&mut self) {
        let bytes = std::mem::take(&mut self.serialized_bytes);
        self.dispatcher
            .update(|counts| counts.serialized_callback_bytes -= bytes);
    }
}

impl Drop for CallbackReservation {
    fn drop(&mut self) {
        let (held, serialized) = (self.held_bytes, self.serialized_bytes);
        self.dispatcher.update(|counts| {
            counts.pending_callbacks -= 1;
            counts.pending_callback_bytes -= held;
            counts.serialized_callback_bytes -= serialized;
        });
    }
}
```


- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: `test result: ok. 40 passed` for the library. Warnings about unused items are expected until Task 8 wires every module into `/invoke`.

- [ ] **Step 5: Commit**

Stage, run the GitNexus change analysis CLAUDE.md requires (new Rust files typically report `changed_count: 0`; a `partial` or `error` flag is not a pass — rerun), then commit:

```bash
cd /home/michele/Documenti/nanofaas
git add sdks/rust/src/lib.rs sdks/rust/src/metrics.rs sdks/rust/src/dispatcher.rs
GITNEXUS="$(python3 -c "import json;print(json.load(open('.gitnexus/meta.json'))['runnerIdentity']['invokedArtifact']['path'].split('/dist/')[0])")"
node --input-type=module -e "const {LocalBackend}=await import('$GITNEXUS/dist/mcp/local/local-backend.js'); console.log(JSON.stringify(await new LocalBackend().callTool('detect_changes',{scope:'staged',repo:'nanofaas'}))); process.exit(0)"
git commit -F - <<'EOF'
Add metrics and the bounded callback dispatcher to the Rust SDK

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019F6VX47yLJ3prsE2mDcaU1
EOF
```

---

### Task 6: Handler context and type erasure

**Files:**
- Create: `sdks/rust/src/context.rs`, `sdks/rust/src/handler.rs`
- Modify: `sdks/rust/src/lib.rs`

**Interfaces:**
- Consumes: `Limits`, `HandlerReservation` (Task 3); `HandlerResponse` (Task 2); `to_vec_bounded`, `EncodeError` (Task 2).
- Produces: `pub struct Context` (`Clone`) with `pub(crate) fn new(execution_id: String, trace_id: Option<String>, metadata: HashMap<String, String>, headers: HashMap<String, String>, cancelled: Arc<AtomicBool>, reservation: Arc<HandlerReservation>)`, and public `execution_id() -> &str`, `trace_id() -> Option<&str>`, `metadata()`, `headers() -> &HashMap<String, String>`, `is_cancelled() -> bool`, `spawn_blocking<F, R>(&self, F) -> JoinHandle<R>`. `pub type BoxError`; `pub(crate) enum Produced { Json(Vec<u8>), Envelope(HandlerResponse), Unencodable(EncodeError) }`; `pub(crate) type HandlerFuture`; `pub(crate) type ErasedHandler = Arc<dyn Fn(Context, &str, usize) -> Result<HandlerFuture, serde_json::Error> + Send + Sync>`; `pub(crate) fn erase<I, O, F, Fut>(F) -> ErasedHandler`.

- [ ] **Step 1: Write the failing tests**

Create `sdks/rust/src/context.rs` containing only its test module:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::limits::Limits;

    fn context(limits: &Arc<Limits>, cancelled: Arc<AtomicBool>) -> Context {
        let reservation = Arc::new(limits.try_reserve_handler().unwrap());
        Context::new(
            "exec-1".into(),
            Some("trace-1".into()),
            HashMap::from([("k".to_string(), "v".to_string())]),
            HashMap::from([("h".to_string(), "w".to_string())]),
            cancelled,
            reservation,
        )
    }

    #[test]
    fn exposes_identity_metadata_and_headers() {
        let limits = Limits::new(1);
        limits.set_accepting(true);
        let ctx = context(&limits, Arc::new(AtomicBool::new(false)));
        assert_eq!(ctx.execution_id(), "exec-1");
        assert_eq!(ctx.trace_id(), Some("trace-1"));
        assert_eq!(ctx.metadata()["k"], "v");
        assert_eq!(ctx.headers()["h"], "w");
        assert!(!ctx.is_cancelled());
    }

    #[tokio::test]
    async fn spawn_blocking_keeps_the_handler_slot_until_the_closure_returns() {
        let limits = Limits::new(1);
        limits.set_accepting(true);
        let cancelled = Arc::new(AtomicBool::new(false));
        let ctx = context(&limits, Arc::clone(&cancelled));
        let (release, released) = std::sync::mpsc::channel::<()>();
        let probe = ctx.clone();
        let work = ctx.spawn_blocking(move || {
            released.recv().unwrap();
            probe.is_cancelled()
        });
        drop(ctx);
        assert_eq!(limits.snapshot().active_handlers, 1);
        cancelled.store(true, Ordering::Release);
        release.send(()).unwrap();
        assert!(work.await.unwrap(), "the closure saw the cancellation");
        assert_eq!(limits.snapshot().active_handlers, 0);
    }
}
```

Create `sdks/rust/src/handler.rs` containing only its test module:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn plain_values_are_encoded_within_the_limit() {
        match produce(json!({"a": 1}), 64) {
            Produced::Json(json) => assert_eq!(json, br#"{"a":1}"#),
            other => panic!("unexpected {other:?}"),
        }
        assert!(matches!(
            produce("x".repeat(100), 10),
            Produced::Unencodable(EncodeError::TooLarge)
        ));
    }

    #[test]
    fn the_envelope_is_detected_by_type() {
        let envelope = HandlerResponse::new(json!("aGk="), 201).encoding("base64");
        match produce(envelope.clone(), 1) {
            Produced::Envelope(found) => assert_eq!(found, envelope),
            other => panic!("unexpected {other:?}"),
        }
    }
}
```

Add the new modules to `sdks/rust/src/lib.rs`, which becomes:

```rust
//! nanofaas function SDK for Rust: a warm HTTP runtime serving `/invoke`, `/health` and
//! `/metrics` under the admission, byte-limit and callback rules of
//! `sdks/runtime-contract/README.md`.

mod bounded;
mod callback;
mod context;
mod dispatcher;
mod handler;
mod limits;
mod metrics;
mod settings;
mod types;

#[cfg(test)]
mod test_support;

pub use context::Context;
pub use handler::BoxError;
pub use settings::RuntimeSettings;
pub use types::HandlerResponse;
```


- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: compile errors: `cannot find type Context`, `cannot find function produce` and similar.

- [ ] **Step 3: Write the implementation**

Insert at the top of `sdks/rust/src/context.rs`, above `#[cfg(test)]`:

```rust
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use crate::limits::HandlerReservation;

/// Per-invocation identity and helpers, handed to every handler call.
#[derive(Clone)]
pub struct Context {
    inner: Arc<Inner>,
}

struct Inner {
    execution_id: String,
    trace_id: Option<String>,
    metadata: HashMap<String, String>,
    headers: HashMap<String, String>,
    cancelled: Arc<AtomicBool>,
    reservation: Arc<HandlerReservation>,
}

impl Context {
    pub(crate) fn new(
        execution_id: String,
        trace_id: Option<String>,
        metadata: HashMap<String, String>,
        headers: HashMap<String, String>,
        cancelled: Arc<AtomicBool>,
        reservation: Arc<HandlerReservation>,
    ) -> Self {
        Self {
            inner: Arc::new(Inner {
                execution_id,
                trace_id,
                metadata,
                headers,
                cancelled,
                reservation,
            }),
        }
    }

    pub fn execution_id(&self) -> &str {
        &self.inner.execution_id
    }

    pub fn trace_id(&self) -> Option<&str> {
        self.inner.trace_id.as_deref()
    }

    /// The `metadata` object of the invocation request.
    pub fn metadata(&self) -> &HashMap<String, String> {
        &self.inner.metadata
    }

    /// The `headers` object of the invocation request.
    pub fn headers(&self) -> &HashMap<String, String> {
        &self.inner.headers
    }

    /// True once the runtime gave up on this invocation, on timeout or client disconnect. An
    /// async handler is dropped at that point; blocking work started with `spawn_blocking` should
    /// poll this and return early.
    pub fn is_cancelled(&self) -> bool {
        self.inner.cancelled.load(Ordering::Acquire)
    }

    /// Runs blocking work on tokio's blocking pool. The closure keeps this invocation's handler
    /// slot and input bytes reserved until it returns, even after the runtime stopped waiting.
    pub fn spawn_blocking<F, R>(&self, work: F) -> tokio::task::JoinHandle<R>
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        let reservation = Arc::clone(&self.inner.reservation);
        tokio::task::spawn_blocking(move || {
            let _reservation = reservation;
            work()
        })
    }
}
```

Insert at the top of `sdks/rust/src/handler.rs`, above `#[cfg(test)]`:

```rust
use std::any::Any;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::bounded::{EncodeError, to_vec_bounded};
use crate::context::Context;
use crate::types::HandlerResponse;

pub type BoxError = Box<dyn std::error::Error + Send + Sync>;

/// A finished handler's output, reduced to what the runtime needs.
#[derive(Debug)]
pub(crate) enum Produced {
    Json(Vec<u8>),
    Envelope(HandlerResponse),
    Unencodable(EncodeError),
}

pub(crate) type HandlerFuture = Pin<Box<dyn Future<Output = Result<Produced, BoxError>> + Send>>;

/// A registered handler with its input and output types erased. Deserializing the input happens
/// in the call, before any future exists, so malformed input never starts the handler.
pub(crate) type ErasedHandler =
    Arc<dyn Fn(Context, &str, usize) -> Result<HandlerFuture, serde_json::Error> + Send + Sync>;

pub(crate) fn erase<I, O, F, Fut>(handler: F) -> ErasedHandler
where
    I: DeserializeOwned + Send + 'static,
    O: Serialize + Send + 'static,
    F: Fn(Context, I) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = Result<O, BoxError>> + Send + 'static,
{
    Arc::new(move |ctx, input_json, max_output_bytes| {
        let input: I = serde_json::from_str(input_json)?;
        let output = handler(ctx, input);
        Ok(Box::pin(async move {
            Ok(produce(output.await?, max_output_bytes))
        }))
    })
}

/// Detects the envelope by type, like the Go SDK's type switch; encodes anything else within
/// the output limit.
fn produce<O: Serialize + 'static>(output: O, max_output_bytes: usize) -> Produced {
    let mut slot = Some(output);
    if let Some(envelope) = (&mut slot as &mut dyn Any).downcast_mut::<Option<HandlerResponse>>() {
        return Produced::Envelope(envelope.take().expect("slot was just filled"));
    }
    let output = slot.expect("only the envelope branch empties the slot");
    match to_vec_bounded(&output, max_output_bytes) {
        Ok(json) => Produced::Json(json),
        Err(error) => Produced::Unencodable(error),
    }
}
```


- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: `test result: ok. 44 passed` for the library. Warnings about unused items are expected until Task 8 wires every module into `/invoke`.

- [ ] **Step 5: Commit**

Stage, run the GitNexus change analysis CLAUDE.md requires (new Rust files typically report `changed_count: 0`; a `partial` or `error` flag is not a pass — rerun), then commit:

```bash
cd /home/michele/Documenti/nanofaas
git add sdks/rust/src/lib.rs sdks/rust/src/context.rs sdks/rust/src/handler.rs
GITNEXUS="$(python3 -c "import json;print(json.load(open('.gitnexus/meta.json'))['runnerIdentity']['invokedArtifact']['path'].split('/dist/')[0])")"
node --input-type=module -e "const {LocalBackend}=await import('$GITNEXUS/dist/mcp/local/local-backend.js'); console.log(JSON.stringify(await new LocalBackend().callTool('detect_changes',{scope:'staged',repo:'nanofaas'}))); process.exit(0)"
git commit -F - <<'EOF'
Add the handler context and handler type erasure to the Rust SDK

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019F6VX47yLJ3prsE2mDcaU1
EOF
```

---

### Task 7: Runtime lifecycle, health and metrics endpoints

`/invoke` is added in Task 8. This task delivers registration, `serve`/`start`, bounded stop, restart, `/health` and `/metrics`.

**Files:**
- Create: `sdks/rust/src/runtime.rs`
- Modify: `sdks/rust/src/lib.rs`, `sdks/rust/src/test_support.rs` (append the served-runtime fixture)

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces: `pub enum Error { Bind(io::Error), Serve(io::Error), NotDrained, ShutdownTimedOut }`; `pub(crate) enum RunState { Idle, Running, Stopping }`; `pub(crate) struct ColdStart` with `first_invocation() -> bool`, `mark_arrival()`, `init_duration_ms() -> u128`; `pub(crate) struct Shared { settings, handlers, limits: Arc<Limits>, dispatcher: Arc<Dispatcher>, metrics, cold_start, state: watch::Sender<RunState> }` with `resolve_handler() -> Option<&ErasedHandler>`; `pub struct Runtime` with `from_env()`, `with_settings(RuntimeSettings)`, `register(self, name, handler) -> Self`, `async start(self)`, `async serve(&self, TcpListener, impl Future<Output = ()> + Send) -> Result<(), Error>`, `pub(crate) router() -> axum::Router`, and test-only `shared()`, `state()`, `without_callback_backoff(self) -> Self`; `pub(crate) fn json_response(StatusCode, &Value) -> Response`. In `test_support.rs`: `TestRuntime { runtime: Arc<Runtime>, callbacks }` with `start(settings, register)`, `start_with(settings, callbacks, register)`, `restart()`, `stop()`, `send(request)`; `get(path)`, `invoke_request(body)`, `body_bytes`, `body_text`, `body_json`, `PendingBody`.

- [ ] **Step 1: Add the served-runtime test fixture**

Append to `sdks/rust/src/test_support.rs`:

```rust
/// A runtime serving on an ephemeral port (so admission is open) with a fake callback endpoint.
/// Requests go straight to the router, as the Go tests call `Handler().ServeHTTP`.
pub(crate) struct TestRuntime {
    pub runtime: Arc<crate::Runtime>,
    pub callbacks: FakeCallbackServer,
    stop: Option<tokio::sync::oneshot::Sender<()>>,
    served: Option<tokio::task::JoinHandle<Result<(), crate::Error>>>,
}

impl TestRuntime {
    pub async fn start(
        settings: crate::RuntimeSettings,
        register: impl FnOnce(crate::Runtime) -> crate::Runtime,
    ) -> Self {
        Self::start_with(settings, FakeCallbackServer::accepting().await, register).await
    }

    pub async fn start_with(
        settings: crate::RuntimeSettings,
        callbacks: FakeCallbackServer,
        register: impl FnOnce(crate::Runtime) -> crate::Runtime,
    ) -> Self {
        let settings = crate::RuntimeSettings {
            callback_url: Some(callbacks.url.clone()),
            ..settings
        };
        let runtime = register(crate::Runtime::with_settings(settings)).without_callback_backoff();
        let mut started = Self {
            runtime: Arc::new(runtime),
            callbacks,
            stop: None,
            served: None,
        };
        started.restart().await;
        started
    }

    /// Serves again; the previous run must have been stopped.
    pub async fn restart(&mut self) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let (stop, stopped) = tokio::sync::oneshot::channel::<()>();
        let runtime = Arc::clone(&self.runtime);
        self.served = Some(tokio::spawn(async move {
            runtime
                .serve(listener, async move {
                    let _ = stopped.await;
                })
                .await
        }));
        self.stop = Some(stop);
        self.runtime
            .state()
            .wait_for(|state| *state == crate::runtime::RunState::Running)
            .await
            .unwrap();
    }

    pub async fn stop(&mut self) -> Result<(), crate::Error> {
        let _ = self.stop.take().expect("serving").send(());
        self.served.take().expect("serving").await.unwrap()
    }

    pub async fn send(
        &self,
        request: axum::http::Request<axum::body::Body>,
    ) -> axum::response::Response {
        use tower::ServiceExt;
        self.runtime.router().oneshot(request).await.unwrap()
    }
}

pub(crate) fn get(path: &str) -> axum::http::Request<axum::body::Body> {
    axum::http::Request::get(path)
        .body(axum::body::Body::empty())
        .unwrap()
}

/// `POST /invoke` with execution id `exec-1`, trace `trace-1`, dispatch attempt `1`.
pub(crate) fn invoke_request(
    body: impl Into<axum::body::Body>,
) -> axum::http::Request<axum::body::Body> {
    axum::http::Request::post("/invoke")
        .header("x-execution-id", "exec-1")
        .header("x-trace-id", "trace-1")
        .header("x-dispatch-attempt", "1")
        .body(body.into())
        .unwrap()
}

pub(crate) async fn body_bytes(response: axum::response::Response) -> Bytes {
    response.into_body().collect().await.unwrap().to_bytes()
}

pub(crate) async fn body_text(response: axum::response::Response) -> String {
    String::from_utf8(body_bytes(response).await.to_vec()).unwrap()
}

pub(crate) async fn body_json(response: axum::response::Response) -> serde_json::Value {
    serde_json::from_slice(&body_bytes(response).await).unwrap()
}

/// A request body that never yields a byte.
pub(crate) struct PendingBody;

impl http_body::Body for PendingBody {
    type Data = Bytes;
    type Error = std::convert::Infallible;

    fn poll_frame(
        self: std::pin::Pin<&mut Self>,
        _cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Result<http_body::Frame<Bytes>, Self::Error>>> {
        std::task::Poll::Pending
    }
}

/// Waits for a handler to signal that it started, failing instead of hanging if it never does.
pub(crate) async fn handler_started(signal: &Notify) {
    tokio::time::timeout(Duration::from_secs(5), signal.notified())
        .await
        .expect("the handler never started");
}
```

- [ ] **Step 2: Write the failing tests**

Create `sdks/rust/src/runtime.rs` containing only its test module:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{TestRuntime, body_json, get};
    use serde_json::{Value, json};
    use tower::ServiceExt;

    #[tokio::test]
    async fn health_answers_ok_json() {
        let runtime = TestRuntime::start(RuntimeSettings::default(), |rt| rt).await;
        let response = runtime.send(get("/health")).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()[CONTENT_TYPE], "application/json");
        assert_eq!(body_json(response).await, json!({"status": "ok"}));
    }

    #[tokio::test]
    async fn metrics_expose_the_runtime_counters() {
        let runtime = TestRuntime::start(RuntimeSettings::default(), |rt| rt).await;
        let response = runtime.send(get("/metrics")).await;
        assert_eq!(response.status(), StatusCode::OK);
        let text = crate::test_support::body_text(response).await;
        assert!(
            text.contains("nanofaas_runtime_cold_starts_total 0"),
            "{text}"
        );
        assert!(
            text.contains("nanofaas_runtime_callback_drops_total 0"),
            "{text}"
        );
    }

    #[tokio::test]
    async fn stop_closes_admission_and_the_same_runtime_serves_again() {
        let mut runtime = TestRuntime::start(RuntimeSettings::default(), |rt| rt).await;
        runtime.stop().await.unwrap();
        let response = runtime
            .runtime
            .router()
            .oneshot(get("/health"))
            .await
            .unwrap();
        assert_eq!(
            response.status(),
            StatusCode::OK,
            "health stays reachable in-process"
        );
        assert!(!runtime.runtime.shared().limits.snapshot().accepting);
        runtime.restart().await;
        assert!(runtime.runtime.shared().limits.snapshot().accepting);
        runtime.stop().await.unwrap();
    }

    #[tokio::test]
    async fn serve_refuses_to_restart_over_an_undrained_callback_owner() {
        let mut runtime = TestRuntime::start(RuntimeSettings::default(), |rt| rt).await;
        let held = runtime.runtime.shared().dispatcher.try_reserve().unwrap();
        runtime.stop().await.unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let refused = runtime.runtime.serve(listener, async {}).await;
        assert!(matches!(refused, Err(Error::NotDrained)));
        drop(held);
        runtime.restart().await;
        runtime.stop().await.unwrap();
    }

    #[tokio::test]
    async fn start_reports_a_bind_failure_without_opening_the_dispatcher() {
        let taken = std::net::TcpListener::bind("0.0.0.0:0").unwrap();
        let port = taken.local_addr().unwrap().port();
        let runtime = Runtime::with_settings(RuntimeSettings {
            port,
            ..RuntimeSettings::default()
        });
        let shared = Arc::clone(runtime.shared());
        assert!(matches!(runtime.start().await, Err(Error::Bind(_))));
        assert!(shared.dispatcher.try_reserve().is_none());
    }

    #[test]
    fn resolves_the_single_handler_or_the_configured_one() {
        let echo = |_: Context, input: Value| async move { Ok::<_, BoxError>(input) };
        let single = Runtime::with_settings(RuntimeSettings::default()).register("a", echo);
        assert!(single.shared.resolve_handler().is_some());

        let two = Runtime::with_settings(RuntimeSettings::default())
            .register("a", echo)
            .register("b", echo);
        assert!(two.shared.resolve_handler().is_none());

        let chosen = Runtime::with_settings(RuntimeSettings {
            function_handler: Some("b".into()),
            ..RuntimeSettings::default()
        })
        .register("a", echo)
        .register("b", echo);
        assert!(chosen.shared.resolve_handler().is_some());
    }
}
```

`sdks/rust/src/lib.rs` becomes:

```rust
//! nanofaas function SDK for Rust: a warm HTTP runtime serving `/invoke`, `/health` and
//! `/metrics` under the admission, byte-limit and callback rules of
//! `sdks/runtime-contract/README.md`.

mod bounded;
mod callback;
mod context;
mod dispatcher;
mod handler;
mod limits;
mod metrics;
mod runtime;
mod settings;
mod types;

#[cfg(test)]
mod test_support;

pub use context::Context;
pub use handler::BoxError;
pub use runtime::{Error, Runtime};
pub use settings::RuntimeSettings;
pub use types::HandlerResponse;
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: compile errors: `cannot find type Runtime`, `unresolved import crate::runtime::RunState` and similar.

- [ ] **Step 4: Write the implementation**

Insert at the top of `sdks/rust/src/runtime.rs`, above `#[cfg(test)]`:

```rust
use std::collections::HashMap;
use std::fmt;
use std::future::{Future, IntoFuture};
use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};

use axum::Router;
use axum::extract::{DefaultBodyLimit, State};
use axum::http::StatusCode;
use axum::http::header::CONTENT_TYPE;
use axum::response::{IntoResponse, Response};
use axum::routing::any;
use serde::Serialize;
use serde::de::DeserializeOwned;
use tokio::net::TcpListener;
use tokio::sync::{oneshot, watch};
use tokio::time::Instant;

use crate::callback::CallbackClient;
use crate::context::Context;
use crate::dispatcher::Dispatcher;
use crate::handler::{self, BoxError, ErasedHandler};
use crate::limits::Limits;
use crate::metrics::{self, Metrics};
use crate::settings::RuntimeSettings;

#[derive(Debug)]
pub enum Error {
    /// The listener could not bind `PORT`.
    Bind(std::io::Error),
    /// The HTTP server failed while serving.
    Serve(std::io::Error),
    /// `serve` was called while running, or while an earlier run still owns callbacks.
    NotDrained,
    /// Handler work was still running when `NANOFAAS_SHUTDOWN_TIMEOUT` expired.
    ShutdownTimedOut,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Bind(error) => write!(f, "cannot bind the runtime port: {error}"),
            Self::Serve(error) => write!(f, "runtime server failed: {error}"),
            Self::NotDrained => f.write_str("runtime is running or still owns callbacks"),
            Self::ShutdownTimedOut => {
                f.write_str("handlers were still running at the shutdown deadline")
            }
        }
    }
}

impl std::error::Error for Error {}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum RunState {
    Idle,
    Running,
    Stopping,
}

/// Marks the first invocation and how long the process waited for it.
pub(crate) struct ColdStart {
    started: Instant,
    first_invocation_done: AtomicBool,
    first_arrival: OnceLock<Instant>,
}

impl ColdStart {
    fn new() -> Self {
        Self {
            started: Instant::now(),
            first_invocation_done: AtomicBool::new(false),
            first_arrival: OnceLock::new(),
        }
    }

    /// True for exactly one caller: the first invocation.
    pub fn first_invocation(&self) -> bool {
        !self.first_invocation_done.swap(true, Ordering::AcqRel)
    }

    pub fn mark_arrival(&self) {
        let _ = self.first_arrival.set(Instant::now());
    }

    pub fn init_duration_ms(&self) -> u128 {
        self.first_arrival.get().map_or(0, |arrival| {
            arrival.duration_since(self.started).as_millis()
        })
    }
}

pub(crate) struct Shared {
    pub settings: RuntimeSettings,
    pub handlers: HashMap<String, ErasedHandler>,
    pub limits: Arc<Limits>,
    pub dispatcher: Arc<Dispatcher>,
    pub metrics: Metrics,
    pub cold_start: ColdStart,
    pub state: watch::Sender<RunState>,
}

impl Shared {
    /// `FUNCTION_HANDLER` when set; otherwise the only registered handler.
    pub fn resolve_handler(&self) -> Option<&ErasedHandler> {
        match &self.settings.function_handler {
            Some(name) => self.handlers.get(name),
            None if self.handlers.len() == 1 => self.handlers.values().next(),
            None => None,
        }
    }
}

/// The warm function runtime: serves `/invoke`, `/health` and `/metrics`.
pub struct Runtime {
    shared: Arc<Shared>,
}

impl Runtime {
    pub fn from_env() -> Self {
        Self::with_settings(RuntimeSettings::from_env())
    }

    pub fn with_settings(settings: RuntimeSettings) -> Self {
        let settings = settings.normalized();
        let metrics = Metrics::new();
        let client = CallbackClient::new(
            settings.callback_url.clone(),
            settings.callback_attempt_timeout,
            settings.callback_max_attempts,
        );
        let dispatcher = Dispatcher::new(&settings, client, metrics.callback_drops.clone());
        Self {
            shared: Arc::new(Shared {
                limits: Limits::new(settings.max_concurrent_handlers),
                settings,
                handlers: HashMap::new(),
                dispatcher,
                metrics,
                cold_start: ColdStart::new(),
                state: watch::channel(RunState::Idle).0,
            }),
        }
    }

    /// Registers a handler. `I` is deserialized from the request's `input`; `O` is serialized as
    /// the response and callback output, unless it is a [`crate::HandlerResponse`] envelope.
    ///
    /// # Panics
    ///
    /// If called after the runtime started serving.
    pub fn register<I, O, F, Fut>(mut self, name: impl Into<String>, handler: F) -> Self
    where
        I: DeserializeOwned + Send + 'static,
        O: Serialize + Send + 'static,
        F: Fn(Context, I) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = Result<O, BoxError>> + Send + 'static,
    {
        Arc::get_mut(&mut self.shared)
            .expect("register every handler before serving")
            .handlers
            .insert(name.into(), handler::erase(handler));
        self
    }

    /// Binds `0.0.0.0:PORT` and serves until SIGTERM or Ctrl-C, then stops within
    /// `NANOFAAS_SHUTDOWN_TIMEOUT`.
    pub async fn start(self) -> Result<(), Error> {
        let port = self.shared.settings.port;
        let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, port))
            .await
            .map_err(Error::Bind)?;
        self.serve(listener, shutdown_signal()).await
    }

    /// Serves on `listener` until `shutdown` resolves. Stopping closes admission (new invocations
    /// get 503), then waits for the server, running handlers and queued callbacks, all within one
    /// `shutdown_timeout`. The same runtime may serve again once a run has fully drained.
    pub async fn serve(
        &self,
        listener: TcpListener,
        shutdown: impl Future<Output = ()> + Send,
    ) -> Result<(), Error> {
        let shared = &self.shared;
        shared.dispatcher.open().map_err(|_| Error::NotDrained)?;
        shared.limits.set_accepting(true);
        shared.state.send_replace(RunState::Running);

        let (stop_server, server_stopped) = oneshot::channel::<()>();
        let server = axum::serve(listener, self.router()).with_graceful_shutdown(async move {
            let _ = server_stopped.await;
        });
        let mut server = tokio::spawn(server.into_future());
        let early_exit = tokio::select! {
            joined = &mut server => Some(joined),
            () = shutdown => None,
        };

        shared.limits.set_accepting(false);
        shared.state.send_replace(RunState::Stopping);
        let deadline = Instant::now() + shared.settings.shutdown_timeout;
        let _ = stop_server.send(());
        let joined = match early_exit {
            Some(joined) => joined,
            None => match tokio::time::timeout_at(deadline, &mut server).await {
                Ok(joined) => joined,
                Err(_) => {
                    server.abort();
                    Ok(Ok(()))
                }
            },
        };
        let handlers_done = tokio::time::timeout_at(deadline, shared.limits.wait_until_idle())
            .await
            .is_ok();
        shared.dispatcher.close(deadline).await;
        shared.state.send_replace(RunState::Idle);

        joined
            .unwrap_or_else(|panic| Err(std::io::Error::other(panic)))
            .map_err(Error::Serve)?;
        if handlers_done {
            Ok(())
        } else {
            Err(Error::ShutdownTimedOut)
        }
    }

    pub(crate) fn router(&self) -> Router {
        Router::new()
            .route("/health", any(health))
            .route("/metrics", any(render_metrics))
            .layer(DefaultBodyLimit::disable())
            .with_state(Arc::clone(&self.shared))
    }

    #[cfg(test)]
    pub(crate) fn shared(&self) -> &Arc<Shared> {
        &self.shared
    }

    #[cfg(test)]
    pub(crate) fn state(&self) -> watch::Receiver<RunState> {
        self.shared.state.subscribe()
    }

    /// Tests keep the attempt count but drop the 100/500/2000 ms backoff between attempts.
    #[cfg(test)]
    pub(crate) fn without_callback_backoff(mut self) -> Self {
        let shared = Arc::get_mut(&mut self.shared).expect("configure before serving");
        let dispatcher = Arc::get_mut(&mut shared.dispatcher).expect("configure before serving");
        let client = Arc::get_mut(&mut dispatcher.client).expect("configure before serving");
        client.retry_delays.fill(std::time::Duration::ZERO);
        self
    }
}

pub(crate) fn json_response(status: StatusCode, body: &serde_json::Value) -> Response {
    let body = serde_json::to_vec(body).expect("a Value always serializes");
    (status, [(CONTENT_TYPE, "application/json")], body).into_response()
}

async fn health() -> Response {
    json_response(StatusCode::OK, &serde_json::json!({"status": "ok"}))
}

async fn render_metrics(State(shared): State<Arc<Shared>>) -> Response {
    (
        [(CONTENT_TYPE, metrics::CONTENT_TYPE)],
        shared.metrics.render(),
    )
        .into_response()
}

async fn shutdown_signal() {
    let ctrl_c = async {
        if tokio::signal::ctrl_c().await.is_err() {
            std::future::pending::<()>().await;
        }
    };
    #[cfg(unix)]
    let terminate = async {
        use tokio::signal::unix::{SignalKind, signal};
        match signal(SignalKind::terminate()) {
            Ok(mut terminate) => {
                terminate.recv().await;
            }
            Err(_) => std::future::pending::<()>().await,
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        () = ctrl_c => {}
        () = terminate => {}
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: `test result: ok. 50 passed`.

- [ ] **Step 6: Commit**

Stage, run the GitNexus change analysis CLAUDE.md requires (new Rust files typically report `changed_count: 0`; a `partial` or `error` flag is not a pass — rerun), then commit:

```bash
cd /home/michele/Documenti/nanofaas
git add sdks/rust/src/lib.rs sdks/rust/src/test_support.rs sdks/rust/src/runtime.rs
GITNEXUS="$(python3 -c "import json;print(json.load(open('.gitnexus/meta.json'))['runnerIdentity']['invokedArtifact']['path'].split('/dist/')[0])")"
node --input-type=module -e "const {LocalBackend}=await import('$GITNEXUS/dist/mcp/local/local-backend.js'); console.log(JSON.stringify(await new LocalBackend().callTool('detect_changes',{scope:'staged',repo:'nanofaas'}))); process.exit(0)"
git commit -F - <<'EOF'
Add the Rust SDK runtime lifecycle, health and metrics

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019F6VX47yLJ3prsE2mDcaU1
EOF
```

---

### Task 8: `/invoke`

**Files:**
- Create: `sdks/rust/src/invoke.rs`
- Modify: `sdks/rust/src/lib.rs`, `sdks/rust/src/runtime.rs` (import + route)

**Interfaces:**
- Consumes: everything from Tasks 1–7, notably `Shared`, `json_response`, `Dispatcher::submit`, `Limits::try_reserve_handler`, `ErasedHandler`, `CountedBody`, `TestRuntime`.
- Produces: `pub(crate) async fn invoke(State<Arc<Shared>>, Request) -> Response`, registered as `/invoke`.

Two test modules pin the Go admission order and the execution outcomes, including the four Review Focus cases owned here.

- [ ] **Step 1: Write the failing tests**

Create `sdks/rust/src/invoke.rs` containing only its test module:

```rust
#[cfg(test)]
mod admission_tests {
    use crate::dispatcher::CallbackSnapshot;
    use crate::test_support::{
        PendingBody, TestRuntime, body_json, handler_started, invoke_request,
    };
    use crate::{BoxError, Context, Runtime, RuntimeSettings};
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use serde::Deserialize;
    use serde_json::{Value, json};
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;
    use tokio::sync::Notify;
    use tower::ServiceExt;

    async fn echo(_: Context, input: Value) -> Result<Value, BoxError> {
        Ok(input)
    }

    fn with_echo(runtime: Runtime) -> Runtime {
        runtime.register("echo", echo)
    }

    fn settings() -> RuntimeSettings {
        RuntimeSettings {
            handler_timeout: Duration::from_secs(2),
            ..RuntimeSettings::default()
        }
    }

    fn assert_idle(runtime: &TestRuntime) {
        let shared = runtime.runtime.shared();
        assert_eq!(shared.limits.snapshot().active_handlers, 0);
        assert_eq!(shared.limits.snapshot().input_bytes, 0);
        assert_eq!(shared.dispatcher.snapshot(), CallbackSnapshot::default());
    }

    #[tokio::test]
    async fn rejects_non_post_methods() {
        let runtime = TestRuntime::start(settings(), with_echo).await;
        let request = Request::get("/invoke").body(Body::empty()).unwrap();
        assert_eq!(
            runtime.send(request).await.status(),
            StatusCode::METHOD_NOT_ALLOWED
        );
    }

    #[tokio::test]
    async fn answers_stopping_before_the_runtime_serves() {
        let runtime = with_echo(Runtime::with_settings(settings()));
        let response = runtime
            .router()
            .oneshot(invoke_request(r#"{"input":1}"#))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(response.headers()["retry-after"], "1");
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "RUNTIME_STOPPING", "message": "Runtime is stopping"}})
        );
    }

    #[tokio::test]
    async fn requires_an_execution_id() {
        let runtime = TestRuntime::start(settings(), with_echo).await;
        let request = Request::post("/invoke")
            .body(Body::from(r#"{"input":1}"#))
            .unwrap();
        let response = runtime.send(request).await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        assert_eq!(
            body_json(response).await,
            json!({"error": "Execution ID not configured"})
        );
    }

    #[tokio::test]
    async fn falls_back_to_the_environment_execution_id() {
        let settings = RuntimeSettings {
            execution_id: Some("env-exec".into()),
            ..settings()
        };
        let runtime = TestRuntime::start(settings, with_echo).await;
        let request = Request::post("/invoke")
            .body(Body::from(r#"{"input":1}"#))
            .unwrap();
        assert_eq!(runtime.send(request).await.status(), StatusCode::OK);
        let received = runtime.callbacks.wait_for(1, |_| true).await;
        assert_eq!(received[0].path, "/env-exec:complete");
    }

    #[tokio::test]
    async fn answers_500_when_no_handler_resolves() {
        let runtime =
            TestRuntime::start(settings(), |rt| rt.register("a", echo).register("b", echo)).await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(
            body_json(response).await,
            json!({"error": "Handler not configured"})
        );
    }

    #[tokio::test]
    async fn rejects_a_declared_length_over_the_input_limit() {
        let settings = RuntimeSettings {
            max_input_bytes: 16,
            ..settings()
        };
        let runtime = TestRuntime::start(settings, with_echo).await;
        let request = Request::post("/invoke")
            .header("x-execution-id", "exec-1")
            .header("content-length", "17")
            .body(Body::from("x".repeat(17)))
            .unwrap();
        let response = runtime.send(request).await;
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "RUNTIME_INPUT_TOO_LARGE",
                             "message": "Runtime input exceeds configured byte limit"}})
        );
        assert_idle(&runtime);
    }

    #[tokio::test]
    async fn rejects_a_streamed_body_over_the_input_limit() {
        let settings = RuntimeSettings {
            max_input_bytes: 16,
            ..settings()
        };
        let runtime = TestRuntime::start(settings, with_echo).await;
        let response = runtime
            .send(invoke_request(format!(
                r#"{{"input":"{}"}}"#,
                "x".repeat(32)
            )))
            .await;
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
        assert_idle(&runtime);
    }

    #[tokio::test]
    async fn bounds_the_body_read_time() {
        let settings = RuntimeSettings {
            body_read_timeout: Duration::from_millis(50),
            ..settings()
        };
        let runtime = TestRuntime::start(settings, with_echo).await;
        let response = runtime.send(invoke_request(Body::new(PendingBody))).await;
        assert_eq!(response.status(), StatusCode::REQUEST_TIMEOUT);
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "RUNTIME_BODY_READ_TIMEOUT",
                             "message": "Runtime request body read timed out"}})
        );
        assert_idle(&runtime);
    }

    #[tokio::test]
    async fn rejects_malformed_json_and_input_of_the_wrong_shape() {
        #[derive(Deserialize)]
        struct Named {
            #[allow(dead_code)]
            name: String,
        }
        let calls = Arc::new(AtomicUsize::new(0));
        let counted = Arc::clone(&calls);
        let runtime = TestRuntime::start(settings(), move |rt| {
            rt.register("named", move |_: Context, _: Named| {
                counted.fetch_add(1, Ordering::SeqCst);
                async { Ok::<_, BoxError>(json!("ok")) }
            })
        })
        .await;
        for body in ["{not json", r#"{"input":{"name":42}}"#] {
            let response = runtime.send(invoke_request(body)).await;
            assert_eq!(response.status(), StatusCode::BAD_REQUEST, "{body}");
            assert_eq!(
                body_json(response).await,
                json!({"error": "Malformed request body"})
            );
        }
        assert_eq!(calls.load(Ordering::SeqCst), 0, "the handler never started");
        assert_idle(&runtime);
        assert!(runtime.callbacks.received().is_empty());
    }

    #[tokio::test]
    async fn accepts_a_body_exactly_at_the_input_limit() {
        let body = r#"{"input":"xxxxxxxxxx"}"#;
        let settings = RuntimeSettings {
            max_input_bytes: body.len(),
            ..settings()
        };
        let runtime = TestRuntime::start(settings, with_echo).await;
        let request = Request::post("/invoke")
            .header("x-execution-id", "exec-1")
            .header("content-length", body.len().to_string())
            .body(Body::from(body))
            .unwrap();
        assert_eq!(runtime.send(request).await.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn reports_handler_saturation_while_a_handler_runs() {
        let settings = RuntimeSettings {
            max_concurrent_handlers: 1,
            ..settings()
        };
        let started = Arc::new(Notify::new());
        let release = Arc::new(Notify::new());
        let (started_signal, release_wait) = (Arc::clone(&started), Arc::clone(&release));
        let runtime = TestRuntime::start(settings, move |rt| {
            rt.register("block", move |_: Context, _: Value| {
                let (started, release) = (Arc::clone(&started_signal), Arc::clone(&release_wait));
                async move {
                    started.notify_one();
                    release.notified().await;
                    Ok::<_, BoxError>(json!("done"))
                }
            })
        })
        .await;
        let router = runtime.runtime.router();
        let first = tokio::spawn(router.oneshot(invoke_request(r#"{"input":1}"#)));
        handler_started(&started).await;
        let response = runtime.send(invoke_request(r#"{"input":2}"#)).await;
        assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(response.headers()["retry-after"], "1");
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "RUNTIME_HANDLER_SATURATED",
                             "message": "Runtime handler capacity exhausted"}})
        );
        release.notify_one();
        assert_eq!(first.await.unwrap().unwrap().status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn reports_callback_saturation_before_the_handler_starts() {
        let runtime = TestRuntime::start(
            RuntimeSettings {
                max_pending_callbacks: 1,
                ..settings()
            },
            with_echo,
        )
        .await;
        let held = runtime.runtime.shared().dispatcher.try_reserve().unwrap();
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(response.headers()["retry-after"], "1");
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "RUNTIME_CALLBACK_SATURATED",
                             "message": "Runtime callback capacity exhausted"}})
        );
        assert_eq!(
            runtime.runtime.shared().limits.snapshot().active_handlers,
            0
        );
        drop(held);
    }

    #[tokio::test]
    async fn a_payload_cap_below_the_terminal_minimum_counts_as_callback_saturation() {
        let settings = RuntimeSettings {
            max_callback_payload_bytes: 255,
            ..settings()
        };
        let runtime = TestRuntime::start(settings, with_echo).await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(
            body_json(response).await["error"]["code"],
            "RUNTIME_CALLBACK_SATURATED"
        );
    }
}

#[cfg(test)]
mod execution_tests {
    use crate::dispatcher::CallbackSnapshot;
    use crate::test_support::{
        FakeCallbackServer, Reply, TestRuntime, body_bytes, body_json, body_text, get,
        handler_started, invoke_request,
    };
    use crate::{BoxError, Context, HandlerResponse, Runtime, RuntimeSettings};
    use axum::http::StatusCode;
    use serde::{Deserialize, Serialize};
    use serde_json::{Value, json};
    use std::collections::HashMap;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::Duration;
    use tokio::sync::Notify;
    use tower::ServiceExt;

    async fn echo(_: Context, input: Value) -> Result<Value, BoxError> {
        Ok(json!({"echo": input}))
    }

    fn settings() -> RuntimeSettings {
        RuntimeSettings {
            handler_timeout: Duration::from_secs(2),
            ..RuntimeSettings::default()
        }
    }

    async fn assert_drained(runtime: &TestRuntime) {
        let shared = runtime.runtime.shared();
        tokio::time::timeout(Duration::from_secs(2), async {
            shared.limits.wait_until_idle().await;
            shared.dispatcher.wait_until_idle().await;
        })
        .await
        .expect("counters never drained");
        let limits = shared.limits.snapshot();
        assert_eq!((limits.input_bytes, limits.output_bytes), (0, 0));
        assert_eq!(shared.dispatcher.snapshot(), CallbackSnapshot::default());
    }

    #[tokio::test]
    async fn returns_the_output_and_reports_success_by_callback() {
        let runtime = TestRuntime::start(settings(), |rt| rt.register("echo", echo)).await;
        let response = runtime.send(invoke_request(r#"{"input":{"n":1}}"#)).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()["content-type"], "application/json");
        assert_eq!(response.headers()["x-cold-start"], "true");
        assert!(response.headers().contains_key("x-init-duration-ms"));
        assert_eq!(body_json(response).await, json!({"echo": {"n": 1}}));

        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(callback.method, "POST");
        assert_eq!(callback.path, "/exec-1:complete");
        assert_eq!(callback.headers["x-trace-id"], "trace-1");
        assert_eq!(callback.headers["x-dispatch-attempt"], "1");
        assert_eq!(
            callback.body,
            json!({"success": true, "output": {"echo": {"n": 1}}, "error": null})
        );

        let second = runtime.send(invoke_request(r#"{"input":2}"#)).await;
        assert!(
            !second.headers().contains_key("x-cold-start"),
            "only the first is cold"
        );
        drop(second);
        assert_drained(&runtime).await;
    }

    #[tokio::test]
    async fn deserializes_typed_input_and_serializes_typed_output() {
        #[derive(Deserialize)]
        struct In {
            text: String,
        }
        #[derive(Serialize)]
        struct Out {
            words: usize,
        }
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("count", |_: Context, input: In| async move {
                Ok::<_, BoxError>(Out {
                    words: input.text.split_whitespace().count(),
                })
            })
        })
        .await;
        let response = runtime
            .send(invoke_request(r#"{"input":{"text":"a b c"}}"#))
            .await;
        assert_eq!(body_json(response).await, json!({"words": 3}));
    }

    #[tokio::test]
    async fn the_handler_sees_request_metadata_and_headers() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("inspect", |ctx: Context, _: Value| async move {
                Ok::<_, BoxError>(json!({
                    "executionId": ctx.execution_id(),
                    "traceId": ctx.trace_id(),
                    "metadata": ctx.metadata(),
                    "headers": ctx.headers(),
                }))
            })
        })
        .await;
        let body = r#"{"input":null,"metadata":{"k":"v"},"headers":{"accept":"text/plain"}}"#;
        let response = runtime.send(invoke_request(body)).await;
        assert_eq!(
            body_json(response).await,
            json!({"executionId": "exec-1", "traceId": "trace-1",
                   "metadata": {"k": "v"}, "headers": {"accept": "text/plain"}})
        );
    }

    #[tokio::test]
    async fn a_handler_error_is_a_platform_error() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("fail", |_: Context, _: Value| async {
                Err::<Value, BoxError>("boom".into())
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "HANDLER_ERROR", "message": "Handler failed"}})
        );
        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(
            callback.body,
            json!({"success": false, "output": null,
                   "error": {"code": "HANDLER_ERROR", "message": "Handler failed"}})
        );
        assert_drained(&runtime).await;
    }

    #[tokio::test]
    async fn a_panicking_handler_is_a_platform_error() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("panic", |_: Context, _: Value| async {
                if true {
                    panic!("handler bug");
                }
                Ok::<Value, BoxError>(Value::Null)
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(body_json(response).await["error"]["code"], "HANDLER_ERROR");
        assert_drained(&runtime).await;
    }

    #[tokio::test]
    async fn a_timed_out_handler_is_dropped_and_reported() {
        let settings = RuntimeSettings {
            handler_timeout: Duration::from_millis(50),
            ..settings()
        };
        let dropped = Arc::new(AtomicBool::new(false));
        let observed = Arc::clone(&dropped);
        let runtime = TestRuntime::start(settings, move |rt| {
            rt.register("hang", move |_: Context, _: Value| {
                let guard = DropFlag(Arc::clone(&observed));
                async move {
                    let _guard = guard;
                    std::future::pending::<()>().await;
                    Ok::<Value, BoxError>(Value::Null)
                }
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::GATEWAY_TIMEOUT);
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "HANDLER_TIMEOUT", "message": "Handler exceeded configured timeout"}})
        );
        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(callback.body["error"]["code"], "HANDLER_TIMEOUT");
        assert_drained(&runtime).await;
        assert!(
            dropped.load(Ordering::SeqCst),
            "the handler future was dropped"
        );
    }

    struct DropFlag(Arc<AtomicBool>);

    impl Drop for DropFlag {
        fn drop(&mut self) {
            self.0.store(true, Ordering::SeqCst);
        }
    }

    #[tokio::test]
    async fn blocking_work_keeps_its_slot_after_a_timeout_until_it_returns() {
        let settings = RuntimeSettings {
            handler_timeout: Duration::from_millis(50),
            max_concurrent_handlers: 1,
            ..settings()
        };
        let (release, released) = std::sync::mpsc::channel::<()>();
        let released = Arc::new(std::sync::Mutex::new(released));
        let saw_cancel = Arc::new(AtomicBool::new(false));
        let observed = Arc::clone(&saw_cancel);
        let runtime = TestRuntime::start(settings, move |rt| {
            rt.register("blocking", move |ctx: Context, _: Value| {
                let (released, observed) = (Arc::clone(&released), Arc::clone(&observed));
                async move {
                    let probe = ctx.clone();
                    ctx.spawn_blocking(move || {
                        released.lock().unwrap().recv().unwrap();
                        observed.store(probe.is_cancelled(), Ordering::SeqCst);
                    })
                    .await?;
                    Ok::<Value, BoxError>(Value::Null)
                }
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::GATEWAY_TIMEOUT);
        let busy = runtime.send(invoke_request(r#"{"input":2}"#)).await;
        assert_eq!(
            busy.status(),
            StatusCode::TOO_MANY_REQUESTS,
            "the slot is still held"
        );
        release.send(()).unwrap();
        assert_drained(&runtime).await;
        assert!(saw_cancel.load(Ordering::SeqCst));
    }

    #[tokio::test]
    async fn a_disconnected_client_cancels_the_handler_and_still_gets_a_callback() {
        let started = Arc::new(Notify::new());
        let signal = Arc::clone(&started);
        let runtime = TestRuntime::start(settings(), move |rt| {
            rt.register("hang", move |_: Context, _: Value| {
                let signal = Arc::clone(&signal);
                async move {
                    signal.notify_one();
                    std::future::pending::<()>().await;
                    Ok::<Value, BoxError>(Value::Null)
                }
            })
        })
        .await;
        let request = tokio::spawn(
            runtime
                .runtime
                .router()
                .oneshot(invoke_request(r#"{"input":1}"#)),
        );
        handler_started(&started).await;
        request.abort();
        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(
            callback.body,
            json!({"success": false, "output": null,
                   "error": {"code": "INVOCATION_CANCELLED", "message": "Invocation cancelled"}})
        );
        assert_drained(&runtime).await;
    }

    #[tokio::test]
    async fn oversized_output_is_rejected_with_a_callback() {
        let settings = RuntimeSettings {
            max_output_bytes: 16,
            ..settings()
        };
        let runtime = TestRuntime::start(settings, |rt| {
            rt.register("big", |_: Context, _: Value| async {
                Ok::<_, BoxError>("x".repeat(64))
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        let expected = json!({"code": "RUNTIME_OUTPUT_TOO_LARGE",
                              "message": "Runtime output exceeds configured byte limit"});
        assert_eq!(body_json(response).await, json!({"error": expected}));
        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(callback.body["error"], expected);
        assert_drained(&runtime).await;
    }

    #[tokio::test]
    async fn output_json_cannot_represent_is_a_serialization_error() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("tuple-keys", |_: Context, _: Value| async {
                Ok::<_, BoxError>(HashMap::from([((1, 2), 3)]))
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "OUTPUT_SERIALIZATION_ERROR",
                             "message": "Handler output could not be serialized"}})
        );
    }

    #[tokio::test]
    async fn the_envelope_sets_status_allowed_headers_and_markers() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("png", |_: Context, _: Value| async {
                Ok::<_, BoxError>(
                    HandlerResponse::new(json!("iVBORw0KGgo="), 201)
                        .header("Content-Type", "image/png")
                        .header("Location", "/things/1")
                        .header("X-Execution-Id", "spoofed")
                        .header("X-NanoFaaS-Function-Status", "false")
                        .encoding("base64"),
                )
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::CREATED);
        let headers = response.headers().clone();
        assert_eq!(headers["content-type"], "image/png");
        assert_eq!(headers["location"], "/things/1");
        assert!(!headers.contains_key("x-execution-id"));
        assert_eq!(headers["x-nanofaas-function-status"], "true");
        assert_eq!(headers["x-nanofaas-encoding"], "base64");
        assert_eq!(body_text(response).await, r#""iVBORw0KGgo=""#);
        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(
            callback.body,
            json!({"success": true, "output": "iVBORw0KGgo=", "error": null, "statusCode": 201,
                   "headers": {"Content-Type": "image/png", "Location": "/things/1"},
                   "encoding": "base64"})
        );
    }

    #[tokio::test]
    async fn an_envelope_with_an_invalid_status_is_a_platform_error() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("bad", |_: Context, _: Value| async {
                Ok::<_, BoxError>(HandlerResponse::new(json!("x"), 99))
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "OUTPUT_SERIALIZATION_ERROR",
                             "message": "Handler returned invalid statusCode: 99"}})
        );
    }

    /// The frozen wire contract shared with platform/common, sdks/python, sdks/go and
    /// sdks/javascript. Changing one side silently breaks cross-language dispatch.
    #[tokio::test]
    async fn wire_contract_header_names() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("x", |_: Context, _: Value| async {
                Ok::<_, BoxError>(HandlerResponse::new(json!("x"), 201).encoding("base64"))
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.headers()["X-NanoFaaS-Function-Status"], "true");
        assert_eq!(response.headers()["X-NanoFaaS-Encoding"], "base64");
    }

    #[tokio::test]
    async fn a_missing_input_reaches_the_handler_as_null() {
        let runtime = TestRuntime::start(settings(), |rt| rt.register("echo", echo)).await;
        let response = runtime
            .send(invoke_request(r#"{"metadata":{"k":"v"}}"#))
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(body_json(response).await, json!({"echo": null}));
    }

    #[tokio::test]
    async fn envelope_header_values_http_cannot_carry_are_dropped_everywhere() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("inject", |_: Context, _: Value| async {
                Ok::<_, BoxError>(
                    HandlerResponse::new(json!("x"), 200)
                        .header("Content-Type", "text/plain\r\nX-Injected: 1")
                        .header("Location", "/ok"),
                )
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()["content-type"], "application/json");
        assert_eq!(response.headers()["location"], "/ok");
        assert!(!response.headers().contains_key("x-injected"));
        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(callback.body["headers"], json!({"Location": "/ok"}));
    }

    #[tokio::test]
    async fn a_burst_above_the_handler_cap_gets_only_ok_or_429_and_drains() {
        let settings = RuntimeSettings {
            max_concurrent_handlers: 4,
            ..settings()
        };
        let runtime = TestRuntime::start(settings, |rt| {
            rt.register("slow", |_: Context, input: Value| async move {
                tokio::time::sleep(Duration::from_millis(20)).await;
                Ok::<_, BoxError>(input)
            })
        })
        .await;
        let router = runtime.runtime.router();
        let burst: Vec<_> = (0..32)
            .map(|i| {
                let request = invoke_request(format!(r#"{{"input":{i}}}"#));
                tokio::spawn(router.clone().oneshot(request))
            })
            .collect();
        let mut succeeded = 0;
        for request in burst {
            let response = request.await.unwrap().unwrap();
            match response.status() {
                StatusCode::OK => succeeded += 1,
                StatusCode::TOO_MANY_REQUESTS => {}
                other => panic!("unexpected status {other}"),
            }
        }
        assert!(succeeded >= 1);
        assert_drained(&runtime).await;
    }

    #[tokio::test]
    async fn output_bytes_stay_counted_until_the_body_is_written() {
        let runtime = TestRuntime::start(settings(), |rt| rt.register("echo", echo)).await;
        let response = runtime.send(invoke_request(r#"{"input":"abc"}"#)).await;
        let expected = br#"{"echo":"abc"}"#;
        assert_eq!(
            runtime.runtime.shared().limits.snapshot().output_bytes,
            expected.len()
        );
        assert_eq!(&body_bytes(response).await[..], expected);
        assert_eq!(runtime.runtime.shared().limits.snapshot().output_bytes, 0);
    }

    #[tokio::test]
    async fn exhausted_callbacks_are_counted_in_metrics() {
        let callbacks = FakeCallbackServer::start(|_| Reply::Status(503)).await;
        let settings = RuntimeSettings {
            callback_max_attempts: 2,
            ..settings()
        };
        let runtime =
            TestRuntime::start_with(settings, callbacks, |rt| rt.register("echo", echo)).await;
        assert_eq!(
            runtime
                .send(invoke_request(r#"{"input":1}"#))
                .await
                .status(),
            StatusCode::OK
        );
        assert_drained(&runtime).await;
        assert_eq!(runtime.callbacks.received().len(), 2);
        let metrics = body_text(runtime.send(get("/metrics")).await).await;
        assert!(
            metrics.contains("nanofaas_runtime_callback_drops_total 1"),
            "{metrics}"
        );
        assert!(metrics.contains(r#"nanofaas_runtime_invocations_total{status="success"} 1"#));
    }

    #[tokio::test]
    async fn stop_reports_handlers_still_running_at_the_deadline() {
        let settings = RuntimeSettings {
            shutdown_timeout: Duration::from_millis(100),
            ..settings()
        };
        let started = Arc::new(Notify::new());
        let signal = Arc::clone(&started);
        let (release, released) = std::sync::mpsc::channel::<()>();
        let released = Arc::new(std::sync::Mutex::new(released));
        let mut runtime = TestRuntime::start(settings, move |rt| {
            rt.register("stuck", move |ctx: Context, _: Value| {
                let (signal, released) = (Arc::clone(&signal), Arc::clone(&released));
                async move {
                    ctx.spawn_blocking(move || {
                        signal.notify_one();
                        released.lock().unwrap().recv().unwrap();
                    })
                    .await?;
                    Ok::<Value, BoxError>(Value::Null)
                }
            })
        })
        .await;
        let _request = tokio::spawn(
            runtime
                .runtime
                .router()
                .oneshot(invoke_request(r#"{"input":1}"#)),
        );
        handler_started(&started).await;
        let stopped = tokio::time::Instant::now();
        assert!(matches!(
            runtime.stop().await,
            Err(crate::Error::ShutdownTimedOut)
        ));
        assert!(
            stopped.elapsed() < Duration::from_secs(1),
            "stop is bounded"
        );
        release.send(()).unwrap();
    }

    #[test]
    #[should_panic(expected = "register every handler before serving")]
    fn registering_after_serving_panics() {
        let runtime = Runtime::with_settings(settings());
        let _router = runtime.router();
        let _ = runtime.register("late", echo);
    }
}
```

Add the new modules to `sdks/rust/src/lib.rs`, which becomes:

```rust
//! nanofaas function SDK for Rust: a warm HTTP runtime serving `/invoke`, `/health` and
//! `/metrics` under the admission, byte-limit and callback rules of
//! `sdks/runtime-contract/README.md`.

mod bounded;
mod callback;
mod context;
mod dispatcher;
mod handler;
mod invoke;
mod limits;
mod metrics;
mod runtime;
mod settings;
mod types;

#[cfg(test)]
mod test_support;

pub use context::Context;
pub use handler::BoxError;
pub use runtime::{Error, Runtime};
pub use settings::RuntimeSettings;
pub use types::HandlerResponse;
```


- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: the tests compile, because they only use `crate::` paths, but `/invoke` is not routed yet: `test result: FAILED. 51 passed; 32 failed`, mostly with `left: 404`, after about 5 s. The waits for a handler to start give up after 5 s instead of hanging.

- [ ] **Step 3: Write the implementation**

Insert at the top of `sdks/rust/src/invoke.rs`, above `#[cfg(test)]`:

```rust
//! `/invoke`: admission, the handler's lifetime, and the terminal callback. Admission order,
//! codes and messages follow `sdks/go/nanofaas/http_invoke.go` so every runtime answers alike.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use axum::body::Body;
use axum::extract::{Request, State};
use axum::http::header::{CONTENT_LENGTH, CONTENT_TYPE, RETRY_AFTER};
use axum::http::{HeaderMap, HeaderName, HeaderValue, Method, StatusCode};
use axum::response::{IntoResponse, Response};
use bytes::Bytes;
use http_body_util::{BodyExt, LengthLimitError, Limited};
use serde_json::json;
use serde_json::value::RawValue;
use tokio::sync::oneshot;
use tokio::task::{JoinError, JoinHandle};
use tracing::Instrument;

use crate::bounded::{EncodeError, to_vec_bounded};
use crate::callback::Identity;
use crate::context::Context;
use crate::dispatcher::{
    CallbackReservation, MINIMUM_TERMINAL_CALLBACK_PAYLOAD_BYTES, SubmitError,
};
use crate::handler::{BoxError, HandlerFuture, Produced};
use crate::limits::{CountedBody, HandlerReservation};
use crate::runtime::{Shared, json_response};
use crate::types::{
    ENCODING_HEADER, FUNCTION_STATUS_HEADER, InvocationResult, WireRequest, filter_allowed_headers,
    is_status_code_valid,
};

/// Everything an admitted invocation owns. Dropping it releases both reservations.
struct Admitted {
    identity: Identity,
    future: HandlerFuture,
    cancelled: Arc<AtomicBool>,
    handler_reservation: Arc<HandlerReservation>,
    callback_reservation: CallbackReservation,
}

enum Finished {
    Done(Result<Result<Produced, BoxError>, JoinError>),
    TimedOut,
    Cancelled,
}

/// A function-decided status and headers, already validated and filtered.
struct Envelope {
    status: u16,
    headers: Vec<(String, String)>,
    encoding: Option<String>,
}

pub(crate) async fn invoke(State(shared): State<Arc<Shared>>, request: Request) -> Response {
    let admitted = match admit(&shared, request).await {
        Ok(admitted) => admitted,
        Err(response) => return response,
    };
    let (reply, replied) = oneshot::channel();
    // The invocation outlives this request future: if the client disconnects, axum drops us
    // and `run` sees its reply channel close, cancels the handler and still sends the callback.
    tokio::spawn(run(shared, admitted, reply));
    replied.await.unwrap_or_else(|_| {
        runtime_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "HANDLER_ERROR",
            "Handler failed",
        )
    })
}

// The error is the HTTP answer itself, returned once to axum rather than propagated.
#[allow(clippy::result_large_err)]
async fn admit(shared: &Arc<Shared>, request: Request) -> Result<Admitted, Response> {
    if request.method() != Method::POST {
        return Err(StatusCode::METHOD_NOT_ALLOWED.into_response());
    }
    if !shared.limits.snapshot().accepting {
        return Err(stopping());
    }
    let (parts, body) = request.into_parts();
    let (execution_id, trace_id) = shared.settings.resolve_identity(
        header(&parts.headers, "x-execution-id"),
        header(&parts.headers, "x-trace-id"),
    );
    let Some(execution_id) = execution_id else {
        return Err(legacy_error(
            StatusCode::BAD_REQUEST,
            "Execution ID not configured",
        ));
    };
    let identity = Identity {
        execution_id,
        trace_id,
        dispatch_attempt: header(&parts.headers, "x-dispatch-attempt").map(str::to_owned),
    };
    let Some(handler) = shared.resolve_handler().cloned() else {
        return Err(legacy_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "Handler not configured",
        ));
    };
    // Read-only checks first: occupied handler capacity is reported even when its terminal
    // callback reserve also fills the callback budget.
    if shared.limits.snapshot().active_handlers >= shared.settings.max_concurrent_handlers {
        return Err(handler_saturated());
    }
    if shared.dispatcher.max_callback_payload_bytes < MINIMUM_TERMINAL_CALLBACK_PAYLOAD_BYTES {
        return Err(callback_saturated());
    }
    let Some(callback_reservation) = shared.dispatcher.try_reserve() else {
        return Err(callback_saturated());
    };
    let Some(handler_reservation) = shared.limits.try_reserve_handler() else {
        return Err(if shared.limits.snapshot().accepting {
            handler_saturated()
        } else {
            stopping()
        });
    };
    let handler_reservation = Arc::new(handler_reservation);

    let body = read_body(body, &parts.headers, shared).await?;
    handler_reservation.retain_input(body.len());
    let wire: WireRequest = serde_json::from_slice(&body).map_err(|_| malformed())?;
    let cancelled = Arc::new(AtomicBool::new(false));
    let ctx = Context::new(
        identity.execution_id.clone(),
        identity.trace_id.clone(),
        wire.metadata,
        wire.headers,
        Arc::clone(&cancelled),
        Arc::clone(&handler_reservation),
    );
    let input = wire.input.map_or("null", RawValue::get);
    let future = handler(ctx, input, shared.settings.max_output_bytes).map_err(|_| malformed())?;
    Ok(Admitted {
        identity,
        future,
        cancelled,
        handler_reservation,
        callback_reservation,
    })
}

// The error is the HTTP answer itself, returned once to axum rather than propagated.
#[allow(clippy::result_large_err)]
async fn read_body(body: Body, headers: &HeaderMap, shared: &Shared) -> Result<Bytes, Response> {
    let max = shared.settings.max_input_bytes;
    let declared = header(headers, CONTENT_LENGTH.as_str()).and_then(|v| v.parse::<usize>().ok());
    if declared.is_some_and(|length| length > max) {
        return Err(input_too_large());
    }
    let read = Limited::new(body, max).collect();
    match tokio::time::timeout(shared.settings.body_read_timeout, read).await {
        Err(_) => Err(runtime_error(
            StatusCode::REQUEST_TIMEOUT,
            "RUNTIME_BODY_READ_TIMEOUT",
            "Runtime request body read timed out",
        )),
        Ok(Err(error)) if error.downcast_ref::<LengthLimitError>().is_some() => {
            Err(input_too_large())
        }
        Ok(Err(_)) => Err(malformed()),
        Ok(Ok(collected)) => Ok(collected.to_bytes()),
    }
}

async fn run(shared: Arc<Shared>, admitted: Admitted, mut reply: oneshot::Sender<Response>) {
    let Admitted {
        identity,
        future,
        cancelled,
        handler_reservation,
        callback_reservation,
    } = admitted;
    let cold_start = shared.cold_start.first_invocation();
    if cold_start {
        shared.metrics.cold_start();
    }
    shared.cold_start.mark_arrival();

    let span = tracing::info_span!(
        "invocation",
        execution_id = %identity.execution_id,
        trace_id = identity.trace_id.as_deref().unwrap_or_default(),
    );
    let started = Instant::now();
    // The task owns the handler slot as well as the context does: a handler that ignores its
    // context must not release the slot before its work is gone.
    let mut handle: JoinHandle<Result<Produced, BoxError>> = tokio::spawn(
        async move {
            let _reservation = handler_reservation;
            future.await
        }
        .instrument(span),
    );
    let finished = tokio::select! {
        joined = &mut handle => Finished::Done(joined),
        () = tokio::time::sleep(shared.settings.handler_timeout) => Finished::TimedOut,
        () = reply.closed() => Finished::Cancelled,
    };
    if !matches!(finished, Finished::Done(_)) {
        cancelled.store(true, Ordering::Release);
        handle.abort();
    }
    shared
        .metrics
        .handler_duration(started.elapsed().as_secs_f64());
    if let Some(response) = conclude(
        &shared,
        finished,
        callback_reservation,
        &identity,
        cold_start,
    ) {
        let _ = reply.send(response);
    }
}

/// Sends the terminal callback and builds the response; `None` when the client is gone.
fn conclude(
    shared: &Shared,
    finished: Finished,
    reservation: CallbackReservation,
    identity: &Identity,
    cold_start: bool,
) -> Option<Response> {
    match finished {
        Finished::Done(Ok(Ok(produced))) => {
            Some(succeed(shared, produced, reservation, identity, cold_start))
        }
        Finished::Done(Ok(Err(error))) => {
            tracing::error!(execution_id = %identity.execution_id, %error, "handler failed");
            Some(handler_failed(shared, reservation, identity))
        }
        Finished::Done(Err(panic)) => {
            tracing::error!(execution_id = %identity.execution_id, %panic, "handler panicked");
            Some(handler_failed(shared, reservation, identity))
        }
        Finished::TimedOut => {
            shared.metrics.invocation("timeout");
            let (code, message) = ("HANDLER_TIMEOUT", "Handler exceeded configured timeout");
            submit_failure(shared, reservation, identity, code, message);
            Some(runtime_error(StatusCode::GATEWAY_TIMEOUT, code, message))
        }
        Finished::Cancelled => {
            shared.metrics.invocation("error");
            let (code, message) = ("INVOCATION_CANCELLED", "Invocation cancelled");
            submit_failure(shared, reservation, identity, code, message);
            None
        }
    }
}

fn succeed(
    shared: &Shared,
    produced: Produced,
    reservation: CallbackReservation,
    identity: &Identity,
    cold_start: bool,
) -> Response {
    let (body, envelope) = match produced {
        Produced::Json(body) => (body, None),
        Produced::Unencodable(error) => return unencodable(shared, reservation, identity, error),
        Produced::Envelope(envelope) => {
            let (output, status, headers, encoding) = envelope.into_parts();
            if !is_status_code_valid(status) {
                let message = format!("Handler returned invalid statusCode: {status}");
                tracing::warn!(execution_id = %identity.execution_id, status, "treating an invalid statusCode as a platform error");
                return output_failure(
                    shared,
                    reservation,
                    identity,
                    "OUTPUT_SERIALIZATION_ERROR",
                    message,
                );
            }
            match to_vec_bounded(&output, shared.settings.max_output_bytes) {
                Ok(body) => (
                    body,
                    Some(Envelope {
                        status,
                        // A value HTTP cannot carry is dropped from the response and the
                        // callback alike, so the two never disagree.
                        headers: filter_allowed_headers(headers)
                            .into_iter()
                            .filter(|(_, value)| HeaderValue::from_str(value).is_ok())
                            .collect(),
                        encoding,
                    }),
                ),
                Err(error) => return unencodable(shared, reservation, identity, error),
            }
        }
    };

    let output: &RawValue = serde_json::from_slice(&body).expect("runtime-encoded JSON is valid");
    let result = match &envelope {
        None => InvocationResult::success(output),
        Some(envelope) => InvocationResult::envelope(
            output,
            envelope.status,
            &envelope.headers,
            envelope.encoding.as_deref(),
        ),
    };
    if let Err(rejected) = shared.dispatcher.submit(reservation, identity, &result) {
        return match rejected.error {
            SubmitError::TooLarge => unencodable(
                shared,
                rejected.reservation,
                identity,
                EncodeError::TooLarge,
            ),
            SubmitError::Serialization => unencodable(
                shared,
                rejected.reservation,
                identity,
                EncodeError::Serialization,
            ),
            SubmitError::Closed => {
                shared.metrics.callback_drop();
                stopping()
            }
        };
    }
    shared.metrics.invocation("success");

    let status = envelope.as_ref().map_or(200, |envelope| envelope.status);
    let length = body.len();
    let body = CountedBody::new(Bytes::from(body), shared.limits.retain_output(length));
    let mut response = Response::new(Body::new(body));
    *response.status_mut() = StatusCode::from_u16(status).expect("validated as 200..=599");
    let headers = response.headers_mut();
    if let Some(envelope) = &envelope {
        for (name, value) in &envelope.headers {
            if let (Ok(name), Ok(value)) =
                (HeaderName::try_from(name), HeaderValue::try_from(value))
            {
                headers.insert(name, value);
            }
        }
        headers.insert(FUNCTION_STATUS_HEADER, HeaderValue::from_static("true"));
        if let Some(Ok(encoding)) = envelope.encoding.as_deref().map(HeaderValue::try_from) {
            headers.insert(ENCODING_HEADER, encoding);
        }
    }
    if cold_start {
        headers.insert("x-cold-start", HeaderValue::from_static("true"));
        headers.insert(
            "x-init-duration-ms",
            HeaderValue::from(shared.cold_start.init_duration_ms() as u64),
        );
    }
    headers
        .entry(CONTENT_TYPE)
        .or_insert(HeaderValue::from_static("application/json"));
    response
}

fn unencodable(
    shared: &Shared,
    reservation: CallbackReservation,
    identity: &Identity,
    error: EncodeError,
) -> Response {
    match error {
        EncodeError::TooLarge => output_failure(
            shared,
            reservation,
            identity,
            "RUNTIME_OUTPUT_TOO_LARGE",
            "Runtime output exceeds configured byte limit".into(),
        ),
        EncodeError::Serialization => output_failure(
            shared,
            reservation,
            identity,
            "OUTPUT_SERIALIZATION_ERROR",
            "Handler output could not be serialized".into(),
        ),
    }
}

fn output_failure(
    shared: &Shared,
    reservation: CallbackReservation,
    identity: &Identity,
    code: &'static str,
    message: String,
) -> Response {
    shared.metrics.invocation("error");
    submit_failure(shared, reservation, identity, code, &message);
    runtime_error(StatusCode::INTERNAL_SERVER_ERROR, code, &message)
}

fn handler_failed(
    shared: &Shared,
    reservation: CallbackReservation,
    identity: &Identity,
) -> Response {
    output_failure(
        shared,
        reservation,
        identity,
        "HANDLER_ERROR",
        "Handler failed".into(),
    )
}

fn submit_failure(
    shared: &Shared,
    reservation: CallbackReservation,
    identity: &Identity,
    code: &'static str,
    message: &str,
) {
    let result = InvocationResult::failure(code, message);
    if shared
        .dispatcher
        .submit(reservation, identity, &result)
        .is_err()
    {
        shared.metrics.callback_drop();
    }
}

fn header<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers.get(name).and_then(|value| value.to_str().ok())
}

fn runtime_error(status: StatusCode, code: &str, message: &str) -> Response {
    json_response(
        status,
        &json!({"error": {"code": code, "message": message}}),
    )
}

fn retryable(status: StatusCode, code: &str, message: &str) -> Response {
    let mut response = runtime_error(status, code, message);
    response
        .headers_mut()
        .insert(RETRY_AFTER, HeaderValue::from_static("1"));
    response
}

/// The pre-contract `{"error": "<message>"}` shape, kept for the requests the Go SDK answers so.
fn legacy_error(status: StatusCode, message: &str) -> Response {
    json_response(status, &json!({"error": message}))
}

fn stopping() -> Response {
    retryable(
        StatusCode::SERVICE_UNAVAILABLE,
        "RUNTIME_STOPPING",
        "Runtime is stopping",
    )
}

fn handler_saturated() -> Response {
    retryable(
        StatusCode::TOO_MANY_REQUESTS,
        "RUNTIME_HANDLER_SATURATED",
        "Runtime handler capacity exhausted",
    )
}

fn callback_saturated() -> Response {
    retryable(
        StatusCode::TOO_MANY_REQUESTS,
        "RUNTIME_CALLBACK_SATURATED",
        "Runtime callback capacity exhausted",
    )
}

fn input_too_large() -> Response {
    runtime_error(
        StatusCode::PAYLOAD_TOO_LARGE,
        "RUNTIME_INPUT_TOO_LARGE",
        "Runtime input exceeds configured byte limit",
    )
}

fn malformed() -> Response {
    legacy_error(StatusCode::BAD_REQUEST, "Malformed request body")
}
```

Then wire the route in `sdks/rust/src/runtime.rs`. Add the import next to the other `crate::` imports:

```rust
use crate::handler::{self, BoxError, ErasedHandler};
use crate::invoke;
```

and add the route as the first one in `router()`:

```rust
        Router::new()
            .route("/invoke", any(invoke::invoke))
            .route("/health", any(health))
```


- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: `test result: ok. 83 passed` for the library. Warnings about unused items are expected until Task 8 wires every module into `/invoke`.

- [ ] **Step 5: Commit**

Stage, run the GitNexus change analysis CLAUDE.md requires (new Rust files typically report `changed_count: 0`; a `partial` or `error` flag is not a pass — rerun), then commit:

```bash
cd /home/michele/Documenti/nanofaas
git add sdks/rust/src/lib.rs sdks/rust/src/runtime.rs sdks/rust/src/invoke.rs
GITNEXUS="$(python3 -c "import json;print(json.load(open('.gitnexus/meta.json'))['runnerIdentity']['invokedArtifact']['path'].split('/dist/')[0])")"
node --input-type=module -e "const {LocalBackend}=await import('$GITNEXUS/dist/mcp/local/local-backend.js'); console.log(JSON.stringify(await new LocalBackend().callTool('detect_changes',{scope:'staged',repo:'nanofaas'}))); process.exit(0)"
git commit -F - <<'EOF'
Add /invoke to the Rust SDK runtime

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019F6VX47yLJ3prsE2mDcaU1
EOF
```

---

### Task 9: Saturation corpus conformance

**Files:**
- Create: `sdks/rust/src/corpus_tests.rs` (test-only)
- Modify: `sdks/rust/src/lib.rs`

**Interfaces:**
- Consumes: `Runtime`, `RunState`, `Shared` fields, `Dispatcher::{try_reserve, submit, wait_until_idle, snapshot}`, `Limits::{try_reserve_handler, wait_until_idle, snapshot}`, `InvocationResult::failure`, `Identity`, `FakeCallbackServer`, `Reply`, `RecordedCallback`.
- Produces: the test `corpus_tests::consumes_the_shared_runtime_saturation_wire_contract`. It needs `python3` for `sdks/runtime-contract/validate_saturation_wire_corpus.py`. `NANOFAAS_SATURATION_CORPUS` overrides the corpus path, as in the Go adapter.

This is the Rust counterpart of `sdks/go/nanofaas/saturation_wire_corpus_test.go` and `saturation_runtime_adapter_test.go`. It runs the shared validator with a 10 s deadline, parses the corpus into typed structs with `deny_unknown_fields`, and executes all twelve scenarios through the real router, dispatcher and counters, each within its own `deadlineMs`. Two Rust-specific details:
- A blocked handler observes cancellation through `Drop`, because the runtime drops its future. `CancelProbe` records `cancelRequested` from `Context::is_cancelled()`.
- `begin-stop` waits for "no longer `Running`", because a fast stop can already be back to `Idle` before the harness looks.

- [ ] **Step 1: Write the conformance test**

Create `sdks/rust/src/corpus_tests.rs`:

```rust
//! Executes `sdks/runtime-contract/saturation-wire-corpus.json` against this runtime: first the
//! shared Python validator, then a typed parse that rejects unknown fields, then every scenario
//! through the real router, callback dispatcher and counters.

// Several model fields exist only so `deny_unknown_fields` proves the whole corpus parses.
#![allow(dead_code)]

use std::collections::{BTreeMap, HashMap};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use axum::body::Body;
use axum::http::{HeaderMap, Request};
use http_body_util::BodyExt;
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::net::TcpListener;
use tokio::sync::{oneshot, watch};
use tokio::task::JoinHandle;
use tower::ServiceExt;

use crate::callback::Identity;
use crate::dispatcher::{CallbackReservation, CallbackSnapshot};
use crate::limits::HandlerReservation;
use crate::runtime::RunState;
use crate::test_support::{FakeCallbackServer, RecordedCallback, Reply};
use crate::types::InvocationResult;
use crate::{BoxError, Context, Error, Runtime, RuntimeSettings};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Corpus {
    schema_version: String,
    contract_definitions: HashMap<String, Definitions>,
    policy: Policy,
    runtime_configurations: HashMap<String, RuntimeConfig>,
    scenarios: Vec<Scenario>,
    mutation_tests: Vec<Value>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Policy {
    maximum_scenario_deadline_ms: u64,
    definitions_ref: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Definitions {
    vocabulary: HashMap<String, Vec<String>>,
    actor_action_compatibility: HashMap<String, Vec<String>>,
    handler_lifecycles: HashMap<String, HandlerLifecycle>,
    handler_behavior_lifecycle_refs: HashMap<String, Vec<String>>,
    callback_lifecycles: HashMap<String, CallbackLifecycle>,
    callback_behavior_lifecycle_refs: HashMap<String, Vec<String>>,
    wire_outcomes: HashMap<String, WireOutcome>,
    callback_envelopes: HashMap<String, CallbackEnvelope>,
    callback_request_template: CallbackRequestTemplate,
    size_relation_operators: HashMap<String, String>,
    final_counters_rule: Expression,
    identity_rules: Vec<Rule>,
    cross_field_rules: Vec<Rule>,
    observation_sets: HashMap<String, Vec<String>>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct HandlerLifecycle {
    started: Option<bool>,
    cancel_requested: Option<bool>,
    terminal: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CallbackLifecycle {
    required: Option<bool>,
    attempted: Option<bool>,
    delivered: Option<bool>,
    attempts: Expression,
    terminal: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct WireOutcome {
    connection_outcome: String,
    status: u16,
    body: Value,
    required_headers: HashMap<String, String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CallbackEnvelope {
    emits_request: Option<bool>,
    payload: Value,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CallbackRequestTemplate {
    method: String,
    url: Expression,
    headers: HashMap<String, Expression>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Expression {
    operator: String,
    value: Option<Value>,
    path: Option<String>,
    callback_url_path: Option<String>,
    execution_id_path: Option<String>,
    suffix: Option<String>,
    field: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Rule {
    id: String,
    scope: String,
    operator: String,
    source: String,
    expected: Option<String>,
    ignore_null: Option<bool>,
    increment: Option<i64>,
    value: Option<Value>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RuntimeConfig {
    max_concurrent_handlers: usize,
    max_input_bytes: usize,
    max_output_bytes: usize,
    max_pending_callbacks: usize,
    max_pending_callback_bytes: usize,
    handler_timeout_ms: u64,
    callback_attempt_timeout_ms: u64,
    callback_max_attempts: usize,
    body_read_timeout_ms: u64,
    shutdown_timeout_ms: u64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Scenario {
    id: String,
    kind: String,
    implementation_owners: Vec<String>,
    runtime_config_ref: String,
    requests: Vec<CorpusRequest>,
    backend: Backend,
    harness: HarnessPlan,
    initial_counters: BTreeMap<String, usize>,
    expected: Expected,
    deadline_ms: u64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CorpusRequest {
    id: String,
    role: String,
    method: String,
    path: String,
    metadata: Metadata,
    payload: Payload,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Metadata {
    execution_id: Option<String>,
    dispatch_attempt: Option<u32>,
    trace_id: Option<String>,
    callback_url: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Payload {
    input_bytes: usize,
    relation_to_input_limit: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Backend {
    handlers: Vec<HandlerBackend>,
    callbacks: Vec<CallbackBackend>,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct HandlerBackend {
    request_id: String,
    behavior: String,
    output_bytes: usize,
    output_relation_to_limit: String,
    barrier: Option<String>,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CallbackBackend {
    request_id: String,
    behavior: String,
    barrier: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct HarnessPlan {
    barriers: Vec<BarrierPlan>,
    actions: Vec<Action>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct BarrierPlan {
    id: String,
    initial_state: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Action {
    sequence: u32,
    actor: String,
    action: String,
    request_id: Option<String>,
    barrier: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Expected {
    responses: Vec<ExpectedResponse>,
    handlers: Vec<ExpectedHandler>,
    callbacks: Vec<ExpectedCallback>,
    identity: ExpectedIdentity,
    observation_set_ref: String,
    observations: Vec<String>,
    final_counters: BTreeMap<String, usize>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ExpectedResponse {
    request_id: String,
    outcome_ref: String,
    connection_outcome: String,
    status: u16,
    body: Value,
    required_headers: HashMap<String, String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ExpectedHandler {
    request_id: String,
    lifecycle_ref: String,
    started: Option<bool>,
    cancel_requested: Option<bool>,
    terminal: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ExpectedCallback {
    request_id: String,
    lifecycle_ref: String,
    envelope_ref: String,
    required: Option<bool>,
    attempted: Option<bool>,
    delivered: Option<bool>,
    attempts: usize,
    terminal: String,
    dispatch_attempts: Vec<u32>,
    request_projection: Option<Projection>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Projection {
    method: String,
    url: String,
    headers: HashMap<String, String>,
    payload: Value,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ExpectedIdentity {
    execution_id: Option<String>,
    request_dispatch_attempts: Vec<u32>,
    runtime_redispatch_count: u32,
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn consumes_the_shared_runtime_saturation_wire_contract() {
    let path = corpus_path();
    run_shared_validator(&path);
    let corpus: Corpus = serde_json::from_slice(&std::fs::read(&path).unwrap())
        .expect("the corpus parses into the typed model");
    let definitions = &corpus.contract_definitions[&corpus.policy.definitions_ref];
    assert_eq!(
        corpus.scenarios.len(),
        definitions.vocabulary["scenarioKinds"].len()
    );
    assert!(
        !corpus.mutation_tests.is_empty(),
        "mutation fixtures are required"
    );
    let logs = capture_logs();
    for scenario in &corpus.scenarios {
        assert_typed_projection(corpus.policy.maximum_scenario_deadline_ms, scenario);
        let config = &corpus.runtime_configurations[&scenario.runtime_config_ref];
        let deadline = Duration::from_millis(scenario.deadline_ms);
        tokio::time::timeout(deadline, run_scenario(scenario, config, &logs))
            .await
            .unwrap_or_else(|_| panic!("{} exceeded its {deadline:?} deadline", scenario.id));
    }
}

fn corpus_path() -> PathBuf {
    std::env::var_os("NANOFAAS_SATURATION_CORPUS").map_or_else(
        || {
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../runtime-contract/saturation-wire-corpus.json")
        },
        PathBuf::from,
    )
}

/// Runs the language-neutral validator (schema, references, mutations) with a 10 s deadline.
fn run_shared_validator(corpus: &Path) {
    let validator = corpus.with_file_name("validate_saturation_wire_corpus.py");
    let mut child = Command::new("python3")
        .arg(&validator)
        .arg("--run-mutations")
        .arg(corpus)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("python3 runs the shared validator");
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    while child.try_wait().unwrap().is_none() {
        if std::time::Instant::now() > deadline {
            let _ = child.kill();
            panic!("the shared validator exceeded its 10 s deadline");
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    let output = child.wait_with_output().unwrap();
    assert!(
        output.status.success(),
        "shared validator failed:\n{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

fn assert_typed_projection(maximum_deadline_ms: u64, scenario: &Scenario) {
    let id = &scenario.id;
    assert!(!id.is_empty() && !scenario.kind.is_empty() && !scenario.runtime_config_ref.is_empty());
    assert!(!scenario.requests.is_empty(), "{id}: requests");
    assert_eq!(
        scenario.backend.handlers.len(),
        scenario.requests.len(),
        "{id}: handlers"
    );
    assert_eq!(
        scenario.backend.callbacks.len(),
        scenario.requests.len(),
        "{id}: callbacks"
    );
    assert!(!scenario.harness.actions.is_empty(), "{id}: actions");
    assert!(
        !scenario.expected.observations.is_empty(),
        "{id}: observations"
    );
    assert!(
        scenario.deadline_ms > 0 && scenario.deadline_ms <= maximum_deadline_ms,
        "{id}: deadline"
    );
    for handler in &scenario.expected.handlers {
        assert!(
            handler.started.is_some() && handler.cancel_requested.is_some(),
            "{id}: handler booleans"
        );
    }
    for callback in &scenario.expected.callbacks {
        assert!(
            callback.required.is_some()
                && callback.attempted.is_some()
                && callback.delivered.is_some(),
            "{id}: callback booleans"
        );
        assert!(!callback.lifecycle_ref.is_empty() && !callback.envelope_ref.is_empty());
        assert!(
            callback.attempts == 0 || callback.request_projection.is_some(),
            "{id}: projection"
        );
    }
    assert!(
        scenario
            .expected
            .final_counters
            .values()
            .all(|count| *count == 0),
        "{id}: undrained"
    );
}

/// Captures every `tracing` event as JSON lines, once per test binary.
fn capture_logs() -> Arc<Mutex<Vec<u8>>> {
    static LOGS: OnceLock<Arc<Mutex<Vec<u8>>>> = OnceLock::new();
    LOGS.get_or_init(|| {
        let buffer = Arc::new(Mutex::new(Vec::new()));
        let sink = Arc::clone(&buffer);
        let _ = tracing_subscriber::fmt()
            .json()
            .with_writer(move || LogWriter(Arc::clone(&sink)))
            .try_init();
        buffer
    })
    .clone()
}

struct LogWriter(Arc<Mutex<Vec<u8>>>);

impl Write for LogWriter {
    fn write(&mut self, data: &[u8]) -> std::io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(data);
        Ok(data.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

#[derive(Clone, Debug, Default)]
struct HandlerObservation {
    started: bool,
    cancel_requested: bool,
    terminal: String,
}

/// What the corpus handler needs: its scripted behavior and where to record what happened.
struct World {
    kind: String,
    max_output_bytes: usize,
    backends: HashMap<String, HandlerBackend>,
    barriers: HashMap<String, watch::Sender<bool>>,
    handlers: Mutex<HashMap<String, HandlerObservation>>,
    redispatched: Mutex<bool>,
}

impl World {
    fn open_barrier(&self, name: &str) {
        self.barriers[name].send_replace(true);
    }

    fn record(&self, request_id: &str, change: impl FnOnce(&mut HandlerObservation)) {
        change(
            self.handlers
                .lock()
                .unwrap()
                .entry(request_id.to_owned())
                .or_default(),
        );
    }
}

/// Records the runtime's cancellation of a blocked handler: its future is dropped.
struct CancelProbe {
    world: Arc<World>,
    request_id: String,
    ctx: Context,
}

impl Drop for CancelProbe {
    fn drop(&mut self) {
        let terminal = if self.world.kind == "handler-timeout" {
            "timed-out"
        } else {
            "cancelled"
        };
        let cancel_requested = self.ctx.is_cancelled();
        self.world.record(&self.request_id, |observed| {
            observed.cancel_requested = cancel_requested;
            observed.terminal = terminal.to_owned();
        });
    }
}

async fn corpus_handler(world: Arc<World>, ctx: Context) -> Result<Value, BoxError> {
    let request_id = ctx.metadata()["requestId"].clone();
    let backend = world.backends[&request_id].clone();
    {
        let mut handlers = world.handlers.lock().unwrap();
        if handlers.contains_key(&request_id) {
            *world.redispatched.lock().unwrap() = true;
            return Err("runtime redispatched a request".into());
        }
        handlers.insert(
            request_id.clone(),
            HandlerObservation {
                started: true,
                ..Default::default()
            },
        );
    }
    if let Some(barrier) = &backend.barrier {
        world.open_barrier(barrier);
    }
    match backend.behavior.as_str() {
        "succeed" if backend.output_relation_to_limit == "above-limit" => {
            world.record(&request_id, |o| o.terminal = "output-rejected".into());
            Ok(Value::String("x".repeat(world.max_output_bytes + 1)))
        }
        "succeed" => {
            world.record(&request_id, |o| o.terminal = "succeeded".into());
            Ok(json!({"result": "ok"}))
        }
        "fail" => {
            world.record(&request_id, |o| o.terminal = "failed".into());
            Err("corpus handler failure".into())
        }
        "block-until-cancelled" => {
            let _probe = CancelProbe {
                world: Arc::clone(&world),
                request_id,
                ctx,
            };
            std::future::pending::<()>().await;
            unreachable!("only cancellation ends a blocked handler")
        }
        other => Err(format!("unexpected invoked behavior {other}").into()),
    }
}

struct Sent {
    task: Option<JoinHandle<(u16, HeaderMap, Vec<u8>)>>,
    result: Option<(u16, HeaderMap, Vec<u8>)>,
    cancelled: bool,
}

struct Harness<'a> {
    scenario: &'a Scenario,
    world: Arc<World>,
    runtime: Arc<Runtime>,
    callbacks: FakeCallbackServer,
    sent: HashMap<String, Sent>,
    stop_signal: Option<oneshot::Sender<()>>,
    served: Option<JoinHandle<Result<(), Error>>>,
    held_callback: Option<CallbackReservation>,
    held_handler: Option<HandlerReservation>,
    starts: usize,
    stops: usize,
}

async fn run_scenario(scenario: &Scenario, config: &RuntimeConfig, logs: &Arc<Mutex<Vec<u8>>>) {
    let mut harness = Harness::new(scenario, config).await;
    let mut initial_checked = false;
    for action in &scenario.harness.actions {
        if !initial_checked
            && matches!(
                action.action.as_str(),
                "send-request" | "probe-health" | "begin-stop"
            )
        {
            harness.verify_counters("initial", &scenario.initial_counters);
            initial_checked = true;
        }
        harness.run(action).await;
    }
    assert!(
        initial_checked,
        "{}: initial counters never observed",
        scenario.id
    );
    harness.drain();
    harness.runtime.shared().limits.wait_until_idle().await;
    if harness.served.is_some() {
        harness.begin_stop();
        harness.await_stop().await;
    }
    harness.verify(logs);
}

impl<'a> Harness<'a> {
    async fn new(scenario: &'a Scenario, config: &RuntimeConfig) -> Self {
        let world = Arc::new(World {
            kind: scenario.kind.clone(),
            max_output_bytes: config.max_output_bytes,
            backends: scenario
                .backend
                .handlers
                .iter()
                .map(|backend| (backend.request_id.clone(), backend.clone()))
                .collect(),
            barriers: scenario
                .harness
                .barriers
                .iter()
                .map(|barrier| (barrier.id.clone(), watch::channel(false).0))
                .collect(),
            handlers: Mutex::new(HashMap::new()),
            redispatched: Mutex::new(false),
        });
        let callbacks = FakeCallbackServer::start(callback_reply(scenario)).await;
        let initial = &scenario.initial_counters;
        let max_callback_payload_bytes = match initial["pendingCallbacks"] {
            0 => config.max_pending_callback_bytes,
            count => initial["pendingCallbackBytes"] / count,
        };
        let settings = RuntimeSettings {
            callback_url: Some(callbacks.url.clone()),
            handler_timeout: Duration::from_millis(config.handler_timeout_ms),
            max_concurrent_handlers: config.max_concurrent_handlers,
            max_input_bytes: config.max_input_bytes,
            max_output_bytes: config.max_output_bytes,
            max_pending_callbacks: config.max_pending_callbacks,
            max_pending_callback_bytes: config.max_pending_callback_bytes,
            max_callback_payload_bytes,
            body_read_timeout: Duration::from_millis(config.body_read_timeout_ms),
            callback_attempt_timeout: Duration::from_millis(config.callback_attempt_timeout_ms),
            callback_max_attempts: config.callback_max_attempts,
            shutdown_timeout: Duration::from_millis(config.shutdown_timeout_ms),
            ..RuntimeSettings::default()
        };
        let handler_world = Arc::clone(&world);
        let runtime = Runtime::with_settings(settings)
            .register("corpus", move |ctx: Context, _: Value| {
                corpus_handler(Arc::clone(&handler_world), ctx)
            })
            .without_callback_backoff();
        Self {
            scenario,
            world,
            runtime: Arc::new(runtime),
            callbacks,
            sent: HashMap::new(),
            stop_signal: None,
            served: None,
            held_callback: None,
            held_handler: None,
            starts: 0,
            stops: 0,
        }
    }

    async fn run(&mut self, action: &Action) {
        let request_id = || {
            action
                .request_id
                .clone()
                .expect("the action names a request")
        };
        let barrier = || action.barrier.clone().expect("the action names a barrier");
        match action.action.as_str() {
            "start-runtime" | "start-runtime-again" => self.start().await,
            "send-request" | "probe-health" => self.send(&request_id()),
            "await-response" => self.await_response(&request_id()).await,
            "await-callback" => self.await_callback(&request_id()).await,
            "await-barrier" => {
                let mut opened = self.world.barriers[&barrier()].subscribe();
                opened.wait_for(|open| *open).await.unwrap();
            }
            "release-barrier" => self.world.open_barrier(&barrier()),
            "cancel-request" => {
                let sent = self.sent.get_mut(&request_id()).unwrap();
                sent.task.take().unwrap().abort();
                sent.cancelled = true;
            }
            "fill-callback-capacity" | "fill-handler-capacity" => self.fill_capacity().await,
            "drain-callbacks" | "drain-handlers" => self.drain(),
            "begin-stop" => {
                self.begin_stop();
                let mut state = self.runtime.state();
                // A fast stop can already be back to Idle: anything but Running means it began.
                state
                    .wait_for(|state| *state != RunState::Running)
                    .await
                    .unwrap();
                if let Some(barrier) = &action.barrier {
                    self.world.open_barrier(barrier);
                }
            }
            "await-stop" => self.await_stop().await,
            // The next send-request is deliberately a new control-plane request.
            "control-plane-redispatch" => {}
            other => panic!("unsupported corpus action {other}"),
        }
    }

    async fn start(&mut self) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let (stop, stopped) = oneshot::channel::<()>();
        let runtime = Arc::clone(&self.runtime);
        let served = tokio::spawn(async move {
            runtime
                .serve(listener, async move {
                    let _ = stopped.await;
                })
                .await
        });
        self.runtime
            .state()
            .wait_for(|state| *state == RunState::Running)
            .await
            .unwrap();
        self.stop_signal = Some(stop);
        self.served = Some(served);
        self.starts += 1;
    }

    fn begin_stop(&mut self) {
        let _ = self.stop_signal.take().expect("serving").send(());
    }

    async fn await_stop(&mut self) {
        let served = self.served.take().expect("serving");
        served.await.unwrap().expect("the runtime stops cleanly");
        self.stops += 1;
    }

    fn send(&mut self, request_id: &str) {
        let request = self.request(request_id);
        let mut builder = Request::builder()
            .method(request.method.as_str())
            .uri(&request.path);
        let metadata = &request.metadata;
        if let Some(id) = &metadata.execution_id {
            builder = builder.header("x-execution-id", id);
        }
        if let Some(id) = &metadata.trace_id {
            builder = builder.header("x-trace-id", id);
        }
        if let Some(attempt) = metadata.dispatch_attempt {
            builder = builder.header("x-dispatch-attempt", attempt.to_string());
        }
        let body = match request.role.as_str() {
            "invoke" => Body::from(sized_body(request_id, request.payload.input_bytes)),
            _ => Body::empty(),
        };
        let router = self.runtime.router();
        let request = builder.body(body).unwrap();
        let task = tokio::spawn(async move {
            let response = router.oneshot(request).await.unwrap();
            let (parts, body) = response.into_parts();
            let bytes = body.collect().await.unwrap().to_bytes().to_vec();
            (parts.status.as_u16(), parts.headers, bytes)
        });
        self.sent.insert(
            request_id.to_owned(),
            Sent {
                task: Some(task),
                result: None,
                cancelled: false,
            },
        );
    }

    async fn await_response(&mut self, request_id: &str) {
        let sent = self.sent.get_mut(request_id).expect("sent");
        sent.result = Some(sent.task.take().expect("pending").await.unwrap());
    }

    async fn await_callback(&self, request_id: &str) {
        let expected = self.expected_callback(request_id);
        let execution_id = self.request(request_id).metadata.execution_id.clone();
        self.callbacks
            .wait_for(expected.attempts, |recorded| {
                Some(execution_id_of(recorded)) == execution_id.as_deref()
            })
            .await;
        if expected.attempts > 0 {
            self.runtime.shared().dispatcher.wait_until_idle().await;
        }
    }

    /// Brings the counters to the scenario's `initialCounters`: a held callback reservation
    /// (optionally serialized and parked in delivery) and a held handler slot.
    async fn fill_capacity(&mut self) {
        let want = &self.scenario.initial_counters;
        let shared = self.runtime.shared();
        if want["pendingCallbacks"] > 0 && shared.dispatcher.snapshot().pending_callbacks == 0 {
            let reservation = shared.dispatcher.try_reserve().expect("callback capacity");
            let serialized = want["serializedCallbackBytes"];
            if serialized == 0 {
                self.held_callback = Some(reservation);
            } else {
                let base = serde_json::to_vec(&InvocationResult::failure("CAPACITY", ""))
                    .unwrap()
                    .len();
                let padding = "x".repeat(serialized - base);
                let identity = Identity {
                    execution_id: "capacity".into(),
                    trace_id: None,
                    dispatch_attempt: Some("1".into()),
                };
                shared
                    .dispatcher
                    .submit(
                        reservation,
                        &identity,
                        &InvocationResult::failure("CAPACITY", padding),
                    )
                    .unwrap_or_else(|_| panic!("capacity callback refused"));
                self.callbacks
                    .wait_for(1, |r| r.path == "/capacity:complete")
                    .await;
            }
        }
        if want["activeHandlers"] > 0 && self.held_handler.is_none() {
            let reservation = shared
                .limits
                .try_reserve_handler()
                .expect("handler capacity");
            reservation.retain_input(want["inputBytes"]);
            self.held_handler = Some(reservation);
        }
    }

    fn drain(&mut self) {
        self.callbacks.release_held();
        self.held_callback = None;
        self.held_handler = None;
    }

    fn verify(&self, logs: &Arc<Mutex<Vec<u8>>>) {
        let id = &self.scenario.id;
        for expected in &self.scenario.expected.responses {
            let sent = &self.sent[&expected.request_id];
            if expected.connection_outcome == "client-disconnected" {
                assert!(
                    sent.cancelled && sent.result.is_none(),
                    "{id}: expected no response"
                );
                continue;
            }
            let (status, headers, body) = sent.result.as_ref().expect("response awaited");
            assert_eq!(*status, expected.status, "{id}: status");
            let body: Value = serde_json::from_slice(body).unwrap();
            assert_eq!(body, expected.body, "{id}: body");
            for (name, value) in &expected.required_headers {
                let actual = headers.get(name).map(|v| v.to_str().unwrap().to_owned());
                assert!(
                    actual.is_some_and(|a| a.eq_ignore_ascii_case(value)),
                    "{id}: header {name}"
                );
            }
        }
        let handlers = self.world.handlers.lock().unwrap().clone();
        for expected in &self.scenario.expected.handlers {
            let actual = handlers.get(&expected.request_id);
            if expected.started == Some(false) {
                assert!(actual.is_none(), "{id}: handler unexpectedly started");
                continue;
            }
            let actual = actual.unwrap_or_else(|| panic!("{id}: handler never started"));
            assert!(actual.started, "{id}: started");
            assert_eq!(
                Some(actual.cancel_requested),
                expected.cancel_requested,
                "{id}: cancel"
            );
            assert_eq!(actual.terminal, expected.terminal, "{id}: terminal");
        }
        for expected in &self.scenario.expected.callbacks {
            self.verify_callback(expected);
        }
        self.verify_counters("final", &self.scenario.expected.final_counters);
        self.verify_observations(&handlers, logs);
    }

    fn verify_callback(&self, expected: &ExpectedCallback) {
        let id = &self.scenario.id;
        let request = self.request(&expected.request_id);
        let attempts: Vec<RecordedCallback> = self
            .callbacks
            .received()
            .into_iter()
            .filter(|r| {
                Some(execution_id_of(r)) == request.metadata.execution_id.as_deref()
                    && r.headers
                        .get("x-dispatch-attempt")
                        .and_then(|v| v.to_str().ok())
                        == request
                            .metadata
                            .dispatch_attempt
                            .map(|a| a.to_string())
                            .as_deref()
            })
            .collect();
        assert_eq!(attempts.len(), expected.attempts, "{id}: callback attempts");
        let delivered = attempts.iter().any(|r| (200..300).contains(&r.status));
        assert_eq!(Some(delivered), expected.delivered, "{id}: delivered");
        let dispatch: Vec<u32> = attempts
            .iter()
            .map(|r| {
                r.headers["x-dispatch-attempt"]
                    .to_str()
                    .unwrap()
                    .parse()
                    .unwrap()
            })
            .collect();
        assert_eq!(
            dispatch, expected.dispatch_attempts,
            "{id}: dispatch attempts"
        );
        if let (Some(projection), Some(last)) = (&expected.request_projection, attempts.last()) {
            let path = projection.url.split_once("://").unwrap().1;
            let path = &path[path.find('/').unwrap()..];
            assert_eq!(last.method, projection.method, "{id}: callback method");
            assert_eq!(last.path, path, "{id}: callback path");
            assert_eq!(last.body, projection.payload, "{id}: callback payload");
            for (name, value) in &projection.headers {
                assert_eq!(
                    last.headers[name.as_str()],
                    value.as_str(),
                    "{id}: callback {name}"
                );
            }
        }
    }

    fn verify_counters(&self, phase: &str, expected: &BTreeMap<String, usize>) {
        let shared = self.runtime.shared();
        let limits = shared.limits.snapshot();
        let callbacks: CallbackSnapshot = shared.dispatcher.snapshot();
        let actual = BTreeMap::from([
            ("activeHandlers".to_owned(), limits.active_handlers),
            ("inputBytes".to_owned(), limits.input_bytes),
            ("outputBytes".to_owned(), limits.output_bytes),
            ("pendingCallbacks".to_owned(), callbacks.pending_callbacks),
            (
                "pendingCallbackBytes".to_owned(),
                callbacks.pending_callback_bytes,
            ),
            (
                "serializedCallbackBytes".to_owned(),
                callbacks.serialized_callback_bytes,
            ),
        ]);
        assert_eq!(&actual, expected, "{}: {phase} counters", self.scenario.id);
    }

    fn verify_observations(
        &self,
        handlers: &HashMap<String, HandlerObservation>,
        logs: &Arc<Mutex<Vec<u8>>>,
    ) {
        let shared = self.runtime.shared();
        let real: Vec<RecordedCallback> = self
            .callbacks
            .received()
            .into_iter()
            .filter(|r| r.path != "/capacity:complete")
            .collect();
        let mut observed: HashMap<&str, bool> = HashMap::new();
        observed.insert(
            "wire-response",
            self.sent
                .values()
                .any(|s| s.result.as_ref().is_some_and(|r| !r.2.is_empty())),
        );
        observed.insert(
            "no-wire-response",
            self.sent
                .values()
                .any(|s| s.cancelled && s.result.is_none()),
        );
        observed.insert(
            "health-response",
            self.sent.iter().any(|(id, s)| {
                self.request(id).role == "health" && s.result.as_ref().is_some_and(|r| r.0 == 200)
            }),
        );
        observed.insert("handler-start", handlers.values().any(|h| h.started));
        observed.insert(
            "handler-cancel",
            handlers.values().any(|h| h.cancel_requested),
        );
        observed.insert("callback-attempt", !real.is_empty());
        observed.insert(
            "callback-delivery",
            real.iter().any(|r| (200..300).contains(&r.status)),
        );
        observed.insert("stop-complete", self.stops > 0);
        observed.insert("restart-complete", self.starts > 1 && self.stops > 0);
        let limits = shared.limits.snapshot();
        observed.insert(
            "counters-zero",
            (
                limits.active_handlers,
                limits.input_bytes,
                limits.output_bytes,
            ) == (0, 0, 0)
                && shared.dispatcher.snapshot() == CallbackSnapshot::default(),
        );
        observed.insert(
            "callback-failure-metric",
            shared.metrics.callback_drops.get() > 0,
        );
        observed.insert(
            "runtime-redispatch-zero",
            !*self.world.redispatched.lock().unwrap() && handlers.len() <= self.sent.len(),
        );
        let execution_ids: Vec<&str> = self
            .scenario
            .requests
            .iter()
            .filter_map(|r| r.metadata.execution_id.as_deref())
            .collect();
        observed.insert("structured-log", logged_exhaustion(logs, &execution_ids));
        for name in &self.scenario.expected.observations {
            assert!(
                observed.get(name.as_str()).copied().unwrap_or(false),
                "{}: missing observation {name} in {observed:?}",
                self.scenario.id
            );
        }
    }

    fn request(&self, id: &str) -> &'a CorpusRequest {
        self.scenario
            .requests
            .iter()
            .find(|r| r.id == id)
            .expect("known request")
    }

    fn expected_callback(&self, id: &str) -> &'a ExpectedCallback {
        self.scenario
            .expected
            .callbacks
            .iter()
            .find(|c| c.request_id == id)
            .expect("known callback")
    }
}

/// Every callback backend reply the corpus scripts: the capacity fixture parks in delivery until
/// drained; `retryable-failure` always answers 503; everything else is delivered.
fn callback_reply(
    scenario: &Scenario,
) -> impl Fn(&RecordedCallback) -> Reply + Send + Sync + 'static {
    let failing: Vec<(String, String)> = scenario
        .backend
        .callbacks
        .iter()
        .filter(|backend| backend.behavior == "retryable-failure")
        .filter_map(|backend| {
            let request = scenario
                .requests
                .iter()
                .find(|r| r.id == backend.request_id)?;
            Some((
                request.metadata.execution_id.clone()?,
                request.metadata.dispatch_attempt?.to_string(),
            ))
        })
        .collect();
    move |recorded| {
        if recorded.path == "/capacity:complete" {
            return Reply::HoldThen(204);
        }
        let attempt = recorded
            .headers
            .get("x-dispatch-attempt")
            .and_then(|v| v.to_str().ok())
            .unwrap_or_default();
        let key = (execution_id_of(recorded).to_owned(), attempt.to_owned());
        Reply::Status(if failing.contains(&key) { 503 } else { 204 })
    }
}

fn execution_id_of(recorded: &RecordedCallback) -> &str {
    recorded
        .path
        .trim_start_matches('/')
        .trim_end_matches(":complete")
}

/// `{"input":"xxx…","metadata":{"requestId":"<id>"}}`, exactly `size` bytes long.
fn sized_body(request_id: &str, size: usize) -> String {
    let prefix = r#"{"input":""#;
    let suffix = format!(r#"","metadata":{{"requestId":"{request_id}"}}}}"#);
    let padding = size
        .checked_sub(prefix.len() + suffix.len())
        .expect("the corpus size holds the request identity");
    let body = format!("{prefix}{}{suffix}", "x".repeat(padding));
    assert_eq!(body.len(), size);
    body
}

fn logged_exhaustion(logs: &Arc<Mutex<Vec<u8>>>, execution_ids: &[&str]) -> bool {
    let logs = logs.lock().unwrap();
    String::from_utf8_lossy(&logs).lines().any(|line| {
        let Ok(record) = serde_json::from_str::<Value>(line) else {
            return false;
        };
        let fields = &record["fields"];
        fields["message"] == "callback delivery exhausted"
            && fields["execution_id"]
                .as_str()
                .is_some_and(|id| execution_ids.contains(&id))
    })
}
```

`sdks/rust/src/lib.rs` becomes:

```rust
//! nanofaas function SDK for Rust: a warm HTTP runtime serving `/invoke`, `/health` and
//! `/metrics` under the admission, byte-limit and callback rules of
//! `sdks/runtime-contract/README.md`.

mod bounded;
mod callback;
mod context;
mod dispatcher;
mod handler;
mod invoke;
mod limits;
mod metrics;
mod runtime;
mod settings;
mod types;

#[cfg(test)]
mod corpus_tests;
#[cfg(test)]
mod test_support;

pub use context::Context;
pub use handler::BoxError;
pub use runtime::{Error, Runtime};
pub use settings::RuntimeSettings;
pub use types::HandlerResponse;
```

- [ ] **Step 2: Run it**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test corpus`
Expected: `test result: ok. 1 passed`. The runtime from Task 8 already conforms, so this test passes as soon as it compiles. If a scenario fails, the panic names it and the counter, header or callback that differs. Fix the runtime, not the corpus.

Then run the whole suite:

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test`
Expected: `test result: ok. 84 passed`.

- [ ] **Step 3: Commit**

Stage, run the GitNexus change analysis CLAUDE.md requires (new Rust files typically report `changed_count: 0`; a `partial` or `error` flag is not a pass — rerun), then commit:

```bash
cd /home/michele/Documenti/nanofaas
git add sdks/rust/src/lib.rs sdks/rust/src/corpus_tests.rs
GITNEXUS="$(python3 -c "import json;print(json.load(open('.gitnexus/meta.json'))['runnerIdentity']['invokedArtifact']['path'].split('/dist/')[0])")"
node --input-type=module -e "const {LocalBackend}=await import('$GITNEXUS/dist/mcp/local/local-backend.js'); console.log(JSON.stringify(await new LocalBackend().callTool('detect_changes',{scope:'staged',repo:'nanofaas'}))); process.exit(0)"
git commit -F - <<'EOF'
Run the shared saturation corpus against the Rust SDK

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019F6VX47yLJ3prsE2mDcaU1
EOF
```

---

### Task 10: Public API test, README and quality gates

**Files:**
- Create: `sdks/rust/tests/public_api.rs`, `sdks/rust/README.md`

**Interfaces:**
- Consumes: only the public API: `Runtime`, `RuntimeSettings`, `Context`, `HandlerResponse`, `BoxError`, `Error`.
- Produces: the crate's user-facing documentation, and the fifth Review Focus test.

- [ ] **Step 1: Write the public API test**

Create `sdks/rust/tests/public_api.rs`:

```rust
//! The crate as a function author sees it: only the public API, over a real TCP connection.

use std::time::Duration;

use nanofaas::{BoxError, Context, Error, HandlerResponse, Runtime, RuntimeSettings};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tokio::task::JoinHandle;

#[derive(Deserialize)]
struct In {
    text: String,
}

#[derive(Serialize)]
struct Out {
    words: usize,
}

async fn word_count(_: Context, input: In) -> Result<Out, BoxError> {
    Ok(Out {
        words: input.text.split_whitespace().count(),
    })
}

struct Served {
    base: String,
    stop: oneshot::Sender<()>,
    served: JoinHandle<Result<(), Error>>,
}

async fn serve(runtime: Runtime) -> Served {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let (stop, stopped) = oneshot::channel::<()>();
    let served = tokio::spawn(async move {
        runtime
            .serve(listener, async move {
                let _ = stopped.await;
            })
            .await
    });
    Served { base, stop, served }
}

async fn invoke(base: &str, execution_id: &str, body: &'static str) -> reqwest::Response {
    reqwest::Client::new()
        .post(format!("{base}/invoke"))
        .header("x-execution-id", execution_id)
        .body(body)
        .send()
        .await
        .unwrap()
}

#[tokio::test]
async fn serves_a_typed_handler_over_http_and_stops_cleanly() {
    let runtime = Runtime::with_settings(RuntimeSettings::default()).register("words", word_count);
    let served = serve(runtime).await;

    let response = invoke(
        &served.base,
        "exec-1",
        r#"{"input":{"text":"one two three"}}"#,
    )
    .await;
    assert_eq!(response.status(), 200);
    assert_eq!(response.headers()["x-cold-start"], "true");
    let body: Value = serde_json::from_slice(&response.bytes().await.unwrap()).unwrap();
    assert_eq!(body, json!({"words": 3}));

    let health = reqwest::get(format!("{}/health", served.base))
        .await
        .unwrap();
    assert_eq!(health.status(), 200);

    served.stop.send(()).unwrap();
    served.served.await.unwrap().expect("a clean stop");
}

#[tokio::test]
async fn a_handler_can_return_an_envelope() {
    let runtime = Runtime::with_settings(RuntimeSettings::default()).register(
        "created",
        |_: Context, _: Value| async {
            Ok::<_, BoxError>(
                HandlerResponse::new(json!({"id": 7}), 201).header("Location", "/things/7"),
            )
        },
    );
    let served = serve(runtime).await;

    let response = invoke(&served.base, "exec-2", r#"{"input":null}"#).await;
    assert_eq!(response.status(), 201);
    assert_eq!(response.headers()["location"], "/things/7");
    assert_eq!(response.headers()["x-nanofaas-function-status"], "true");

    served.stop.send(()).unwrap();
    served.served.await.unwrap().expect("a clean stop");
}

/// A local run has no control plane: the invocation still answers, and the undeliverable
/// callback shows up as a drop instead of leaking.
#[tokio::test]
async fn without_a_callback_url_invocations_answer_and_count_a_drop() {
    let runtime = Runtime::with_settings(RuntimeSettings::default()).register("words", word_count);
    let served = serve(runtime).await;

    let response = invoke(&served.base, "exec-3", r#"{"input":{"text":"a"}}"#).await;
    assert_eq!(response.status(), 200);

    let counted = async {
        loop {
            let metrics = reqwest::get(format!("{}/metrics", served.base))
                .await
                .unwrap()
                .text()
                .await
                .unwrap();
            if metrics.contains("nanofaas_runtime_callback_drops_total 1") {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    };
    tokio::time::timeout(Duration::from_secs(2), counted)
        .await
        .expect("the dropped callback is counted");

    served.stop.send(()).unwrap();
    served.served.await.unwrap().expect("a clean stop");
}
```

- [ ] **Step 2: Run it**

Run: `cd /home/michele/Documenti/nanofaas/sdks/rust && cargo test --test public_api`
Expected: `test result: ok. 3 passed`.

- [ ] **Step 3: Write the README**

Create `sdks/rust/README.md`:

````markdown
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
async fn main() -> Result<(), nanofaas::Error> {
    Runtime::from_env().register("word-count", word_count).start().await
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

## Development

```bash
cd sdks/rust
cargo test                                   # unit, conformance corpus (needs python3), public API
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```
````

- [ ] **Step 4: Run the quality gates**

```bash
cd /home/michele/Documenti/nanofaas/sdks/rust
cargo test
cargo clippy --all-targets -- -D warnings
cargo fmt --check
for i in $(seq 1 10); do taskset -c 0,1 cargo test -q 2>&1 | grep -E "test result|FAILED|panicked"; done
```

Expected: `84 passed` (library) and `3 passed` (public API); clippy and fmt exit 0 with no output. All ten runs pinned to two CPUs, like the CI runner, are green. A single failure there is a real race: stop and investigate it, do not rerun until green.
- [ ] **Step 5: Commit**

Stage, run the GitNexus change analysis CLAUDE.md requires (new Rust files typically report `changed_count: 0`; a `partial` or `error` flag is not a pass — rerun), then commit:

```bash
cd /home/michele/Documenti/nanofaas
git add sdks/rust/tests/public_api.rs sdks/rust/README.md
GITNEXUS="$(python3 -c "import json;print(json.load(open('.gitnexus/meta.json'))['runnerIdentity']['invokedArtifact']['path'].split('/dist/')[0])")"
node --input-type=module -e "const {LocalBackend}=await import('$GITNEXUS/dist/mcp/local/local-backend.js'); console.log(JSON.stringify(await new LocalBackend().callTool('detect_changes',{scope:'staged',repo:'nanofaas'}))); process.exit(0)"
git commit -F - <<'EOF'
Document the Rust SDK and test its public API

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019F6VX47yLJ3prsE2mDcaU1
EOF
```

