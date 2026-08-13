# Handler Response Envelope — Go and JavaScript Bindings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let handlers written in Go and JavaScript control their HTTP status code, response
headers, and base64 `encoding` marker, using the same wire contract Java and Python already
implement.

**Architecture:** Both SDKs gain an envelope type the handler may optionally return instead of a
plain value, detected **nominally**: a distinct struct type in Go, an exported class checked with
`instanceof` in JavaScript. The runtime applies the handler's status and allow-listed headers to its
own `/invoke` response, emits the two marker headers, and carries the three fields on the async
callback body. Nothing about the wire contract changes — this is idiomatic translation of a frozen
design.

**Tech Stack:** Go (stdlib `net/http`, `encoding/json`), TypeScript/Node (`node:http`), `go test`,
and Node's built-in test runner.

**The JavaScript SDK does not use vitest, jest, or any test framework.** Its `npm test` script is
`tsc -p tsconfig.test.json && node --test build-test/test/**/*.test.js` — tests are written in
TypeScript against `node:test` and `node:assert/strict`, compiled to `build-test/`, then run by
Node's own runner. `devDependencies` contains only `@types/node` and `typescript`. Do not add a test
framework, do not write `describe`/`expect`, and do not run `npx vitest` — it is not installed, and
invoking it silently reports "no tests" rather than failing, which reads as a green run.

**Context:** the envelope shipped for Java and Python in `0ffaf217`, with follow-up fixes in
`79f41017` and `bfbdfa4a`. This plan is workstream 1 of GitHub issue #193. The watchdog STDIO
envelope, the example-function pass and the `fn-init` templates are separate plans.

## Global Constraints

- **The wire contract is frozen. Field names on the callback body are camelCase: `statusCode`,
  `headers`, `encoding`.** The control plane deserializes that body straight into Java's
  `InvocationResult` under default Jackson naming, and there is no snake_case strategy configured
  anywhere. A mismatch is silent — the field simply vanishes. Go must use explicit struct tags;
  JavaScript must use those exact property names.
- **Detection is nominal, never structural.** Go: a distinct `HandlerResponse` struct type.
  JavaScript: `result instanceof HandlerResponse` against an exported class. A structural check
  (`"statusCode" in result`) is a defect — TypeScript erases types at runtime and a plain object
  that happens to carry those keys would be silently promoted to an envelope.
- Status codes a handler may set: integers in `[200, 599]`. Outside that range (or non-integer) is a
  **platform error** — the runtime returns its own 500 through the existing error path and reports
  the failure on the callback, exactly as Java's `InvokeController` does. It is never passed through.
- Response headers a handler may set: exactly `Content-Type`, `Location`, `Cache-Control`, `ETag`,
  `Content-Disposition`, `Content-Language`, `Retry-After`, `Vary`, compared case-insensitively.
  Anything else is dropped with a WARN log and **never fails the invocation**. At most one entry
  survives per header name (case-insensitive, first occurrence wins, original casing preserved) —
  this mirrors `ResponseHeaderPolicy.filterAllowedHeaders` in `platform/common`.
- **Case folding must use an invariant locale.** Go's `strings.ToLower` is already Unicode-invariant;
  JavaScript must use `toLowerCase()` on ASCII header names, which is invariant in JS (unlike Java,
  where this was a real bug fixed in `bfbdfa4a`). Do not introduce `toLocaleLowerCase`.
- Control headers can never be set by a handler regardless of the allow-list: `X-Execution-Id`,
  `X-Cold-Start`, `X-Init-Duration-Ms`, `X-NanoFaaS-Offload-Hop`, `X-NanoFaaS-Function-Status`,
  `X-NanoFaaS-Encoding`, `X-Trace-Id`, `X-Dispatch-Attempt`, `X-NanoFaaS-Offloaded`,
  `X-Queue-Reject-Reason`. None of them appear in the 8-name allow-list, so the filter already
  excludes them — a test must prove it rather than assuming.
- **Two marker headers, byte-identical to what Java and Python emit:**
  `X-NanoFaaS-Function-Status: true` on exactly the envelope path, and `X-NanoFaaS-Encoding: <value>`
  only when `encoding` is non-null. Never on a plain-value success, never on a platform error
  (handler exception, timeout, invalid status). `ExternalDispatcher` reads both across the process
  boundary and cannot see the in-process type check.
- An envelope response's body is `output` **verbatim** — no wrapping, no envelope object on the wire
  at this hop.
- `encoding`: the only legal value is `"base64"` (or absent/null). The platform never encodes or
  decodes — it is a pure carrier for the marker.
- **Request headers arrive in the JSON request body** as `InvocationRequest.headers`, already
  lower-cased by the control plane at ingress. Neither runtime may read its own inbound HTTP headers
  for caller data: `ExternalDispatcher` forwards only four fixed control headers to a runtime.
- **Backward compatibility is mandatory:** a handler returning a plain value must behave exactly as
  today — same status, same body shape, no extra headers, no marker headers. Every task includes a
  regression test proving it.
- Do not touch `platform/`, `sdks/java`, `sdks/python`, `runtimes/watchdog`, `tools/fn-init`, or any
  function under `functions/` — all out of scope for this plan.
- Do NOT add a `Co-Authored-By` trailer to any commit message.

---

## File Structure

**Go** (`sdks/go/nanofaas/`, package `nanofaas`):
- `types.go` — gains `HandlerResponse` and the three fields on `InvocationResult`. Existing
  `Success`/`Failure` factories keep their signatures.
- `response_policy.go` *(new)* — the allow-list, the status-range check, and the dedupe filter. One
  responsibility, mirroring `ResponseHeaderPolicy`; kept out of `types.go` so the data types stay
  free of policy.
- `http_invoke.go` — the success path detects the envelope and applies status/headers/markers.

**JavaScript** (`sdks/javascript/src/`):
- `types.ts` — `CallbackPayload` gains the three fields; `Handler`'s return type widens.
- `response.ts` *(new)* — the exported `HandlerResponse` class and the header policy.
- `runtime.ts` — the `/invoke` success path detects the envelope and applies status/headers/markers.
- `index.ts` — exports `HandlerResponse`.

---

### Task 1: Go — `HandlerResponse`, the header policy, and the result fields

**Files:**
- Create: `sdks/go/nanofaas/response_policy.go`
- Modify: `sdks/go/nanofaas/types.go`
- Test: `sdks/go/nanofaas/response_policy_test.go` (create), `sdks/go/nanofaas/types_test.go`

**Interfaces:**
- Produces, consumed by Task 2:
  - `type HandlerResponse struct { Output any; StatusCode int; Headers map[string]string; Encoding string }`
  - `func NewHandlerResponse(output any, statusCode int) HandlerResponse`
  - `func IsStatusCodeValid(statusCode int) bool` — true for `[200,599]` inclusive.
  - `func FilterAllowedHeaders(raw map[string]string) map[string]string` — case-insensitive
    allow-list filter with first-occurrence-wins dedupe, preserving original casing; nil-safe,
    returns an empty (non-nil) map for nil input.
  - `func SuccessWithEnvelope(output any, statusCode int, headers map[string]string, encoding string) InvocationResult`
    — always sets `Success: true`.
  - `InvocationResult` gains `StatusCode *int \`json:"statusCode,omitempty"\``,
    `Headers map[string]string \`json:"headers,omitempty"\``,
    `Encoding string \`json:"encoding,omitempty"\``.

**Why `*int` for `StatusCode`:** the field must be absent from the callback JSON when the handler had
no opinion. A plain `int` with `omitempty` would also omit a legitimate `0`, but more importantly it
cannot distinguish "no opinion" from a zero value, and the control plane's Java side reads it as a
nullable `Integer`. Use a pointer.

- [ ] **Step 1: Write the failing policy test**

Create `sdks/go/nanofaas/response_policy_test.go`:

```go
package nanofaas

import (
	"reflect"
	"testing"
)

func TestIsStatusCodeValid(t *testing.T) {
	for _, code := range []int{200, 422, 599} {
		if !IsStatusCodeValid(code) {
			t.Errorf("expected %d to be valid", code)
		}
	}
	for _, code := range []int{199, 600, 0, -1, 999} {
		if IsStatusCodeValid(code) {
			t.Errorf("expected %d to be invalid", code)
		}
	}
}

func TestFilterAllowedHeadersKeepsAllowedDropsEverythingElse(t *testing.T) {
	filtered := FilterAllowedHeaders(map[string]string{
		"Content-Type":   "application/pdf",
		"X-Custom":       "nope",
		"X-Execution-Id": "spoofed",
	})
	if !reflect.DeepEqual(filtered, map[string]string{"Content-Type": "application/pdf"}) {
		t.Errorf("unexpected filtered headers: %v", filtered)
	}
}

func TestFilterAllowedHeadersPreservesCasingAndDedupes(t *testing.T) {
	filtered := FilterAllowedHeaders(map[string]string{"Content-Type": "application/pdf"})
	if filtered["Content-Type"] != "application/pdf" {
		t.Errorf("original casing must be preserved, got %v", filtered)
	}
	if len(filtered) != 1 {
		t.Errorf("expected exactly one entry, got %v", filtered)
	}
}

func TestFilterAllowedHeadersNilIsEmptyNotNil(t *testing.T) {
	filtered := FilterAllowedHeaders(nil)
	if filtered == nil || len(filtered) != 0 {
		t.Errorf("expected an empty non-nil map, got %v", filtered)
	}
}
```

Note on the dedupe test: Go maps have no defined iteration order, so a two-casing input cannot assert
*which* occurrence wins deterministically. The test above therefore pins casing preservation and
single-entry output only. Do not write a test that asserts first-occurrence-wins for Go — it would be
flaky. The dedupe still matters (it prevents two colliding entries reaching `w.Header().Set`), and
Task 2's integration test covers that.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd sdks/go && go test ./nanofaas/ -run "TestIsStatusCodeValid|TestFilterAllowedHeaders" -v`
Expected: FAIL to compile — `undefined: IsStatusCodeValid`, `undefined: FilterAllowedHeaders`.

- [ ] **Step 3: Implement the policy**

Create `sdks/go/nanofaas/response_policy.go`:

```go
package nanofaas

import "strings"

// allowedResponseHeaders mirrors ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS in platform/common.
// Keep the two in sync.
var allowedResponseHeaders = map[string]struct{}{
	"content-type":        {},
	"location":            {},
	"cache-control":       {},
	"etag":                {},
	"content-disposition": {},
	"content-language":    {},
	"retry-after":         {},
	"vary":                {},
}

// IsStatusCodeValid reports whether a handler-chosen status code is legal to pass through.
func IsStatusCodeValid(statusCode int) bool {
	return statusCode >= 200 && statusCode <= 599
}

// FilterAllowedHeaders filters handler-supplied response headers down to the allow-list.
//
// At most one entry survives per header name, compared case-insensitively: HTTP header names are
// case-insensitive, so emitting both Content-Type and content-type would put two colliding entries
// on the response. The surviving key keeps its original casing.
func FilterAllowedHeaders(raw map[string]string) map[string]string {
	filtered := make(map[string]string, len(raw))
	seen := make(map[string]struct{}, len(raw))
	for key, value := range raw {
		lowerKey := strings.ToLower(key)
		if _, ok := allowedResponseHeaders[lowerKey]; !ok {
			continue
		}
		if _, dup := seen[lowerKey]; dup {
			continue
		}
		seen[lowerKey] = struct{}{}
		filtered[key] = value
	}
	return filtered
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd sdks/go && go test ./nanofaas/ -run "TestIsStatusCodeValid|TestFilterAllowedHeaders" -v`
Expected: PASS, 4 tests.

- [ ] **Step 5: Write the failing types test**

Add to `sdks/go/nanofaas/types_test.go`:

```go
func TestSuccessWithEnvelopeIsAlwaysSuccess(t *testing.T) {
	code := 503
	result := SuccessWithEnvelope("body", code, map[string]string{"Location": "/x"}, "base64")

	if !result.Success {
		t.Error("a function-decided result must be Success=true so it is never retried")
	}
	if result.StatusCode == nil || *result.StatusCode != 503 {
		t.Errorf("expected statusCode 503, got %v", result.StatusCode)
	}
	if result.Encoding != "base64" {
		t.Errorf("expected encoding base64, got %q", result.Encoding)
	}
}

func TestInvocationResultCallbackJSONUsesCamelCaseWireKeys(t *testing.T) {
	result := SuccessWithEnvelope("body", 201, map[string]string{"Location": "/x"}, "base64")
	encoded, err := json.Marshal(result)
	if err != nil {
		t.Fatalf("marshal failed: %v", err)
	}
	body := string(encoded)
	for _, key := range []string{`"statusCode"`, `"headers"`, `"encoding"`} {
		if !strings.Contains(body, key) {
			t.Errorf("callback body must contain %s, got %s", key, body)
		}
	}
	for _, wrong := range []string{`"status_code"`, `"Encoding"`, `"StatusCode"`} {
		if strings.Contains(body, wrong) {
			t.Errorf("callback body must not contain %s, got %s", wrong, body)
		}
	}
}

func TestPlainSuccessOmitsEnvelopeFields(t *testing.T) {
	encoded, err := json.Marshal(Success("body"))
	if err != nil {
		t.Fatalf("marshal failed: %v", err)
	}
	body := string(encoded)
	for _, key := range []string{"statusCode", "headers", "encoding"} {
		if strings.Contains(body, key) {
			t.Errorf("a plain success must omit %s, got %s", key, body)
		}
	}
}
```

Add `"encoding/json"` and `"strings"` to that file's imports if absent.

- [ ] **Step 6: Run it to verify it fails**

Run: `cd sdks/go && go test ./nanofaas/ -run "TestSuccessWithEnvelope|TestInvocationResultCallback|TestPlainSuccess" -v`
Expected: FAIL to compile — `undefined: SuccessWithEnvelope`, and `result.StatusCode` undefined.

- [ ] **Step 7: Implement the type changes**

In `sdks/go/nanofaas/types.go`, replace the `InvocationResult` block and add `HandlerResponse`:

```go
// HandlerResponse is the optional envelope a handler may return instead of a plain value, to
// control the HTTP status code, response headers, and the base64 encoding marker.
//
// Detection is nominal: the runtime type-switches on this exact struct type. A handler returning
// any other value keeps today's behavior (implicit 200, no extra headers).
type HandlerResponse struct {
	Output     any
	StatusCode int
	Headers    map[string]string
	Encoding   string
}

// NewHandlerResponse builds an envelope with no headers and no encoding marker.
func NewHandlerResponse(output any, statusCode int) HandlerResponse {
	return HandlerResponse{Output: output, StatusCode: statusCode}
}

type InvocationResult struct {
	Success bool       `json:"success"`
	Output  any        `json:"output"`
	Error   *ErrorInfo `json:"error"`
	// Wire keys are camelCase to match platform/common's InvocationResult under default Jackson
	// naming. A mismatch here is silent — the field just vanishes on the control plane.
	StatusCode *int              `json:"statusCode,omitempty"`
	Headers    map[string]string `json:"headers,omitempty"`
	Encoding   string            `json:"encoding,omitempty"`
}

// SuccessWithEnvelope builds a function-decided result. Success is always true: retry is driven by
// !Success, so a function-decided response must never be retried whatever its status, 5xx included.
func SuccessWithEnvelope(output any, statusCode int, headers map[string]string, encoding string) InvocationResult {
	return InvocationResult{
		Success:    true,
		Output:     output,
		StatusCode: &statusCode,
		Headers:    headers,
		Encoding:   encoding,
	}
}
```

Leave `Success`, `Failure` and `ErrorInfo` exactly as they are.

- [ ] **Step 8: Run it to verify it passes**

Run: `cd sdks/go && go test ./nanofaas/ -v`
Expected: PASS, the whole package including the pre-existing tests.

- [ ] **Step 9: Commit**

```bash
git add sdks/go/nanofaas/types.go sdks/go/nanofaas/types_test.go \
        sdks/go/nanofaas/response_policy.go sdks/go/nanofaas/response_policy_test.go
git commit -m "feat: add HandlerResponse envelope and response header policy to the Go SDK"
```

---

### Task 2: Go — the runtime honours the envelope

**Files:**
- Modify: `sdks/go/nanofaas/http_invoke.go:63-80` (the `case result := <-resultCh:` success branch)
- Test: `sdks/go/nanofaas/http_invoke_test.go`

**Interfaces:**
- Consumes from Task 1: `HandlerResponse`, `NewHandlerResponse`, `IsStatusCodeValid`,
  `FilterAllowedHeaders`, `SuccessWithEnvelope`, and `InvocationResult`'s three new fields.
- Produces: nothing later tasks consume. The `Handler` signature
  (`func(ctx context.Context, req InvocationRequest) (any, error)`) is unchanged — the envelope
  travels as the `any` return value.

- [ ] **Step 1: Write the failing tests**

Add to `sdks/go/nanofaas/http_invoke_test.go`, following the file's existing style for building a
runtime and driving `handleInvoke` with an `httptest` recorder:

```go
func TestInvokeHandlerResponseAppliesStatusHeadersAndMarker(t *testing.T) {
	rec := invokeWithHandler(t, func(ctx context.Context, req InvocationRequest) (any, error) {
		return HandlerResponse{
			Output:     map[string]string{"error": "not found"},
			StatusCode: 404,
			Headers:    map[string]string{"Location": "/x", "X-Custom": "dropped"},
			Encoding:   "base64",
		}, nil
	})

	if rec.Code != 404 {
		t.Errorf("expected 404, got %d", rec.Code)
	}
	if got := rec.Header().Get("Location"); got != "/x" {
		t.Errorf("allow-listed header must survive, got %q", got)
	}
	if got := rec.Header().Get("X-Custom"); got != "" {
		t.Errorf("disallowed header must be dropped, got %q", got)
	}
	if got := rec.Header().Get("X-NanoFaaS-Function-Status"); got != "true" {
		t.Errorf("marker header must be set on the envelope path, got %q", got)
	}
	if got := rec.Header().Get("X-NanoFaaS-Encoding"); got != "base64" {
		t.Errorf("encoding header must be set when encoding is present, got %q", got)
	}
}

func TestInvokeHandlerCannotSpoofControlHeaders(t *testing.T) {
	rec := invokeWithHandler(t, func(ctx context.Context, req InvocationRequest) (any, error) {
		return HandlerResponse{
			Output:     "ok",
			StatusCode: 200,
			Headers: map[string]string{
				"X-NanoFaaS-Function-Status": "spoofed",
				"X-NanoFaaS-Encoding":        "spoofed",
				"X-Execution-Id":             "spoofed",
			},
		}, nil
	})

	if got := rec.Header().Get("X-NanoFaaS-Encoding"); got != "" {
		t.Errorf("a handler must not be able to set the encoding marker, got %q", got)
	}
	if got := rec.Header().Get("X-NanoFaaS-Function-Status"); got != "true" {
		t.Errorf("the marker must come from the runtime, not the handler, got %q", got)
	}
}

func TestInvokeHandlerResponseInvalidStatusIsPlatformError(t *testing.T) {
	rec := invokeWithHandler(t, func(ctx context.Context, req InvocationRequest) (any, error) {
		return HandlerResponse{Output: "ok", StatusCode: 999}, nil
	})

	if rec.Code != 500 {
		t.Errorf("an out-of-range status must be a platform error, got %d", rec.Code)
	}
	if got := rec.Header().Get("X-NanoFaaS-Function-Status"); got != "" {
		t.Errorf("no marker on a platform error, got %q", got)
	}
}

func TestInvokePlainValueBehavesExactlyAsBefore(t *testing.T) {
	rec := invokeWithHandler(t, func(ctx context.Context, req InvocationRequest) (any, error) {
		return map[string]string{"roman": "XLII"}, nil
	})

	if rec.Code != 200 {
		t.Errorf("expected 200, got %d", rec.Code)
	}
	if got := rec.Header().Get("X-NanoFaaS-Function-Status"); got != "" {
		t.Errorf("no marker on a plain-value success, got %q", got)
	}
	if got := rec.Header().Get("X-NanoFaaS-Encoding"); got != "" {
		t.Errorf("no encoding header on a plain-value success, got %q", got)
	}
	if body := strings.TrimSpace(rec.Body.String()); body != `{"roman":"XLII"}` {
		t.Errorf("plain body shape must be unchanged, got %s", body)
	}
}
```

Write the `invokeWithHandler(t, handler) *httptest.ResponseRecorder` helper at the bottom of the same
file, reusing whatever runtime construction the existing tests in that file already do (read them
first — do not invent a new construction path). It must register the handler, POST an
`InvocationRequest` to `handleInvoke` with an `X-Execution-Id` header set, and return the recorder.

- [ ] **Step 2: Run to verify they fail**

Run: `cd sdks/go && go test ./nanofaas/ -run "TestInvokeHandler|TestInvokePlain" -v`
Expected: FAIL — the envelope is currently JSON-encoded as the response body, so the status stays
200 and no markers are set.

- [ ] **Step 3: Implement the runtime change**

In `sdks/go/nanofaas/http_invoke.go`, replace the success branch of the `select` (currently lines
73-80, from `r.markInvocation("success")` through the `json.NewEncoder(w).Encode(result.output)`):

```go
		r.markInvocation("success")

		envelope, isEnvelope := result.output.(HandlerResponse)
		if isEnvelope && !IsStatusCodeValid(envelope.StatusCode) {
			message := fmt.Sprintf("Handler returned invalid statusCode: %d", envelope.StatusCode)
			r.submitCallback(runtimeContext, Failure("OUTPUT_SERIALIZATION_ERROR", message), dispatchAttempt)
			writeErrorJSON(w, http.StatusInternalServerError, message)
			return
		}

		status := http.StatusOK
		outputForWire := result.output
		if isEnvelope {
			status = envelope.StatusCode
			outputForWire = envelope.Output
			allowed := FilterAllowedHeaders(envelope.Headers)
			if len(allowed) != len(envelope.Headers) {
				dropped := make([]string, 0, len(envelope.Headers))
				for key := range envelope.Headers {
					if _, kept := allowed[key]; !kept {
						dropped = append(dropped, key)
					}
				}
				log.Printf("WARN dropped response header(s) %v for execution %s", dropped, runtimeContext.ExecutionID)
			}
			for key, value := range allowed {
				w.Header().Set(key, value)
			}
			w.Header().Set("X-NanoFaaS-Function-Status", "true")
			if envelope.Encoding != "" {
				w.Header().Set("X-NanoFaaS-Encoding", envelope.Encoding)
			}
			r.submitCallback(runtimeContext,
				SuccessWithEnvelope(envelope.Output, envelope.StatusCode, allowed, envelope.Encoding),
				dispatchAttempt)
		} else {
			r.submitCallback(runtimeContext, Success(result.output), dispatchAttempt)
		}

		if isColdStart {
			w.Header().Set("X-Cold-Start", "true")
			w.Header().Set("X-Init-Duration-Ms", formatInitDurationHeader(r.coldStart.InitDurationMs()))
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(outputForWire)
```

Two ordering details that matter: the allow-listed headers are applied **before** the two marker
headers, so a handler-supplied value can never overwrite a marker (belt-and-braces — the allow-list
already excludes them). And `w.WriteHeader(status)` must come after every `w.Header().Set` call;
Go's `net/http` silently ignores header mutations made after `WriteHeader`.

Add `"log"` to the file's imports. `"fmt"` and `"net/http"` are already imported.

- [ ] **Step 4: Run to verify they pass**

Run: `cd sdks/go && go test ./nanofaas/ -v`
Expected: PASS, the whole package.

- [ ] **Step 5: Vet and commit**

Run: `cd sdks/go && go vet ./...`
Expected: no output.

```bash
git add sdks/go/nanofaas/http_invoke.go sdks/go/nanofaas/http_invoke_test.go
git commit -m "feat: honour the HandlerResponse envelope in the Go runtime"
```

---

### Task 3: JavaScript — the `HandlerResponse` class, the header policy, and the types

**Files:**
- Create: `sdks/javascript/src/response.ts`
- Modify: `sdks/javascript/src/types.ts`, `sdks/javascript/src/index.ts`
- Test: `sdks/javascript/test/response.test.ts` (create)

**Interfaces:**
- Produces, consumed by Task 4:
  - `export class HandlerResponse { constructor(output: JsonValue, statusCode: number, headers?: Record<string,string>, encoding?: string); readonly output: JsonValue; readonly statusCode: number; readonly headers: Record<string,string>; readonly encoding?: string }`
  - `export function isStatusCodeValid(statusCode: number): boolean`
  - `export function filterAllowedHeaders(raw?: Record<string,string>): Record<string,string>`
- `Handler`'s return type widens to `JsonValue | HandlerResponse | Promise<JsonValue | HandlerResponse>`.
- `CallbackPayload`'s success arm gains `statusCode?: number; headers?: Record<string,string>; encoding?: string`.

- [ ] **Step 1: Write the failing test**

Create `sdks/javascript/test/response.test.ts`, using the same `node:test` + `node:assert/strict`
idiom every other file in that directory uses (flat `test(...)` calls, no `describe`/`expect`):

```typescript
import assert from "node:assert/strict";
import { test } from "node:test";

import { HandlerResponse, filterAllowedHeaders, isStatusCodeValid } from "../src/response.js";

test("HandlerResponse defaults headers to an empty object and encoding to undefined", () => {
    const response = new HandlerResponse({ error: "not found" }, 404);
    assert.deepEqual(response.output, { error: "not found" });
    assert.equal(response.statusCode, 404);
    assert.deepEqual(response.headers, {});
    assert.equal(response.encoding, undefined);
});

test("HandlerResponse is detected nominally, not structurally", () => {
    const lookalike = { output: "x", statusCode: 201, headers: {} };
    assert.equal(lookalike instanceof HandlerResponse, false);
    assert.equal(new HandlerResponse("x", 201) instanceof HandlerResponse, true);
});

test("isStatusCodeValid accepts 200..599 and rejects everything else", () => {
    for (const code of [200, 422, 599]) {
        assert.equal(isStatusCodeValid(code), true, `expected ${code} to be valid`);
    }
    for (const code of [199, 600, 0, -1, 999]) {
        assert.equal(isStatusCodeValid(code), false, `expected ${code} to be invalid`);
    }
});

test("filterAllowedHeaders keeps allow-listed headers and drops everything else", () => {
    assert.deepEqual(
        filterAllowedHeaders({
            "Content-Type": "application/pdf",
            "X-Custom": "nope",
            "X-Execution-Id": "spoofed",
        }),
        { "Content-Type": "application/pdf" },
    );
});

test("filterAllowedHeaders dedupes colliding casings keeping the first occurrence", () => {
    assert.deepEqual(
        filterAllowedHeaders({
            "Content-Type": "application/pdf",
            "content-type": "text/plain",
        }),
        { "Content-Type": "application/pdf" },
    );
});

test("filterAllowedHeaders returns an empty object for undefined", () => {
    assert.deepEqual(filterAllowedHeaders(undefined), {});
});
```

Unlike Go, JavaScript object property order is deterministic for string keys, so the
first-occurrence-wins assertion is stable here and must be pinned.

- [ ] **Step 2: Run to verify it fails**

Run: `cd sdks/javascript && npm test`
Expected: FAIL — `tsc -p tsconfig.test.json` errors with "Cannot find module './response.js'".

- [ ] **Step 3: Implement `response.ts`**

Create `sdks/javascript/src/response.ts`:

**Note on import paths:** this package is ESM and its source imports carry an explicit `.js`
extension (`export { getLogger } from "./logger.js";` in `index.ts`). Every new import below must
follow that convention or the build will not resolve. The `types.ts` ↔ `response.ts` pair import each
other, but both directions are `import type`, which TypeScript erases — there is no runtime cycle.

```typescript
import type { JsonValue } from "./types.js";

/**
 * Mirrors ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS in platform/common. Keep in sync.
 */
const ALLOWED_RESPONSE_HEADERS = new Set([
    "content-type",
    "location",
    "cache-control",
    "etag",
    "content-disposition",
    "content-language",
    "retry-after",
    "vary",
]);

/**
 * The optional envelope a handler may return instead of a plain value, to control the HTTP status
 * code, response headers, and the base64 encoding marker.
 *
 * This is a class rather than a plain object type on purpose: detection must be nominal. TypeScript
 * erases types at runtime, so a structural check would silently promote any plain object that
 * happened to carry a `statusCode` property.
 */
export class HandlerResponse {
    readonly output: JsonValue;
    readonly statusCode: number;
    readonly headers: Record<string, string>;
    readonly encoding?: string;

    constructor(
        output: JsonValue,
        statusCode: number,
        headers: Record<string, string> = {},
        encoding?: string,
    ) {
        this.output = output;
        this.statusCode = statusCode;
        this.headers = headers;
        if (encoding !== undefined) {
            this.encoding = encoding;
        }
    }
}

export function isStatusCodeValid(statusCode: number): boolean {
    return Number.isInteger(statusCode) && statusCode >= 200 && statusCode <= 599;
}

/**
 * Filters handler-supplied response headers down to the allow-list. At most one entry survives per
 * header name, compared case-insensitively; the first occurrence wins and keeps its original casing.
 */
export function filterAllowedHeaders(raw?: Record<string, string>): Record<string, string> {
    const filtered: Record<string, string> = {};
    const seen = new Set<string>();
    for (const [key, value] of Object.entries(raw ?? {})) {
        const lowerKey = key.toLowerCase();
        if (!ALLOWED_RESPONSE_HEADERS.has(lowerKey) || seen.has(lowerKey)) {
            continue;
        }
        seen.add(lowerKey);
        filtered[key] = value;
    }
    return filtered;
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd sdks/javascript && npm test`
Expected: PASS — the 6 new tests plus every pre-existing one.

- [ ] **Step 5: Widen the types and export the class**

In `sdks/javascript/src/types.ts`, change the `Handler` type and the `CallbackPayload` success arm:

```typescript
export type CallbackPayload =
    | {
        success: true;
        output: JsonValue;
        error: null;
        statusCode?: number;
        headers?: Record<string, string>;
        encoding?: string;
    }
    | {
        success: false;
        output: null;
        error: ErrorInfo;
    };

export type Handler = (
    ctx: HandlerContext,
    req: InvocationRequest,
) => JsonValue | HandlerResponse | Promise<JsonValue | HandlerResponse>;
```

Add `import type { HandlerResponse } from "./response.js";` at the top of `types.ts`.

In `sdks/javascript/src/index.ts`, add `export { HandlerResponse } from "./response.js";` — a value
export, not a type-only one, since handlers construct it with `new`. Keep it in the file's existing
alphabetical-ish grouping with the other value exports.

- [ ] **Step 6: Typecheck and commit**

Run: `cd sdks/javascript && npx tsc --noEmit -p tsconfig.json`
Expected: no errors.

Run: `cd sdks/javascript && npm test`
Expected: PASS, the whole suite.

```bash
git add sdks/javascript/src/response.ts sdks/javascript/src/types.ts \
        sdks/javascript/src/index.ts sdks/javascript/test/response.test.ts
git commit -m "feat: add HandlerResponse envelope and response header policy to the JS SDK"
```

---

### Task 4: JavaScript — the runtime honours the envelope

**Files:**
- Modify: `sdks/javascript/src/runtime.ts:333-348` (the `/invoke` success path)
- Test: `sdks/javascript/test/runtime.envelope.test.ts` (create)

**Interfaces:**
- Consumes from Task 3: `HandlerResponse`, `isStatusCodeValid`, `filterAllowedHeaders`, and the
  widened `CallbackPayload`/`Handler` types.

- [ ] **Step 1: Write the failing tests**

Create `sdks/javascript/test/runtime.envelope.test.ts`. Read `test/runtime.contract.test.ts` first
and reuse its runtime-start/stop and fetch helpers verbatim — do not invent a new harness:

```typescript
import assert from "node:assert/strict";
import { test } from "node:test";

import { createRuntime } from "../src/index.js";
import { HandlerResponse } from "../src/response.js";
import type { Handler } from "../src/types.js";

async function invokeWith(handler: Handler): Promise<Response> {
    const runtime = createRuntime({ port: 0 });
    runtime.register("fn", handler);
    await runtime.start();
    try {
        return await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: { "content-type": "application/json", "x-execution-id": "exec-1" },
            body: JSON.stringify({ input: "payload" }),
        });
    } finally {
        await runtime.stop();
    }
}

test("envelope applies the status, allow-listed headers and both markers", async () => {
    const response = await invokeWith(() => new HandlerResponse(
        { error: "not found" },
        404,
        { Location: "/x", "X-Custom": "dropped" },
        "base64",
    ));

    assert.equal(response.status, 404);
    assert.equal(response.headers.get("location"), "/x");
    assert.equal(response.headers.get("x-custom"), null);
    assert.equal(response.headers.get("x-nanofaas-function-status"), "true");
    assert.equal(response.headers.get("x-nanofaas-encoding"), "base64");
    assert.deepEqual(await response.json(), { error: "not found" });
});

test("envelope omits the encoding marker when the handler set no encoding", async () => {
    const response = await invokeWith(() => new HandlerResponse({ ok: true }, 201));

    assert.equal(response.status, 201);
    assert.equal(response.headers.get("x-nanofaas-function-status"), "true");
    assert.equal(response.headers.get("x-nanofaas-encoding"), null);
});

test("an out-of-range status is a platform error", async () => {
    const response = await invokeWith(() => new HandlerResponse({ ok: true }, 999));

    assert.equal(response.status, 500);
    assert.equal(response.headers.get("x-nanofaas-function-status"), null);
});

test("a handler cannot spoof the control headers", async () => {
    const response = await invokeWith(() => new HandlerResponse({ ok: true }, 200, {
        "X-NanoFaaS-Function-Status": "spoofed",
        "X-NanoFaaS-Encoding": "spoofed",
        "X-Execution-Id": "spoofed",
    }));

    assert.equal(response.headers.get("x-nanofaas-encoding"), null);
    assert.equal(response.headers.get("x-nanofaas-function-status"), "true");
});

test("a plain-value return behaves exactly as before", async () => {
    const response = await invokeWith(() => ({ roman: "XLII" }));

    assert.equal(response.status, 200);
    assert.equal(response.headers.get("x-nanofaas-function-status"), null);
    assert.equal(response.headers.get("x-nanofaas-encoding"), null);
    assert.deepEqual(await response.json(), { roman: "XLII" });
});
```

The `invokeWith` helper above mirrors the `withRuntime` pattern in `test/runtime.contract.test.ts`
(`createRuntime({ port: 0 })`, `register`, `start`, `fetch` against `runtime.baseUrl`, `stop` in a
`finally`). Read that file before writing yours and match whatever it actually does — if its helper
signature differs from what is sketched here, follow the file, not this sketch.

- [ ] **Step 2: Run to verify they fail**

Run: `cd sdks/javascript && npm test`
Expected: FAIL — the envelope instance is currently JSON-serialized as the body, so the status stays
200 and no markers appear.

- [ ] **Step 3: Implement the runtime change**

In `sdks/javascript/src/runtime.ts`, replace the success block (from `const output = await
invokeHandler(...)` through `writeJson(res, 200, output, responseHeaders);`):

```typescript
        const result = await invokeHandler(state, handler, ctx, payload);
        const isEnvelope = result instanceof HandlerResponse;

        if (isEnvelope && !isStatusCodeValid(result.statusCode)) {
            const message = `Handler returned invalid statusCode: ${result.statusCode}`;
            state.logger.warn(message, { executionId });
            state.metrics.invocations.inc({ success: "false" });
            dispatchCallback(state, callbackUrl, executionId, traceId, dispatchAttempt, {
                success: false,
                output: null,
                error: { code: "OUTPUT_SERIALIZATION_ERROR", message },
            });
            writeJson(res, 500, { error: { code: "OUTPUT_SERIALIZATION_ERROR", message } });
            return;
        }

        state.metrics.invocations.inc({ success: "true" });

        const responseHeaders: Record<string, string> = {};
        if (coldStart) {
            responseHeaders["x-cold-start"] = "true";
            responseHeaders["x-init-duration-ms"] = String(Date.now() - state.startedAt);
        }

        if (isEnvelope) {
            const allowed = filterAllowedHeaders(result.headers);
            const dropped = Object.keys(result.headers).filter((key) => !(key in allowed));
            if (dropped.length > 0) {
                state.logger.warn("dropped response header(s)", { executionId, dropped: dropped.join(", ") });
            }
            Object.assign(responseHeaders, allowed);
            responseHeaders["x-nanofaas-function-status"] = "true";
            if (result.encoding !== undefined) {
                responseHeaders["x-nanofaas-encoding"] = result.encoding;
            }
            dispatchCallback(state, callbackUrl, executionId, traceId, dispatchAttempt, {
                success: true,
                output: result.output,
                error: null,
                statusCode: result.statusCode,
                headers: allowed,
                encoding: result.encoding,
            });
            writeJson(res, result.statusCode, result.output, responseHeaders);
        } else {
            dispatchCallback(state, callbackUrl, executionId, traceId, dispatchAttempt, {
                success: true,
                output: result,
                error: null,
            });
            writeJson(res, 200, result, responseHeaders);
        }
```

Add `import { HandlerResponse, filterAllowedHeaders, isStatusCodeValid } from "./response.js";` to
the file's imports — a value import (the `instanceof` check needs the class at runtime), and note the
`.js` extension this package requires.

Note on header casing: `responseHeaders` keys are applied by `writeJson` via `res.setHeader`, and the
allow-listed entries are assigned **before** the two markers, so a handler-supplied value cannot
overwrite a marker. `writeJson` also sets `content-type` before applying `headers`, so a
handler-supplied `Content-Type` correctly wins — matching Java.

- [ ] **Step 4: Run to verify they pass**

Run: `cd sdks/javascript && npm test`
Expected: PASS, the whole suite including the pre-existing runtime tests.

- [ ] **Step 5: Typecheck, then commit**

Run: `cd sdks/javascript && npx tsc --noEmit -p tsconfig.json`
Expected: no errors.

```bash
git add sdks/javascript/src/runtime.ts sdks/javascript/test/runtime.envelope.test.ts
git commit -m "feat: honour the HandlerResponse envelope in the JS runtime"
```

---

### Task 5: Cross-SDK wire-parity check

**Files:**
- Test: `sdks/go/nanofaas/wire_parity_test.go` (create),
  `sdks/javascript/test/wire-parity.test.ts` (create)

**Interfaces:**
- Consumes everything from Tasks 1-4.

**What this proves:** that Go and JavaScript emit the same header names and the same callback JSON
keys as each other and as Java/Python. Four independent implementations of one frozen contract drift
silently; the milestone-1 bug that reached final review was exactly a wire-level field that existed
in one channel and not another.

- [ ] **Step 1: Write the Go parity test**

Create `sdks/go/nanofaas/wire_parity_test.go`:

```go
package nanofaas

import "testing"

// These constants are the frozen wire contract, shared with platform/common (Java),
// sdks/python, and sdks/javascript. Changing one without the others silently breaks
// cross-language dispatch.
func TestWireContractConstants(t *testing.T) {
	rec := invokeWithHandler(t, func(ctx context.Context, req InvocationRequest) (any, error) {
		return HandlerResponse{Output: "x", StatusCode: 201, Encoding: "base64"}, nil
	})

	if got := rec.Header().Get("X-NanoFaaS-Function-Status"); got != "true" {
		t.Errorf(`marker header must be exactly "X-NanoFaaS-Function-Status: true", got %q`, got)
	}
	if got := rec.Header().Get("X-NanoFaaS-Encoding"); got != "base64" {
		t.Errorf(`encoding header must be exactly "X-NanoFaaS-Encoding", got %q`, got)
	}
}
```

Add `"context"` to the imports. This reuses `invokeWithHandler` from Task 2's test file — same
package, so no import is needed.

- [ ] **Step 2: Write the JavaScript parity test**

Create `sdks/javascript/test/wire-parity.test.ts`:

```typescript
import assert from "node:assert/strict";
import { test } from "node:test";

import { HandlerResponse } from "../src/response.js";

// The frozen wire contract, shared with platform/common (Java), sdks/python and sdks/go.
const MARKER_HEADER = "x-nanofaas-function-status";
const ENCODING_HEADER = "x-nanofaas-encoding";
const CALLBACK_KEYS = ["statusCode", "headers", "encoding"];

test("the runtime emits the exact marker header names", async () => {
    const response = await invokeWith(() => new HandlerResponse("x", 201, {}, "base64"));

    assert.equal(response.headers.get(MARKER_HEADER), "true");
    assert.equal(response.headers.get(ENCODING_HEADER), "base64");
});

test("the callback body uses camelCase keys, never snake_case", async () => {
    const body = await captureCallbackBody(() => new HandlerResponse("x", 201, {}, "base64"));

    for (const key of CALLBACK_KEYS) {
        assert.ok(Object.keys(body).includes(key), `callback body must contain ${key}`);
    }
    assert.equal(Object.keys(body).includes("status_code"), false);
});
```

Write `invokeWith` and `captureCallbackBody` in this file — the test files in this directory are
self-contained and each defines its own helpers rather than sharing a module, so duplicate the small
`invokeWith` from Task 4 here rather than exporting it. `captureCallbackBody` must start a stub HTTP
server, point the runtime's `callbackUrl` at it, invoke once, wait for the callback to arrive, and
return the parsed JSON body the runtime POSTed. Read `test/runtime.callback.test.ts` first — it
already does exactly this kind of callback capture, including how it waits for the asynchronous
delivery; reuse its approach rather than inventing one.

- [ ] **Step 3: Run both**

Run: `cd sdks/go && go test ./nanofaas/ -v`
Expected: PASS.

Run: `cd sdks/javascript && npm test`
Expected: PASS.

- [ ] **Step 4: Verify the four languages agree, by inspection**

Confirm by reading the source that all four runtimes emit the identical header name strings:

- Java: `sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeController.java`
- Python: `sdks/python/src/nanofaas/runtime/app.py`
- Go: `sdks/go/nanofaas/http_invoke.go`
- JavaScript: `sdks/javascript/src/runtime.ts`

Expected: every one uses `X-NanoFaaS-Function-Status` with value `true`, and `X-NanoFaaS-Encoding`
with the handler's encoding value, differing only in header-name casing (HTTP header names are
case-insensitive, and Node lower-cases them on the wire regardless). Record the four exact literals
you found in your report. If any differs in more than casing, stop and report it — that is a real
cross-language break, not a style difference.

- [ ] **Step 5: Commit**

```bash
git add sdks/go/nanofaas/wire_parity_test.go sdks/javascript/test/wire-parity.test.ts
git commit -m "test: pin the cross-SDK wire contract for Go and JavaScript"
```

---

### Final verification

- [ ] **Step 1: Both new SDKs**

Run: `cd sdks/go && go test ./... && go vet ./...`
Expected: PASS, no vet output.

Run: `cd sdks/javascript && npm test && npx tsc --noEmit -p tsconfig.json`
Expected: PASS, no type errors.

- [ ] **Step 2: Nothing else regressed**

Run: `./gradlew test --no-parallel --continue`
Expected: only `:nanofaas-cli RootCommandTest.versionComesFromTheBuild` fails — a known
pre-existing, unrelated failure. This plan touches no Java, so anything else is a surprise worth
investigating.

Run: `cd sdks/python && uv run pytest tests/ -q`
Expected: PASS.

- [ ] **Step 3: The out-of-scope example functions still pass**

Run: `./functions/contract-tests/run.sh`
Expected: exit 0. This plan converts no example function — the Go and JavaScript `roman-numeral`
implementations still return plain values with an implicit 200, which the shared fixture tolerates.
Converting them is the separate example-function plan.

## Self-Review Notes

- **Spec coverage:** issue #193's workstream 1 (JS and Go bindings) is fully covered — envelope type
  (Tasks 1, 3), runtime honouring it (Tasks 2, 4), status validation, header allow-list, both marker
  headers, callback camelCase keys, backward compatibility, and cross-SDK parity (Task 5).
- **The nominal-detection decision was made deliberately.** TypeScript erases types at runtime, so a
  structural check would promote any plain object carrying a `statusCode` key. An exported class
  checked with `instanceof` is the only option that matches Java's and Python's semantics exactly.
  Go needs no such decision — its struct types are already nominal.
- **Go and JavaScript differ on one testable property, on purpose.** Go map iteration order is
  undefined, so first-occurrence-wins dedupe is not deterministically assertable there; JavaScript
  object key order is specified, so it is. Task 1 says not to write the flaky Go test, and Task 3
  requires the JS one. Both still guarantee at most one entry survives.
- **`*int` for Go's `StatusCode`** is not incidental: a plain `int` cannot distinguish "no opinion"
  from zero, and the control plane reads the field as a nullable `Integer`.
- **Task 5 exists because the milestone-1 Critical was a wire-level gap** — `encoding` had no channel
  on one hop — found only at the final whole-branch review. Four implementations of one contract need
  a test that names the literals.
