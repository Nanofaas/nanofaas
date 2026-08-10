# Handler response envelope: status codes, headers, binary payloads

> Addresses GitHub issues #174 (status codes), #175 (headers), #176 (binary payloads).

## Context

Today a function handler returns a plain value; the runtime always answers HTTP 200 on
success (or a fixed platform error status on exception/timeout). This blocks legitimate
use cases: 404 for a missing resource, 201/204 for side effects, custom 4xx client
errors, reading request headers (e.g. `Authorization`) or setting response headers
(`Content-Type`, `Location`, `Cache-Control`), and returning binary payloads without a
base64 round-trip. All three issues extend the same request/response contract, so they
are designed together and implemented in phases.

Current contract (verified in code, 2026-08-10):
- `platform/common`: `FunctionHandler.handle(InvocationRequest) -> Object`,
  `InvocationRequest(Object input, Map<String,String> metadata)`,
  `InvocationResponse(String executionId, String status, Object output, ErrorInfo error)`.
- `sdks/java`: `InvokeController` always returns `ResponseEntity.ok()` on success;
  exceptions/timeouts map to fixed 500/504.
- `sdks/python`: `runtime/app.py` — same shape, plain dict/value in, JSON out, fixed 500.
- `sdks/javascript`: `Handler = (ctx, req) => JsonValue | Promise<JsonValue>`; the
  runtime's internal `writeJson(res, statusCode, payload, headers)` already accepts
  arbitrary status/headers — just not exposed to the handler.
- `sdks/go`: `Handler func(ctx, req) (any, error)`; same always-200-unless-error shape;
  `writeJSON(w, status, body)` already takes a status param internally.
- `runtimes/watchdog` (Rust): the STDIO execution mode used by bash functions treats
  the child process's entire stdout as the JSON output, verbatim — no envelope concept
  today. This is not a per-language SDK; it is the generic process-supervisor contract.
- `platform/control-plane`: `InvocationController.invokeSync` always returns
  `ResponseEntity.ok()` on success. `ExternalDispatcher` maps non-2xx to
  `EXTERNAL_ERROR`. `DefaultOffloadGateway.invokeRemote` calls `:invoke` on a remote
  control-plane and consumes `InvocationResponse` — the same public model used by
  direct callers.

## Scope and sequencing

**This milestone** (implements #174/#175/#176 end-to-end for two languages):
- The wire-level envelope (this document) as the permanent, language-agnostic contract.
- `platform/common` model changes.
- `sdks/java` and `sdks/python` SDK bindings.
- Full control-plane propagation: runtime response → `ExternalDispatcher` →
  `InvocationResponse` → `InvocationController.invokeSync` → `ExecutionStore` (async) →
  offload (inherits for free via the shared `InvocationResponse` model).
- One updated example function per implemented language (Java, Python) demonstrating
  the envelope, serving as both documentation and an integration-test proof.

**Future milestone** (explicitly deferred, tracked here so it isn't lost):
- `sdks/javascript` and `sdks/go` bindings (the wire contract does not change for
  them — this is "translate to idiomatic syntax," not a new design).
- The `runtimes/watchdog` STDIO envelope for bash functions (marker-based, see below).
- An extensive pass updating **all** existing example functions (Java, Python, JS, Go,
  bash) across `functions/*` to use the envelope where it fits their domain (e.g.
  `json-transform` → 400 on malformed input, `roman-numeral` → 422 on out-of-range
  input, `mlimage` → a custom `Content-Type` on the returned image).
- `tools/fn-init` template updates so newly scaffolded functions reflect the pattern.

## Wire-level envelope

The canonical, language-agnostic contract. Every per-language SDK binding is a
convenience wrapper that serializes to/deserializes from this shape; it is not a new
value language SDKs need to reinvent.

```json
{
  "__nanofaas_envelope__": true,
  "output": "<any JSON value, or a base64 string when encoding is set>",
  "statusCode": 201,
  "headers": { "Content-Type": "application/pdf" },
  "encoding": "base64"
}
```

- `__nanofaas_envelope__: true` is a required marker. It exists **only** to
  disambiguate the untyped bash/STDIO path (a script's stdout JSON might legitimately
  contain a `statusCode` key as real output data — the marker removes the ambiguity).
  Typed SDKs (Java/Python/JS/Go) never need to inspect this field themselves; the
  language runtime detects the envelope via its own type system (see below) and adds
  the marker only when serializing to the wire.
- `statusCode`: optional, integer, valid range `[200, 599]`. Absent means "no opinion,
  default to 200" (current behavior, unchanged).
- `headers`: optional map, only entries whose key is in the response allow-list survive
  (see Validation). Anything else is silently dropped with a warning log.
- `encoding`: optional, only legal value today is `"base64"`. Absent means `output` is
  a plain JSON value (current behavior).

## Request side: headers in

`InvocationRequest` gains `headers: Map<String,String>` (Java) / equivalent per SDK.
**All** incoming request headers are exposed to the handler except:
- Hop-by-hop transport headers (`Connection`, `Transfer-Encoding`, `Keep-Alive`) —
  meaningless past the first hop.
- Headers already exposed through a dedicated, existing mechanism
  (`X-Execution-Id`, `X-Trace-Id`, `X-Dispatch-Attempt`).

No filtering of things like `Authorization` — these are the caller's headers, not the
control plane's, so there is no spoofing risk on the read side.

## Response side: status codes and headers out

**Status codes** (#174): the distinguishing signal is *structural*, not numeric. If the
handler explicitly returns an envelope value (any status, including 5xx), that is a
deliberate function decision and the control plane trusts it as-is, regardless of the
number. Concretely (verified against `ExecutionCompletionHandler`, 2026-08-10): retry
is driven by `InvocationResult.success() == false`
(`shouldRetry = !result.success() && attempt <= maxRetries`) — a function-decided
envelope response always maps to `InvocationResult.success(output)` (`success = true`),
so it can never enter the retry path, no matter what status code the function chose.
Offload is not reactive to a response at all — `shouldOffloadEagerly`/
`shouldOffloadOnPressure` are pre-dispatch routing decisions based on policy and queue
pressure, made *before* the function ever responds — so there is no "offload on 5xx"
interaction to design for; offload is simply orthogonal to this feature. If the handler
throws an exception or times out, no envelope is ever produced — that remains today's
platform-error path (`HANDLER_ERROR`→500, `HANDLER_TIMEOUT`→504,
`InvocationResult.success = false`) completely unchanged, with existing retry
semantics. There is no numeric threshold and no opt-in flag; the envelope's mere
presence *is* the "trust this" signal.

**Response headers** (#175): an explicit allow-list — `Content-Type`, `Location`,
`Cache-Control`, `ETag`, `Content-Disposition`, `Content-Language`, `Retry-After`,
`Vary` — not a namespace convention. Control headers the platform already owns
(`X-Execution-Id`, `X-Cold-Start`, `X-Init-Duration-Ms`, `X-NanoFaaS-Offload-Hop`,
`X-Trace-Id`, ...) are always blocked regardless of what the handler tries to set —
this is checked by header name, independent of the allow-list, so a handler can never
spoof a control header even if it were accidentally added to the allow-list later.

## Binary payloads (#176)

The envelope stays JSON — same transport as status codes and headers, so the rest of
the pipeline (sync-queue, offload, callback) continues to see exactly one wire format.
`encoding: "base64"` on the envelope marks `output` (or, on the request side, `input`)
as a base64 string instead of a plain JSON value. No parallel raw-bytes transport is
introduced. Cost: ~33% size overhead and an encode/decode step; benefit: zero new
code paths in sync-queue, offload gateway, or the callback client.

## Per-language detection (this milestone)

- **Java**: new record in `platform/common`:
  ```java
  public record HandlerResponse(Object output, int statusCode,
                                 Map<String,String> headers, String encoding) {}
  ```
  `FunctionHandler.handle()` keeps its exact signature (`Object handle(InvocationRequest)`)
  — zero breaking change. At runtime (`HandlerExecutor`/`InvokeController`), an
  `instanceof HandlerResponse` check on the return value selects the new path; any
  other value keeps today's behavior (200, no headers, no encoding).
- **Python**: a `HandlerResponse` dataclass in `nanofaas.sdk`; `isinstance(result,
  HandlerResponse)` selects the new path in `runtime/app.py`. The decorated handler's
  signature is unchanged.

## Control-plane propagation (this milestone)

1. Runtime SDK (`InvokeController` / Python `app.py`): if the handler returned a
   `HandlerResponse`, the runtime's own `/invoke` HTTP response uses that status and
   allow-listed headers, and the callback payload carries `statusCode`/`headers`/
   `encoding` alongside `output`.
2. `ExternalDispatcher`: propagates status/headers/encoding into `DispatchResult` /
   `InvocationResult` instead of collapsing to a 2xx/non-2xx boolean.
3. `InvocationResponse` (`platform/common`, the public API model): gains `statusCode`,
   `headers`, `encoding`.
4. `InvocationController.invokeSync`: when `InvocationResponse.statusCode()` is
   present, it becomes the actual HTTP status of the response to the external caller
   of `:invoke` (allow-listed headers copied onto the `ResponseEntity`); absent means
   200 as today. Platform-owned statuses (404 unknown function, 429 rate-limited, 501
   async disabled, 502/504 offload failure) are untouched — they live outside this path.
5. `ExecutionStore`: persists `statusCode`/`headers` on the completed-execution record
   so `GET /v1/functions/{name}/executions/{id}` (the async polling path) returns them
   too.
6. Offload: `DefaultOffloadGateway.invokeRemote` already exchanges `InvocationResponse`
   end-to-end with the remote control-plane — it inherits the new fields for free, no
   offload-specific code change needed.

## Validation and error handling

- **Disallowed/reserved response header**: dropped silently (warning logged), does not
  fail the invocation — a handler bug should not break the response.
- **`statusCode` outside `[200, 599]` or non-integer**: treated as a platform error
  (mapped to 500, logged explicitly) rather than passed through — an invalid value
  would break the HTTP stack downstream. Inside the range, any value passes untouched,
  including 3xx and 5xx, consistent with "if the function returned it, don't touch it."
- **`encoding: "base64"` with an invalid base64 string**: reuses the existing
  `OUTPUT_SERIALIZATION_ERROR` platform-error path in `InvokeController` — corrupt
  binary data is never silently propagated.

## Future: the watchdog STDIO envelope (deferred)

For bash functions, the entire stdout of the child process is parsed as JSON and used
verbatim as `output` today — there is no type system to distinguish "this is envelope
metadata" from "this happens to be the script's real JSON output." When implemented,
the watchdog checks for `__nanofaas_envelope__: true` in the parsed stdout JSON; if
present, it unpacks `statusCode`/`headers`/`encoding`, otherwise it keeps today's
behavior (the whole JSON value is the output, 200). Same rule for the watchdog's HTTP
proxy mode, mapped from the fronted runtime's own HTTP response instead of stdout.

## Testing

- Unit tests per SDK: `HandlerResponse` detection and serialization (Java, Python).
- A shared JSON fixture of the wire envelope, asserted against by both the Java and
  Python runtime test suites, so the two SDKs cannot silently drift from one format.
- Control-plane integration test: register a function (`LOCAL`/`EXTERNAL` mode) whose
  handler returns a custom status/header, invoke `:invoke` synchronously, assert the
  actual HTTP response to the caller reflects it (not just an internal field).
- Regression test: existing handlers returning a plain value still produce HTTP 200
  with no extra headers — proves backward compatibility.
