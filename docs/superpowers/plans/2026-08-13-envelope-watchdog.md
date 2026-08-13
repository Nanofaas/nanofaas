# Handler Response Envelope — Watchdog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let bash functions decide their HTTP status, response headers and base64 marker through the
watchdog's STDIO/FILE paths, and stop the watchdog from swallowing the envelope of an SDK runtime it
fronts in HTTP proxy mode.

**Architecture:** Two distinct mechanisms for two distinct topologies. On STDIO and FILE the child
process has no type system, so the envelope is recognised by an explicit `__nanofaas_envelope__: true`
marker key in the JSON it writes. On HTTP proxy the fronted runtime is an SDK that already emits the
two marker headers, so the watchdog forwards what it receives rather than re-deriving anything.

**Tech Stack:** Rust (tokio, axum 0.8, reqwest 0.13, serde_json), `cargo test`.

**Context:** the envelope shipped for Java and Python in `0ffaf217` and for Go and JavaScript in
`b2329d9a`. This is workstream 2 of GitHub issue #193. The example-function pass and the `fn-init`
templates are a separate plan.

## Global Constraints

- **The wire contract is frozen.** Two marker headers, byte-identical to what the four SDKs emit:
  `X-NanoFaaS-Function-Status: true` on exactly the envelope path, and `X-NanoFaaS-Encoding: <value>`
  only when encoding is present. Callback JSON keys are camelCase `statusCode`, `headers`, `encoding`
  — the control plane deserializes that body into a Java record under default Jackson naming, and a
  mismatch is silent.
- **The `__nanofaas_envelope__: true` marker key applies ONLY to STDIO and FILE mode.** It exists
  because a bash script's stdout has no type system: the script's real output could legitimately
  contain a `statusCode` key, and the marker is what removes the ambiguity. It must never be required
  on, or read from, the HTTP proxy path.
- **HTTP proxy mode forwards, it does not re-derive.** The fronted runtime is an SDK that already
  applied the allow-list, validated the status range and emitted the markers. The watchdog passes its
  status and marker headers through unchanged. It must not re-filter or re-validate — doing so would
  put two different implementations of the same policy in the same request path.
- Response headers a bash handler may set (STDIO/FILE only): exactly `Content-Type`, `Location`,
  `Cache-Control`, `ETag`, `Content-Disposition`, `Content-Language`, `Retry-After`, `Vary`,
  compared case-insensitively, at most one entry per name. Mirrors
  `ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS` in `platform/common`.
- Control headers can never be set by a handler: `X-Execution-Id`, `X-Cold-Start`,
  `X-Init-Duration-Ms`, `X-NanoFaaS-Offload-Hop`, `X-NanoFaaS-Function-Status`,
  `X-NanoFaaS-Encoding`, `X-Trace-Id`, `X-Dispatch-Attempt`, `X-NanoFaaS-Offloaded`,
  `X-Queue-Reject-Reason`. None appear in the allow-list, so the filter excludes them — a test must
  prove it rather than assuming.
- Status codes: integers in `[200,599]`. Outside that range is a platform error — the watchdog's own
  500 — never passed through.
- `encoding`: the only legal value is `"base64"` (or absent). The watchdog never encodes or decodes.
- **Backward compatibility is mandatory.** A bash function whose stdout carries no
  `__nanofaas_envelope__` key must behave exactly as today: the whole parsed JSON value is the
  output, status 200, no extra headers. Every task includes a regression test proving it.
- Do not touch `platform/`, `sdks/`, or anything under `functions/` — all out of scope for this plan.
- Do NOT add a `Co-Authored-By` trailer to any commit message.

### Two facts about this crate's gates, verified at baseline

**`cargo fmt --check` fails on `main.rs` before any of this work.** Confirmed by running
`rustfmt --check` against `main.rs` as it exists at the commit this branch started from. It is
therefore **not a usable gate for this plan**, and no task should assert it comes back clean.
Reformatting the whole 1161-line file would be a large diff unrelated to the envelope and is out of
scope. The rule for this plan: **new files must be rustfmt-clean on their own**
(`rustfmt --check --edition 2021 src/<file>.rs`), and edits to `main.rs` must follow rustfmt
conventions by eye without reformatting untouched regions. `cargo clippy -- -D warnings` does work at
baseline and remains the real lint gate.

**Task 1's `envelope.rs` carries `#![allow(dead_code)]`, and it is temporary.** Nothing calls into
the module until Tasks 2 and 3 wire it up, so clippy's `-D warnings` rejects every new item as dead
code. That allow is a scaffolding bridge, exactly like a type cast added to keep a tree compiling
across a task boundary: **Task 3 must delete it**, once `warm_invoke`, `invoke_http_warm` and
`InvocationResult` all reference the module. Leaving it would permanently mask genuinely dead code in
a module whose whole job is to be called.

## Why HTTP proxy mode is in scope

`runtimes/watchdog/Dockerfile.combined` sets `ENV EXECUTION_MODE=HTTP` and fronts `warm-echo`, a Java
SDK runtime that now emits both marker headers. `invoke_http_warm` returns `Result<serde_json::Value,
String>`, discarding the response's status and headers, and `warm_invoke` then rebuilds the response
as `(StatusCode::OK, Json(v))`. So today the watchdog **silently swallows** the envelope of any SDK
runtime it fronts. That is a regression this envelope effort introduced for that topology, not a
missing feature — which is why it is fixed here rather than deferred.

Related: issue #172 asks whether the combined image should exist at all. If it is retired, this half
becomes dead code — but until that decision lands, the swallowing is real and this plan fixes it.

---

## File Structure

`runtimes/watchdog/src/main.rs` is a single 1161-line file, which is the established shape of this
crate. Rather than restructure it, this plan adds one focused module file and touches three existing
functions:

- `runtimes/watchdog/src/envelope.rs` *(new)* — the marker key, the allow-list, the status-range
  check, the header filter, and an `Envelope` struct with the function that recognises one in a
  `serde_json::Value`. One responsibility, unit-testable without spawning a process or a server.
- `main.rs` — declares the module, unpacks the envelope in `warm_invoke`, forwards status and marker
  headers in `invoke_http_warm`, and threads the three fields onto `InvocationResult` for the
  one-shot callback path.

---

### Task 1: The envelope module

**Files:**
- Create: `runtimes/watchdog/src/envelope.rs`
- Modify: `runtimes/watchdog/src/main.rs` (add `mod envelope;` near the other module-level items)

**Interfaces:**
- Produces, consumed by Tasks 2 and 3:
  - `pub const MARKER_HEADER: &str = "X-NanoFaaS-Function-Status";`
  - `pub const ENCODING_HEADER: &str = "X-NanoFaaS-Encoding";`
  - `pub const ENVELOPE_KEY: &str = "__nanofaas_envelope__";`
  - `pub struct Envelope { pub output: serde_json::Value, pub status_code: u16, pub headers: std::collections::BTreeMap<String, String>, pub encoding: Option<String> }`
  - `pub fn detect(value: &serde_json::Value) -> Option<Result<Envelope, String>>` — `None` when the
    value carries no marker key (today's behavior, the caller uses the value verbatim); `Some(Err)`
    when it carries the marker but is malformed or the status is out of range; `Some(Ok)` otherwise.
  - `pub fn is_status_valid(status: u16) -> bool`
  - `pub fn filter_allowed_headers(raw: &serde_json::Value) -> BTreeMap<String, String>`

**Why `BTreeMap` and not `HashMap`:** iteration order is deterministic, so a first-occurrence-wins
dedupe assertion is testable here — unlike Go, where map order is undefined and the equivalent test
had to be a count-only assertion. Take the determinism where the language offers it.

- [ ] **Step 1: Write the failing tests**

Create `runtimes/watchdog/src/envelope.rs` containing only the tests at first, so the module compiles
into the crate and the failures are about missing items rather than a missing file:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn detect_returns_none_without_the_marker_key() {
        let value = json!({"roman": "XLII", "statusCode": 999});
        assert!(detect(&value).is_none(),
            "a plain output that merely contains a statusCode key must NOT be treated as an envelope");
    }

    #[test]
    fn detect_returns_none_for_non_objects() {
        assert!(detect(&json!("plain string")).is_none());
        assert!(detect(&json!([1, 2, 3])).is_none());
        assert!(detect(&json!(null)).is_none());
    }

    #[test]
    fn detect_unpacks_a_well_formed_envelope() {
        let value = json!({
            "__nanofaas_envelope__": true,
            "output": {"error": "not found"},
            "statusCode": 404,
            "headers": {"Location": "/x", "X-Custom": "dropped"},
            "encoding": "base64"
        });

        let envelope = detect(&value).expect("marker present").expect("well formed");

        assert_eq!(envelope.status_code, 404);
        assert_eq!(envelope.output, json!({"error": "not found"}));
        assert_eq!(envelope.encoding.as_deref(), Some("base64"));
        assert_eq!(envelope.headers.get("Location").map(String::as_str), Some("/x"));
        assert!(!envelope.headers.contains_key("X-Custom"), "disallowed header must be dropped");
    }

    #[test]
    fn detect_defaults_a_missing_status_to_200() {
        let value = json!({"__nanofaas_envelope__": true, "output": "ok"});
        let envelope = detect(&value).unwrap().unwrap();
        assert_eq!(envelope.status_code, 200);
        assert!(envelope.encoding.is_none());
        assert!(envelope.headers.is_empty());
    }

    #[test]
    fn detect_rejects_an_out_of_range_status() {
        let value = json!({"__nanofaas_envelope__": true, "output": "ok", "statusCode": 999});
        let err = detect(&value).expect("marker present").expect_err("999 is not in [200,599]");
        assert!(err.contains("999"), "the error must name the offending status, got: {err}");
    }

    #[test]
    fn detect_ignores_a_falsy_marker() {
        let value = json!({"__nanofaas_envelope__": false, "output": "ok", "statusCode": 404});
        assert!(detect(&value).is_none(),
            "only an explicit true marker opts in; anything else is plain output");
    }

    #[test]
    fn filter_dedupes_case_insensitively_keeping_the_first_occurrence() {
        // BTreeMap iteration is ordered, so "Content-Type" sorts before "content-type"
        // and first-occurrence-wins is deterministic here (unlike Go's HashMap).
        let raw = json!({"Content-Type": "application/pdf", "content-type": "text/plain"});
        let filtered = filter_allowed_headers(&raw);
        assert_eq!(filtered.len(), 1, "colliding casings must collapse to one entry");
        assert_eq!(filtered.get("Content-Type").map(String::as_str), Some("application/pdf"));
    }

    #[test]
    fn filter_drops_control_headers() {
        let raw = json!({
            "X-NanoFaaS-Function-Status": "spoofed",
            "X-NanoFaaS-Encoding": "spoofed",
            "X-Execution-Id": "spoofed",
            "ETag": "\"abc\""
        });
        let filtered = filter_allowed_headers(&raw);
        assert_eq!(filtered.len(), 1);
        assert!(filtered.contains_key("ETag"));
    }

    #[test]
    fn is_status_valid_range_boundaries() {
        assert!(is_status_valid(200));
        assert!(is_status_valid(599));
        assert!(!is_status_valid(199));
        assert!(!is_status_valid(600));
    }
}
```

Add `mod envelope;` to `main.rs`, next to the existing `use` block at the top.

- [ ] **Step 2: Run to verify it fails**

Run: `cd runtimes/watchdog && cargo test envelope`
Expected: FAIL to compile — `cannot find function detect in this scope`, and the same for
`filter_allowed_headers` and `is_status_valid`.

- [ ] **Step 3: Implement the module**

Put this above the `#[cfg(test)] mod tests` block in `runtimes/watchdog/src/envelope.rs`:

```rust
//! The wire-level response envelope, as it reaches the watchdog.
//!
//! Unlike the typed SDKs — which recognise an envelope through their own type systems — a child
//! process writing JSON to stdout has no type to check. The `__nanofaas_envelope__` marker key is
//! what disambiguates "these are envelope fields" from "the script's real output happens to contain
//! a statusCode key". This marker applies to STDIO and FILE mode only: on the HTTP proxy path the
//! fronted runtime is an SDK that already emits the marker headers, and the watchdog forwards them.

use std::collections::BTreeMap;

pub const MARKER_HEADER: &str = "X-NanoFaaS-Function-Status";
pub const ENCODING_HEADER: &str = "X-NanoFaaS-Encoding";
pub const ENVELOPE_KEY: &str = "__nanofaas_envelope__";

/// Mirrors ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS in platform/common. Keep in sync.
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

#[derive(Debug, Clone)]
pub struct Envelope {
    pub output: serde_json::Value,
    pub status_code: u16,
    pub headers: BTreeMap<String, String>,
    pub encoding: Option<String>,
}

pub fn is_status_valid(status: u16) -> bool {
    (200..=599).contains(&status)
}

/// Filters handler-supplied response headers down to the allow-list.
///
/// At most one entry survives per header name, compared case-insensitively. BTreeMap iteration is
/// ordered, so the first occurrence in sort order wins deterministically and keeps its casing.
pub fn filter_allowed_headers(raw: &serde_json::Value) -> BTreeMap<String, String> {
    let mut filtered = BTreeMap::new();
    let mut seen: Vec<String> = Vec::new();
    let Some(object) = raw.as_object() else {
        return filtered;
    };
    for (key, value) in object {
        let lower = key.to_lowercase();
        if !ALLOWED_RESPONSE_HEADERS.contains(&lower.as_str()) || seen.contains(&lower) {
            continue;
        }
        let Some(text) = value.as_str() else {
            continue;
        };
        seen.push(lower);
        filtered.insert(key.clone(), text.to_string());
    }
    filtered
}

/// Recognises an envelope in a child process's parsed JSON output.
///
/// Returns `None` when there is no marker — the caller uses the value verbatim, which is the
/// pre-envelope behavior and must stay byte-identical. Returns `Some(Err)` when the marker is
/// present but the envelope is unusable, which the caller reports as a platform error.
pub fn detect(value: &serde_json::Value) -> Option<Result<Envelope, String>> {
    let object = value.as_object()?;
    if object.get(ENVELOPE_KEY)?.as_bool() != Some(true) {
        return None;
    }

    let status_code = match object.get("statusCode") {
        None | Some(serde_json::Value::Null) => 200,
        Some(raw) => match raw.as_u64() {
            Some(candidate) if candidate <= u16::MAX as u64 => candidate as u16,
            _ => return Some(Err(format!("Envelope statusCode is not an integer: {raw}"))),
        },
    };
    if !is_status_valid(status_code) {
        return Some(Err(format!(
            "Envelope statusCode {status_code} is outside [200,599]"
        )));
    }

    let headers = object
        .get("headers")
        .map(filter_allowed_headers)
        .unwrap_or_default();

    let encoding = object
        .get("encoding")
        .and_then(|raw| raw.as_str())
        .map(str::to_string);

    Some(Ok(Envelope {
        output: object.get("output").cloned().unwrap_or(serde_json::Value::Null),
        status_code,
        headers,
        encoding,
    }))
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd runtimes/watchdog && cargo test envelope`
Expected: PASS, 9 tests.

- [ ] **Step 5: Format, lint, commit**

Run: `cd runtimes/watchdog && rustfmt --check --edition 2021 src/envelope.rs && cargo clippy -- -D warnings`
Expected: no output from rustfmt (the new file must be clean on its own), no warnings from clippy.

Do **not** run `cargo fmt --check` — it fails on `main.rs` at baseline, for reasons unrelated to this
work, so it cannot tell you anything about your change.

Clippy will reject every new item in `envelope.rs` as dead code, because nothing calls the module
until Task 2. Add `#![allow(dead_code)]` at the top of `envelope.rs` to get past it, and note in your
report that it is temporary — Task 3 deletes it once all three call sites exist.

```bash
git add runtimes/watchdog/src/envelope.rs runtimes/watchdog/src/main.rs
git commit -m "feat: recognise the response envelope marker in the watchdog"
```

---

### Task 2: STDIO and FILE modes honour the envelope in warm mode

**Files:**
- Modify: `runtimes/watchdog/src/main.rs` — `warm_invoke` (around line 675) and its return type
- Test: `runtimes/watchdog/src/main.rs` (the crate's existing `#[cfg(test)]` module)

**Interfaces:**
- Consumes from Task 1: `envelope::detect`, `envelope::Envelope`, `envelope::MARKER_HEADER`,
  `envelope::ENCODING_HEADER`.
- Produces: nothing later tasks consume.

**The shape change.** `warm_invoke` currently returns `(StatusCode, Json<serde_json::Value>)`, which
cannot carry headers. It becomes `axum::response::Response` (built through `IntoResponse`), so the
envelope path can attach the allow-listed headers and the two markers. Every existing early return in
that function — the missing-execution-id 400, the error 500 — must be converted to the same return
type and keep its exact status and body.

- [ ] **Step 1: Write the failing tests**

Add to the crate's existing test module in `main.rs`. Read the surrounding tests first and match how
they construct `WarmAppState` and `Config`; if the existing tests do not exercise `warm_invoke`
directly, drive it through the axum router the way `execute_warm_server` builds it, using
`tower::ServiceExt::oneshot`. Do not invent a second construction path.

```rust
    #[tokio::test]
    async fn warm_invoke_stdio_envelope_applies_status_headers_and_markers() {
        // A bash function whose stdout carries the envelope marker.
        let body = serde_json::json!({
            "__nanofaas_envelope__": true,
            "output": {"error": "not found"},
            "statusCode": 404,
            "headers": {"Location": "/x", "X-Custom": "dropped"},
            "encoding": "base64"
        });

        let response = invoke_stdio_returning(body).await;

        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response.headers().get("X-NanoFaaS-Function-Status").unwrap(), "true");
        assert_eq!(response.headers().get("X-NanoFaaS-Encoding").unwrap(), "base64");
        assert_eq!(response.headers().get("Location").unwrap(), "/x");
        assert!(response.headers().get("X-Custom").is_none(), "disallowed header must be dropped");
    }

    #[tokio::test]
    async fn warm_invoke_stdio_plain_output_behaves_exactly_as_before() {
        let body = serde_json::json!({"roman": "XLII"});

        let response = invoke_stdio_returning(body).await;

        assert_eq!(response.status(), StatusCode::OK);
        assert!(response.headers().get("X-NanoFaaS-Function-Status").is_none());
        assert!(response.headers().get("X-NanoFaaS-Encoding").is_none());
    }

    #[tokio::test]
    async fn warm_invoke_stdio_out_of_range_status_is_a_platform_error() {
        let body = serde_json::json!({
            "__nanofaas_envelope__": true, "output": "ok", "statusCode": 999
        });

        let response = invoke_stdio_returning(body).await;

        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert!(response.headers().get("X-NanoFaaS-Function-Status").is_none(),
            "no marker on a platform error");
    }
```

Write the `invoke_stdio_returning(body) -> axum::response::Response` helper in the same test module.
It must run a real STDIO invocation: point `Config.command` at a shell command that echoes the given
JSON to stdout (e.g. `sh -c 'cat > /dev/null; echo <json>'`), drive `warm_invoke` through the router,
and return the response. Driving a real child process is the point — a helper that calls
`envelope::detect` directly would re-test Task 1 and prove nothing about the wiring.

- [ ] **Step 2: Run to verify they fail**

Run: `cd runtimes/watchdog && cargo test warm_invoke_stdio`
Expected: FAIL — the envelope test gets 200 with the envelope object as the body, and no markers.

- [ ] **Step 3: Implement**

Change `warm_invoke`'s signature to return `axum::response::Response` and import
`axum::response::IntoResponse`. Convert the two existing early returns to `.into_response()`,
preserving their exact statuses and bodies. Then replace the final `match out { ... }` block:

```rust
    match out {
        Ok(value) => {
            state
                .metrics
                .invocations_total
                .get_or_create(&InvocationsLabels {
                    function: state.function_name.clone(),
                    mode: mode_str.to_string(),
                    success: "true".to_string(),
                })
                .inc();

            match envelope::detect(&value) {
                None => (StatusCode::OK, Json(value)).into_response(),
                Some(Err(message)) => {
                    warn!(execution_id = %execution_id, error = %message,
                        "Envelope rejected, treating as platform error");
                    (
                        StatusCode::INTERNAL_SERVER_ERROR,
                        Json(serde_json::json!({"error": message})),
                    )
                        .into_response()
                }
                Some(Ok(envelope)) => {
                    let mut response = (
                        StatusCode::from_u16(envelope.status_code)
                            .unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
                        Json(envelope.output),
                    )
                        .into_response();
                    let headers = response.headers_mut();
                    for (name, value) in &envelope.headers {
                        if let (Ok(name), Ok(value)) = (
                            name.parse::<axum::http::HeaderName>(),
                            value.parse::<axum::http::HeaderValue>(),
                        ) {
                            headers.insert(name, value);
                        }
                    }
                    headers.insert(
                        envelope::MARKER_HEADER.parse::<axum::http::HeaderName>().unwrap(),
                        axum::http::HeaderValue::from_static("true"),
                    );
                    if let Some(encoding) = envelope.encoding.as_deref() {
                        if let Ok(value) = encoding.parse::<axum::http::HeaderValue>() {
                            headers.insert(
                                envelope::ENCODING_HEADER.parse::<axum::http::HeaderName>().unwrap(),
                                value,
                            );
                        }
                    }
                    response
                }
            }
        }
        Err(e) => { /* unchanged, plus .into_response() */ }
    }
```

Two ordering details that matter: the allow-listed headers are inserted **before** the two markers,
so a handler value can never overwrite a marker — belt and braces, since the allow-list already
excludes them. And `Json(...)` sets `content-type: application/json` when the response is built, so a
handler-supplied `Content-Type` inserted afterwards correctly wins, matching all four SDKs.

- [ ] **Step 4: Run to verify they pass**

Run: `cd runtimes/watchdog && cargo test`
Expected: PASS, the whole crate including every pre-existing test.

- [ ] **Step 5: Format, lint, commit**

Run: `cd runtimes/watchdog && cargo clippy -- -D warnings`
Expected: no warnings. Do not run `cargo fmt --check` — it fails on `main.rs` at baseline for
unrelated reasons. Match the surrounding code's formatting by eye; do not reformat untouched regions.

```bash
git add runtimes/watchdog/src/main.rs
git commit -m "feat: honour the STDIO envelope marker in watchdog warm mode"
```

---

### Task 3: HTTP proxy mode stops swallowing the fronted runtime's envelope

**Files:**
- Modify: `runtimes/watchdog/src/main.rs` — `invoke_http_warm` (around line 782) and the
  `ExecutionMode::Http` arm of `warm_invoke`
- Test: `runtimes/watchdog/src/main.rs` test module

**Interfaces:**
- Consumes from Task 1: `envelope::MARKER_HEADER`, `envelope::ENCODING_HEADER`.
- Consumes from Task 2: `warm_invoke` now returns `axum::response::Response`.

**The defect being fixed.** `Dockerfile.combined` sets `EXECUTION_MODE=HTTP` and fronts `warm-echo`,
a Java SDK runtime that emits both marker headers. `invoke_http_warm` returns
`Result<serde_json::Value, String>`, discarding the response's status and headers, so the watchdog
rebuilds a flat 200 and the envelope vanishes. A function-decided 404 behind the combined image
currently reaches the control plane as a 200.

**Forward, do not re-derive.** The fronted runtime already applied the allow-list, validated the
status range and set the markers. The watchdog copies its status and its two marker headers through,
plus any allow-listed headers it already set. It must NOT run `envelope::detect` on the body — the
body from an SDK runtime is the output verbatim, with no marker key — and must NOT re-filter with its
own allow-list, which would put two implementations of one policy in the same path.

- [ ] **Step 1: Write the failing tests**

```rust
    #[tokio::test]
    async fn warm_invoke_http_forwards_the_fronted_runtimes_envelope() {
        // A stub standing in for an SDK runtime that decided its own status.
        let upstream = spawn_stub_runtime(|| {
            axum::response::Response::builder()
                .status(404)
                .header("content-type", "application/json")
                .header("X-NanoFaaS-Function-Status", "true")
                .header("X-NanoFaaS-Encoding", "base64")
                .header("Location", "/x")
                .body(axum::body::Body::from(r#"{"error":"not found"}"#))
                .unwrap()
        })
        .await;

        let response = invoke_http_returning(&upstream.url).await;

        assert_eq!(response.status(), StatusCode::NOT_FOUND);
        assert_eq!(response.headers().get("X-NanoFaaS-Function-Status").unwrap(), "true");
        assert_eq!(response.headers().get("X-NanoFaaS-Encoding").unwrap(), "base64");
        assert_eq!(response.headers().get("Location").unwrap(), "/x");
    }

    #[tokio::test]
    async fn warm_invoke_http_plain_success_behaves_exactly_as_before() {
        let upstream = spawn_stub_runtime(|| {
            axum::response::Response::builder()
                .status(200)
                .header("content-type", "application/json")
                .body(axum::body::Body::from(r#"{"roman":"XLII"}"#))
                .unwrap()
        })
        .await;

        let response = invoke_http_returning(&upstream.url).await;

        assert_eq!(response.status(), StatusCode::OK);
        assert!(response.headers().get("X-NanoFaaS-Function-Status").is_none());
    }
```

Write `spawn_stub_runtime` and `invoke_http_returning` in the same test module. The stub binds an
ephemeral port with a tiny axum router and returns its URL; the driver points `Config.runtime_url` at
it and drives `warm_invoke`. Read how the crate's existing tests bind test servers, if any, and reuse
that; otherwise `tokio::net::TcpListener::bind("127.0.0.1:0")` plus `axum::serve` is the idiom.

- [ ] **Step 2: Run to verify they fail**

Run: `cd runtimes/watchdog && cargo test warm_invoke_http`
Expected: FAIL — the forwarding test gets 200 with no marker headers, because the current code
discards them.

- [ ] **Step 3: Implement**

Change `invoke_http_warm` to return the pieces the caller needs instead of only the body. Introduce a
small struct next to it:

```rust
/// What the fronted runtime answered on the HTTP proxy path.
///
/// The runtime is an SDK that already applied the response-header allow-list, validated the status
/// range and set the marker headers. The watchdog forwards these verbatim — re-deriving them here
/// would put a second implementation of the same policy in the request path.
struct ProxiedResponse {
    status: StatusCode,
    headers: axum::http::HeaderMap,
    body: serde_json::Value,
}
```

Have `invoke_http_warm` return `Result<ProxiedResponse, String>`: keep its existing error handling
for transport failures and non-success statuses **only where the marker header is absent** — a
marker-bearing non-2xx is a function decision and must be forwarded, not turned into an error, which
is exactly the rule `ExternalDispatcher` and `DefaultOffloadGateway` already follow one hop up.

In `warm_invoke`'s `ExecutionMode::Http` arm, build the response from the `ProxiedResponse`: use its
status, copy its allow-listed and marker headers onto the outgoing response, and use its body.

Because the three modes now produce different shapes, keep them behind one small enum or convert the
STDIO/FILE arm's `serde_json::Value` into a `ProxiedResponse` right after `envelope::detect`, so the
final response-building code has a single form. Choose whichever keeps the diff smaller and say which
you chose and why in your report.

- [ ] **Step 4: Delete Task 1's temporary `#![allow(dead_code)]`**

Task 1 put `#![allow(dead_code)]` at the top of `runtimes/watchdog/src/envelope.rs` because nothing
referenced the module yet and clippy's `-D warnings` rejected every new item. After this task,
`warm_invoke`, `invoke_http_warm` and `InvocationResult` all reference it, so the allow has done its
job. **Remove that line.**

It is scaffolding, not a lint preference: leaving it would permanently mask genuinely dead code in a
module whose entire purpose is to be called. Same shape as a type cast added to bridge a task
boundary — it exists to be deleted.

Run: `cd runtimes/watchdog && cargo clippy -- -D warnings`
Expected: no warnings **with the allow removed**. If clippy now reports a specific item as dead,
that is a real finding, not a reason to restore the allow: either a later task still needs to call
it, or it should not have been written. Report which, and do not put the blanket allow back.

- [ ] **Step 5: Run to verify everything passes**

Run: `cd runtimes/watchdog && cargo test`
Expected: PASS, the whole crate.

Run: `cd runtimes/watchdog && grep -n "allow(dead_code)" src/envelope.rs`
Expected: no match.

Do not run `cargo fmt --check` — it fails on `main.rs` at baseline for unrelated reasons. Match the
surrounding code's formatting by eye; do not reformat untouched regions.

- [ ] **Step 6: Commit**

```bash
git add runtimes/watchdog/src/main.rs runtimes/watchdog/src/envelope.rs
git commit -m "fix: forward the fronted runtime's envelope in watchdog HTTP proxy mode"
```

---

### Task 4: The one-shot callback carries the envelope fields

**Files:**
- Modify: `runtimes/watchdog/src/main.rs` — `InvocationResult` (line 192), its `impl` (line 204), and
  the one-shot arms in `main` (around line 1015)
- Test: `runtimes/watchdog/src/main.rs` test module

**Interfaces:**
- Consumes from Task 1: `envelope::detect`.
- Produces: `InvocationResult` gains `status_code: Option<u16>`, `headers: Option<BTreeMap<String,String>>`,
  `encoding: Option<String>`, all serialized as camelCase and omitted when absent, plus
  `fn success_with_envelope(envelope: Envelope) -> Self` which always sets `success: true`.

**Why this task is separate from Task 2.** Warm mode answers over HTTP; one-shot mode has no HTTP
response at all — it POSTs an `InvocationResult` to the callback URL and exits. The envelope reaches
the control plane through the JSON body there, so the field names must match Java's record exactly.

- [ ] **Step 1: Write the failing test**

```rust
    #[test]
    fn invocation_result_serializes_envelope_fields_as_camel_case() {
        let envelope = envelope::Envelope {
            output: serde_json::json!({"error": "not found"}),
            status_code: 404,
            headers: std::collections::BTreeMap::from([
                ("Location".to_string(), "/x".to_string()),
            ]),
            encoding: Some("base64".to_string()),
        };

        let body = serde_json::to_string(&InvocationResult::success_with_envelope(envelope)).unwrap();

        assert!(body.contains(r#""statusCode":404"#), "got: {body}");
        assert!(body.contains(r#""encoding":"base64""#), "got: {body}");
        assert!(body.contains(r#""Location":"/x""#), "got: {body}");
        assert!(!body.contains("status_code"), "wire keys are camelCase, got: {body}");
        assert!(body.contains(r#""success":true"#),
            "a function-decided result must be success=true so it is never retried");
    }

    #[test]
    fn invocation_result_plain_success_omits_envelope_fields() {
        let body = serde_json::to_string(&InvocationResult::success(serde_json::json!("ok"))).unwrap();

        for key in ["statusCode", "headers", "encoding"] {
            assert!(!body.contains(key), "a plain success must omit {key}, got: {body}");
        }
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd runtimes/watchdog && cargo test invocation_result`
Expected: FAIL to compile — no `success_with_envelope`, no `status_code` field.

- [ ] **Step 3: Implement**

Extend the struct and its impl:

```rust
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct InvocationResult {
    success: bool,
    output: Option<serde_json::Value>,
    error: Option<ErrorInfo>,
    // Wire keys must match platform/common's InvocationResult under default Jackson naming.
    // A mismatch is silent: the field simply vanishes on the control plane.
    #[serde(skip_serializing_if = "Option::is_none")]
    status_code: Option<u16>,
    #[serde(skip_serializing_if = "Option::is_none")]
    headers: Option<std::collections::BTreeMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    encoding: Option<String>,
}
```

Add `status_code: None, headers: None, encoding: None` to the existing `success` and `error`
constructors so they keep behaving identically, and add:

```rust
    /// Builds a function-decided result. `success` is always true: retry is driven by `!success`,
    /// so a function-decided response must never be retried whatever its status, 5xx included.
    fn success_with_envelope(envelope: envelope::Envelope) -> Self {
        Self {
            success: true,
            output: Some(envelope.output),
            error: None,
            status_code: Some(envelope.status_code),
            headers: if envelope.headers.is_empty() { None } else { Some(envelope.headers) },
            encoding: envelope.encoding,
        }
    }
```

Note `#[serde(rename_all = "camelCase")]` covers `status_code` → `statusCode`; `success`, `output`,
`error` and `headers` are already single words and are unaffected. Verify that with the test rather
than assuming.

Then, in `main`'s one-shot path, run `envelope::detect` on the successful result before calling
`send_callback`: `None` keeps `InvocationResult::success(value)`; `Some(Ok(e))` becomes
`InvocationResult::success_with_envelope(e)`; `Some(Err(message))` becomes
`InvocationResult::error("OUTPUT_SERIALIZATION_ERROR", &message)`.

- [ ] **Step 4: Run to verify it passes**

Run: `cd runtimes/watchdog && cargo test`
Expected: PASS, the whole crate.

- [ ] **Step 5: Format, lint, commit**

Run: `cd runtimes/watchdog && cargo clippy -- -D warnings`
Expected: no warnings. Do not run `cargo fmt --check` — it fails on `main.rs` at baseline for
unrelated reasons. Match the surrounding code's formatting by eye; do not reformat untouched regions.

```bash
git add runtimes/watchdog/src/main.rs
git commit -m "feat: carry the envelope on the watchdog's one-shot callback"
```

---

### Final verification

- [ ] **Step 1: The crate**

Run: `cd runtimes/watchdog && cargo test && cargo clippy -- -D warnings`
Expected: all green, no warnings.

Run: `cd runtimes/watchdog && rustfmt --check --edition 2021 src/envelope.rs`
Expected: no output — the one new file this plan adds must be rustfmt-clean on its own.

Run: `cd runtimes/watchdog && grep -n "allow(dead_code)" src/envelope.rs`
Expected: **no match.** Task 1's temporary allow must be gone; if it is still there, either Task 3
failed to remove it or the module has genuinely unused items worth deleting.

- [ ] **Step 2: Nothing else regressed**

Run: `./gradlew test --no-parallel --continue`
Expected: only `:nanofaas-cli RootCommandTest.versionComesFromTheBuild` fails — known, pre-existing,
unrelated. This plan touches no Java.

Run: `cd sdks/python && uv run pytest tests/ -q` — expected PASS.
Run: `cd sdks/go && go test ./...` — expected PASS.
Run: `cd sdks/javascript && npm test` — expected PASS.

- [ ] **Step 3: The cross-language gate**

Run: `./functions/contract-tests/run.sh`
Expected: exit 0. This plan converts no example function, so the bash `roman-numeral` still returns a
plain value with an implicit 200 — which the shared fixture tolerates.

- [ ] **Step 4: Confirm the marker literals match the four SDKs**

Read the header-name literals in `runtimes/watchdog/src/envelope.rs` against
`sdks/java/.../InvokeController.java`, `sdks/python/src/nanofaas/runtime/app.py`,
`sdks/go/nanofaas/http_invoke.go` and `sdks/javascript/src/runtime.ts`. Record all five in your
report. Casing differences are fine — HTTP header names are case-insensitive. Anything else is a real
cross-language break: stop and report it.

## Self-Review Notes

- **Spec coverage:** the spec's deferred "watchdog STDIO envelope" is Tasks 1, 2 and 4. Task 3 is not
  in the spec's deferred list — it was discovered while grounding this plan, and it is a regression
  rather than a feature: the combined image fronts an SDK runtime whose markers the watchdog
  currently discards.
- **The `__nanofaas_envelope__` marker is deliberately confined to STDIO and FILE.** Requiring it on
  the HTTP proxy path would mean asking SDK runtimes to emit a marker key in their body purely for
  the watchdog's benefit, when they already emit marker headers that every other hop reads.
- **`BTreeMap` over `HashMap` is a deliberate choice**, and the reason is recorded in Task 1: ordered
  iteration makes the first-occurrence-wins dedupe assertion deterministic. In Go the equivalent test
  had to be weakened to a count-only assertion because map order is undefined there.
- **Task 2 changes `warm_invoke`'s return type**, which is why the plan says explicitly to convert
  every early return. Missing one is a compile error, not a silent bug, but the instruction saves a
  round trip.
- **Every task's tests drive a real execution** — a spawned child process in Task 2, a live stub HTTP
  server in Task 3 — rather than calling `envelope::detect` directly. Unit-testing the detector is
  Task 1's job; re-testing it through a helper would prove nothing about the wiring, which is exactly
  the gap that let a Critical through in the first milestone.

### Corrections applied during execution

**The `cargo fmt --check` gate was unsatisfiable and has been replaced.** Every task originally
asserted it comes back clean. It does not: `main.rs` is unformatted at the commit this branch started
from, verified with `git show <base>:runtimes/watchdog/src/main.rs | rustfmt --check`. This is the
third instance in this effort of a plan asserting a green gate that was never green — the earlier two
were a task boundary that left the tree non-compiling, and a JavaScript plan written for a test
framework the package does not have. The replacement gates are narrower and true: `rustfmt --check`
on the one new file, and `cargo clippy -- -D warnings`, which does pass at baseline.

**Task 1's `#![allow(dead_code)]` is now explicitly Task 3's to remove.** It was not in the original
plan at all; Task 1's implementer had to add it because clippy rejects an entirely uncalled module
under `-D warnings`, and reported it honestly as temporary. The plan now names it in the Global
Constraints and makes its deletion a numbered step with its own verification, so it cannot quietly
survive to merge the way an unguarded scaffolding bridge would.
