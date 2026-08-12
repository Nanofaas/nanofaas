# Handler Response Envelope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let function handlers (Java, Python) control the HTTP status code and a
safe set of response headers of their response, read arbitrary request headers, and
mark output/input as base64-encoded binary — end to end, from the handler through the
runtime SDK through the control plane back to the caller of `:invoke`.

**Architecture:** One shared, language-agnostic envelope (`HandlerResponse`) that a
handler may optionally return instead of a plain value; detected structurally
(`instanceof` in Java, `isinstance` in Python) so existing handlers are unaffected.
The signal that a response was function-decided (not a platform error) propagates two
ways depending on the transport: as a dedicated marker HTTP header
(`X-NanoFaaS-Function-Status: true`) on **every** synchronous HTTP hop that can carry a
non-2xx function status — the runtime's `/invoke` response (read by
`ExternalDispatcher`) and the control plane's `:invoke` response (read by
`DefaultOffloadGateway`) — and as a nullable JSON field on the async callback body
(`CallbackPayload`/`InvocationResult`) — null means "no opinion," non-null means "the
function decided this."

**Request headers travel in the body, not on the wire.** `ExternalDispatcher` forwards
only four fixed headers to the runtime (`X-Execution-Id`, `X-Dispatch-Attempt`,
`X-Trace-Id`, `Idempotency-Key`), so a runtime reading its own inbound HTTP headers
would see the control plane's headers, never the caller's. The caller's headers are
therefore captured once at the control-plane ingress into `InvocationRequest.headers`,
which already rides in the JSON body via `request.bodyValue(task.request())` and so
reaches every dispatch mode (EXTERNAL, DEPLOYMENT, LOCAL) unchanged. The runtime SDKs
read `request.headers()` out of that body and add no header-capture code of their own.

**Tech Stack:** Java 25 / Spring Boot 4.1 (WebFlux control-plane, WebMVC SDK runtime),
Python 3.11+ / FastAPI, JUnit 5, pytest.

## Global Constraints

- Status codes a handler may set: integers in `[200, 599]`. Outside that range (or
  non-integer) is a platform error (`OUTPUT_SERIALIZATION_ERROR`-style handling), not
  passed through.
- Response headers a handler may set: exactly `Content-Type`, `Location`,
  `Cache-Control`, `ETag`, `Content-Disposition`, `Content-Language`, `Retry-After`,
  `Vary`. Anything else is silently dropped with a warning log — never fails the
  invocation.
- Control headers (`X-Execution-Id`, `X-Cold-Start`, `X-Init-Duration-Ms`,
  `X-NanoFaaS-Offload-Hop`, `X-NanoFaaS-Function-Status`, `X-Trace-Id`,
  `X-Dispatch-Attempt`, `X-NanoFaaS-Offloaded`, `X-Queue-Reject-Reason`) can never be
  set by a handler, regardless of the allow-list.
- Request headers exposed to the handler: all incoming headers except hop-by-hop
  (`Connection`, `Transfer-Encoding`, `Keep-Alive`, `Host`, `Content-Length`) and every
  header the control plane already binds to a dedicated parameter (`X-Execution-Id`,
  `X-Trace-Id`, `X-Dispatch-Attempt`, `X-Timeout-Ms`, `X-NanoFaaS-Offload-Hop`,
  `Idempotency-Key`, `traceparent`, `tracestate`).
- Request header **keys are lower-cased** before reaching the handler, in both
  languages. HTTP header names are case-insensitive on the wire, Spring preserves the
  sender's casing and Starlette lower-cases — without normalization the same handler
  logic would need `Authorization` in Java and `authorization` in Python.
- Request headers are captured **only** by the control plane (`InvocationController`,
  both `:invoke` and `:enqueue`), which **overwrites** whatever `headers` the caller put
  in the JSON body. A caller must not be able to forge a header it did not actually
  send. The runtime SDKs never read their own inbound HTTP headers for this purpose.
- `encoding` field: only legal value is `"base64"` (or absent/null). The platform is a
  pure carrier for this marker — it never encodes or decodes; the caller of `:invoke`
  does. It must therefore be threaded through *every* model that can reach the caller
  (`ExecutionStatus` and `ExecutionRecord.Snapshot` included), or it is silently lost on
  the terminal/replay path.
- Response body shape: on the runtime's `/invoke`, an envelope response's body is
  `output` **verbatim** in both languages (Python does not apply its usual
  `{"result": ...}` wrapping in the envelope path — Java never did). On the control
  plane's `:invoke`, the body stays the `InvocationResponse` JSON envelope exactly as
  today; consequently `Content-Type` is *not* re-emitted as a real HTTP header at that
  hop (it would describe a body that is not there) — it stays readable in
  `response.headers()`.
- Retry semantics: a function-decided result is `success()`, so it is never retried,
  even when its status is 5xx. Only platform errors retry. This is intentional — the
  function answered, the platform did not fail.
- Backward compatibility is mandatory: a handler returning a plain value must behave
  exactly as it does today (implicit 200, no extra headers, no encoding) — every task
  below includes a regression test proving this.
- Every record that is Jackson-(de)serialized from a request/response body
  (`InvocationRequest`, `InvocationResult`, `InvocationResponse`, `CallbackPayload`)
  gains a **round-trip serde test**, not just accessor tests. Adding a secondary
  constructor to a record is the one change here that can break at runtime while every
  accessor unit test stays green.
- This milestone covers Java + Python only. Do not touch `sdks/javascript`,
  `sdks/go`, `runtimes/watchdog`, or `tools/fn-init` — out of scope (see spec's
  "Future milestone" section). Note this means the shared `roman-numeral` contract
  fixture ends up with 7 implementations of which only 2 return an envelope; Task 15
  states exactly what that does and does not prove.

---

### Task 1: `HandlerResponse` record and response header allow-list (platform/common)

**Files:**
- Create: `platform/common/src/main/java/it/unimib/datai/nanofaas/common/runtime/HandlerResponse.java`
- Create: `platform/common/src/main/java/it/unimib/datai/nanofaas/common/runtime/ResponseHeaderPolicy.java`
- Test: `platform/common/src/test/java/it/unimib/datai/nanofaas/common/runtime/HandlerResponseTest.java`
- Test: `platform/common/src/test/java/it/unimib/datai/nanofaas/common/runtime/ResponseHeaderPolicyTest.java`

**Interfaces:**
- Produces: `HandlerResponse(Object output, int statusCode, Map<String,String> headers, String encoding)`,
  static factories `HandlerResponse.of(Object output, int statusCode)` and
  `HandlerResponse.of(Object output, int statusCode, Map<String,String> headers)`.
- Produces: `ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS` (`Set<String>`, lower-case
  header names), `ResponseHeaderPolicy.isStatusCodeValid(int)`, and
  `ResponseHeaderPolicy.filterAllowedHeaders(Map<String,String> raw)` returning only
  entries whose key (case-insensitively) is in the allow-list.

- [ ] **Step 1: Write the failing tests**

```java
// HandlerResponseTest.java
package it.unimib.datai.nanofaas.common.runtime;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HandlerResponseTest {
    @Test
    void of_withStatusOnly_hasEmptyHeadersAndNoEncoding() {
        HandlerResponse r = HandlerResponse.of("body", 201);
        assertEquals("body", r.output());
        assertEquals(201, r.statusCode());
        assertTrue(r.headers().isEmpty());
        assertNull(r.encoding());
    }

    @Test
    void of_withHeaders_keepsThemAsGiven() {
        HandlerResponse r = HandlerResponse.of("body", 404, Map.of("Content-Type", "text/plain"));
        assertEquals(Map.of("Content-Type", "text/plain"), r.headers());
    }

    @Test
    void record_nullHeadersAllowed() {
        HandlerResponse r = new HandlerResponse("x", 200, null, null);
        assertNull(r.headers());
    }
}
```

```java
// ResponseHeaderPolicyTest.java
package it.unimib.datai.nanofaas.common.runtime;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ResponseHeaderPolicyTest {
    @Test
    void isStatusCodeValid_rangeBoundaries() {
        assertTrue(ResponseHeaderPolicy.isStatusCodeValid(200));
        assertTrue(ResponseHeaderPolicy.isStatusCodeValid(599));
        assertTrue(ResponseHeaderPolicy.isStatusCodeValid(503));
        assertFalse(ResponseHeaderPolicy.isStatusCodeValid(199));
        assertFalse(ResponseHeaderPolicy.isStatusCodeValid(600));
        assertFalse(ResponseHeaderPolicy.isStatusCodeValid(999));
    }

    @Test
    void filterAllowedHeaders_keepsAllowedDropsEverythingElse() {
        Map<String, String> raw = Map.of(
                "Content-Type", "application/pdf",
                "X-Execution-Id", "spoof-attempt",
                "X-Cold-Start", "true",
                "Location", "/somewhere",
                "X-Random-Custom", "nope");
        Map<String, String> filtered = ResponseHeaderPolicy.filterAllowedHeaders(raw);
        assertEquals(Map.of("Content-Type", "application/pdf", "Location", "/somewhere"), filtered);
    }

    @Test
    void filterAllowedHeaders_isCaseInsensitiveOnKeys() {
        Map<String, String> filtered = ResponseHeaderPolicy.filterAllowedHeaders(Map.of("content-type", "text/plain"));
        assertEquals(Map.of("content-type", "text/plain"), filtered);
    }

    @Test
    void filterAllowedHeaders_nullInputReturnsEmptyMap() {
        assertTrue(ResponseHeaderPolicy.filterAllowedHeaders(null).isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :common:test --tests "*HandlerResponseTest" --tests "*ResponseHeaderPolicyTest"`
Expected: FAIL — `HandlerResponse`/`ResponseHeaderPolicy` do not exist yet.

- [ ] **Step 3: Implement**

```java
// HandlerResponse.java
package it.unimib.datai.nanofaas.common.runtime;

import java.util.Map;

/**
 * Optional response envelope a handler may return instead of a plain value to control
 * the HTTP status code, a safe set of response headers, and whether {@code output} is
 * base64-encoded binary. Its mere presence as the handler's return value is itself the
 * signal that the response was function-decided, not a platform error.
 */
public record HandlerResponse(Object output, int statusCode, Map<String, String> headers, String encoding) {
    public static HandlerResponse of(Object output, int statusCode) {
        return new HandlerResponse(output, statusCode, Map.of(), null);
    }

    public static HandlerResponse of(Object output, int statusCode, Map<String, String> headers) {
        return new HandlerResponse(output, statusCode, headers, null);
    }
}
```

```java
// ResponseHeaderPolicy.java
package it.unimib.datai.nanofaas.common.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ResponseHeaderPolicy {
    public static final Set<String> ALLOWED_RESPONSE_HEADERS = Set.of(
            "content-type", "location", "cache-control", "etag",
            "content-disposition", "content-language", "retry-after", "vary");

    private ResponseHeaderPolicy() {
    }

    public static boolean isStatusCodeValid(int statusCode) {
        return statusCode >= 200 && statusCode <= 599;
    }

    public static Map<String, String> filterAllowedHeaders(Map<String, String> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (ALLOWED_RESPONSE_HEADERS.contains(entry.getKey().toLowerCase())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :common:test --tests "*HandlerResponseTest" --tests "*ResponseHeaderPolicyTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add platform/common/src/main/java/it/unimib/datai/nanofaas/common/runtime/HandlerResponse.java \
        platform/common/src/main/java/it/unimib/datai/nanofaas/common/runtime/ResponseHeaderPolicy.java \
        platform/common/src/test/java/it/unimib/datai/nanofaas/common/runtime/HandlerResponseTest.java \
        platform/common/src/test/java/it/unimib/datai/nanofaas/common/runtime/ResponseHeaderPolicyTest.java
git commit -m "feat: add HandlerResponse envelope and response header allow-list"
```

---

### Task 2: `InvocationRequest` gains a `headers` field

**Files:**
- Modify: `platform/common/src/main/java/it/unimib/datai/nanofaas/common/model/InvocationRequest.java`
- Modify: `platform/common/src/test/java/it/unimib/datai/nanofaas/common/model/CommonModelTest.java`

**Interfaces:**
- Produces: `InvocationRequest(Object input, Map<String,String> metadata, Map<String,String> headers)`.

**Why this field matters:** it is not decoration — it is the *transport* for request
headers (see Architecture). The control plane fills it at ingress (Task 6) and it rides
in the JSON body all the way to the handler.

- [ ] **Step 1: Write the failing test**

Add to `CommonModelTest.java` (near the existing `invocationRequest_*` tests):

```java
    @Test
    void invocationRequest_headersAccessor() {
        InvocationRequest r = new InvocationRequest("payload", Map.of("k", "v"), Map.of("authorization", "Bearer x"));
        assertEquals("Bearer x", r.headers().get("authorization"));
    }

    @Test
    void invocationRequest_nullHeaders() {
        InvocationRequest r = new InvocationRequest("data", null, null);
        assertNull(r.headers());
    }

    @Test
    void invocationRequest_jsonRoundTrip() throws Exception {
        // The record is @RequestBody-deserialized on two hops; adding a secondary
        // constructor must not confuse Jackson's canonical-constructor detection.
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        InvocationRequest original = new InvocationRequest(
                Map.of("number", 42), Map.of("k", "v"), Map.of("authorization", "Bearer x"));
        InvocationRequest back = mapper.readValue(mapper.writeValueAsString(original), InvocationRequest.class);
        assertEquals(original.metadata(), back.metadata());
        assertEquals(original.headers(), back.headers());
    }

    @Test
    void invocationRequest_deserializesLegacyBodyWithoutHeaders() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        InvocationRequest back = mapper.readValue("{\"input\":{\"n\":1}}", InvocationRequest.class);
        assertNull(back.headers());
    }
```

(match the Jackson import style already used elsewhere in this module — the repo is on
Jackson 3 / `tools.jackson`, confirm before writing.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :common:test --tests "*CommonModelTest"`
Expected: FAIL — compile error, `InvocationRequest` has no 3-arg constructor.

This will also break every existing call site that constructs `InvocationRequest` with
2 args (e.g. `RomanNumeralHandlerTest`, `HandlerExecutor` callers). That is expected
and fixed by Step 3 using a compatibility constructor, not by updating every call site.

- [ ] **Step 3: Implement**

```java
package it.unimib.datai.nanofaas.common.model;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record InvocationRequest(
        @NotNull(message = "Input payload is required")
        Object input,
        Map<String, String> metadata,
        Map<String, String> headers
) {
    public InvocationRequest(Object input, Map<String, String> metadata) {
        this(input, metadata, null);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :common:test --tests "*CommonModelTest"`
Expected: PASS. Also run `./gradlew build` once to confirm the 2-arg compatibility
constructor keeps every other existing call site compiling unchanged.

- [ ] **Step 5: Commit**

```bash
git add platform/common/src/main/java/it/unimib/datai/nanofaas/common/model/InvocationRequest.java \
        platform/common/src/test/java/it/unimib/datai/nanofaas/common/model/CommonModelTest.java
git commit -m "feat: add headers field to InvocationRequest"
```

---

### Task 3: `InvocationResult` gains `statusCode`, `headers`, `encoding`

**Files:**
- Modify: `platform/common/src/main/java/it/unimib/datai/nanofaas/common/model/InvocationResult.java`
- Modify: `platform/common/src/test/java/it/unimib/datai/nanofaas/common/model/CommonModelTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `InvocationResult(boolean success, Object output, ErrorInfo error, Integer statusCode, Map<String,String> headers, String encoding)`.
  `InvocationResult.success(Object output)` / `.error(String,String)` keep their exact
  signatures and now delegate with `statusCode=null, headers=null, encoding=null`.
  New: `InvocationResult.successWithEnvelope(Object output, Integer statusCode, Map<String,String> headers, String encoding)`.

- [ ] **Step 1: Write the failing test**

Add to `CommonModelTest.java`:

```java
    @Test
    void invocationResult_success_hasNullEnvelopeFieldsByDefault() {
        InvocationResult r = InvocationResult.success("hello");
        assertNull(r.statusCode());
        assertNull(r.headers());
        assertNull(r.encoding());
    }

    @Test
    void invocationResult_successWithEnvelope_carriesFields() {
        InvocationResult r = InvocationResult.successWithEnvelope("body", 201, Map.of("Location", "/x"), "base64");
        assertTrue(r.success());
        assertEquals("body", r.output());
        assertEquals(201, r.statusCode());
        assertEquals("/x", r.headers().get("Location"));
        assertEquals("base64", r.encoding());
    }

    @Test
    void invocationResult_jsonRoundTrip() {
        // This record is the @RequestBody of /internal/executions/{id}:complete — the
        // async callback wire format. Field names must survive both directions.
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        InvocationResult original = InvocationResult.successWithEnvelope(
                "body", 201, Map.of("Location", "/x"), "base64");
        InvocationResult back = mapper.readValue(mapper.writeValueAsString(original), InvocationResult.class);
        assertEquals(201, back.statusCode());
        assertEquals("/x", back.headers().get("Location"));
        assertEquals("base64", back.encoding());
    }

    @Test
    void invocationResult_deserializesLegacyCallbackBody() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        InvocationResult back = mapper.readValue(
                "{\"success\":true,\"output\":\"ok\",\"error\":null}", InvocationResult.class);
        assertTrue(back.success());
        assertNull(back.statusCode());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :common:test --tests "*CommonModelTest"`
Expected: FAIL — `statusCode()`/`successWithEnvelope` do not exist.

- [ ] **Step 3: Implement**

```java
package it.unimib.datai.nanofaas.common.model;

import java.util.Map;

public record InvocationResult(
        boolean success,
        Object output,
        ErrorInfo error,
        Integer statusCode,
        Map<String, String> headers,
        String encoding
) {
    public InvocationResult(boolean success, Object output, ErrorInfo error) {
        this(success, output, error, null, null, null);
    }

    public static InvocationResult success(Object output) {
        return new InvocationResult(true, output, null);
    }

    public static InvocationResult successWithEnvelope(Object output, Integer statusCode,
                                                         Map<String, String> headers, String encoding) {
        return new InvocationResult(true, output, null, statusCode, headers, encoding);
    }

    public static InvocationResult error(String code, String message) {
        return new InvocationResult(false, null, new ErrorInfo(code, message));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :common:test --tests "*CommonModelTest"`
Expected: PASS. Then `./gradlew build` to confirm the 3-arg compatibility constructor
keeps every existing `new InvocationResult(...)` call site compiling (e.g.
`InvocationResponseMapper.terminalResponse`).

- [ ] **Step 5: Commit**

```bash
git add platform/common/src/main/java/it/unimib/datai/nanofaas/common/model/InvocationResult.java \
        platform/common/src/test/java/it/unimib/datai/nanofaas/common/model/CommonModelTest.java
git commit -m "feat: add statusCode/headers/encoding to InvocationResult"
```

---

### Task 4: `InvocationResponse` and `ExecutionStatus` gain the same fields

**Files:**
- Modify: `platform/common/src/main/java/it/unimib/datai/nanofaas/common/model/InvocationResponse.java`
- Modify: `platform/common/src/main/java/it/unimib/datai/nanofaas/common/model/ExecutionStatus.java`
- Modify: `platform/common/src/test/java/it/unimib/datai/nanofaas/common/model/CommonModelTest.java`

**Interfaces:**
- Produces: `InvocationResponse(String executionId, String status, Object output, ErrorInfo error, Integer statusCode, Map<String,String> headers, String encoding)`.
- Produces: `ExecutionStatus(String executionId, String status, Instant startedAt, Instant finishedAt, Object output, ErrorInfo error, boolean coldStart, Long initDurationMs, Integer statusCode, Map<String,String> headers, String encoding)`.

**Both records get a compatibility constructor.** `ExecutionStatus` has two existing
call sites besides the mapper — `CommonModelTest.java:74` and
`InvocationControllerTest.java:262` — and its production call site
(`InvocationResponseMapper.toStatus`) is owned by Task 9. Without a compat constructor
`:control-plane` stops compiling at the end of *this* task and does not compile again
until Task 9 lands, which makes Tasks 5–8 unverifiable (Task 8 in particular declares
`./gradlew :control-plane:test → PASS`, which would be impossible). The compat
constructor keeps every intermediate commit green.

`encoding` is on `ExecutionStatus` too: it is a caller-visible marker, and
`GET /v1/executions/{id}` is a path where the caller reads the output back. Dropping it
here is what would make it silently vanish on the replay path.

- [ ] **Step 1: Write the failing test**

Update the existing tests in `CommonModelTest.java` (replace, don't duplicate):

```java
    @Test
    void invocationResponse_recordAccessors() {
        ErrorInfo err = new ErrorInfo("ERR", "detail");
        InvocationResponse resp = new InvocationResponse("ex-1", "FAILED", null, err, null, null, null);
        assertEquals("ex-1", resp.executionId());
        assertEquals("FAILED", resp.status());
        assertNull(resp.output());
        assertEquals(err, resp.error());
        assertNull(resp.statusCode());
    }

    @Test
    void invocationResponse_carriesEnvelopeFields() {
        InvocationResponse resp = new InvocationResponse(
                "ex-2", "success", "body", null, 201, Map.of("Location", "/x"), null);
        assertEquals(201, resp.statusCode());
        assertEquals("/x", resp.headers().get("Location"));
    }

    @Test
    void invocationResponse_jsonRoundTrip() {
        // DefaultOffloadGateway does bodyToMono(InvocationResponse.class) on the remote's
        // :invoke body — the envelope fields must survive that hop (Task 10b depends on it).
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        InvocationResponse original = new InvocationResponse(
                "ex-3", "success", "body", null, 404, Map.of("Content-Type", "text/plain"), "base64");
        InvocationResponse back = mapper.readValue(mapper.writeValueAsString(original), InvocationResponse.class);
        assertEquals(404, back.statusCode());
        assertEquals("text/plain", back.headers().get("Content-Type"));
        assertEquals("base64", back.encoding());
    }
```

```java
    @Test
    void executionStatus_recordAccessors() {
        ExecutionStatus s = new ExecutionStatus(
                "ex-1", "COMPLETED", Instant.ofEpochMilli(100), Instant.ofEpochMilli(200),
                "result", null, true, 150L, null, null, null);
        assertEquals("ex-1", s.executionId());
        assertEquals("COMPLETED", s.status());
        assertEquals(Instant.ofEpochMilli(100), s.startedAt());
        assertEquals(Instant.ofEpochMilli(200), s.finishedAt());
        assertEquals("result", s.output());
        assertNull(s.error());
        assertTrue(s.coldStart());
        assertEquals(150L, s.initDurationMs());
        assertNull(s.statusCode());
    }

    @Test
    void executionStatus_carriesEnvelopeFields() {
        ExecutionStatus s = new ExecutionStatus(
                "ex-2", "success", Instant.now(), Instant.now(),
                "body", null, false, null, 404, Map.of("Content-Type", "text/plain"), "base64");
        assertEquals(404, s.statusCode());
        assertEquals("text/plain", s.headers().get("Content-Type"));
        assertEquals("base64", s.encoding());
    }

    @Test
    void executionStatus_compatConstructorLeavesEnvelopeFieldsNull() {
        ExecutionStatus s = new ExecutionStatus(
                "ex-3", "queued", null, null, null, null, false, null);
        assertNull(s.statusCode());
        assertNull(s.headers());
        assertNull(s.encoding());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :common:test --tests "*CommonModelTest"`
Expected: FAIL — both records still have the old, shorter constructor.

- [ ] **Step 3: Implement**

```java
// InvocationResponse.java
package it.unimib.datai.nanofaas.common.model;

import java.util.Map;

public record InvocationResponse(
        String executionId,
        String status,
        Object output,
        ErrorInfo error,
        Integer statusCode,
        Map<String, String> headers,
        String encoding
) {
    public InvocationResponse(String executionId, String status, Object output, ErrorInfo error) {
        this(executionId, status, output, error, null, null, null);
    }
}
```

```java
// ExecutionStatus.java
package it.unimib.datai.nanofaas.common.model;

import java.time.Instant;
import java.util.Map;

public record ExecutionStatus(
        String executionId,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Object output,
        ErrorInfo error,
        boolean coldStart,
        Long initDurationMs,
        Integer statusCode,
        Map<String, String> headers,
        String encoding
) {
    public ExecutionStatus(String executionId, String status, Instant startedAt, Instant finishedAt,
                           Object output, ErrorInfo error, boolean coldStart, Long initDurationMs) {
        this(executionId, status, startedAt, finishedAt, output, error, coldStart, initDurationMs,
                null, null, null);
    }
}
```

Both records keep a compatibility constructor so that no intermediate commit breaks the
build: `InvocationResponse`'s 4-arg form covers `InvocationService.invokeAsync` and
`InvocationResponseMapper.timeoutResponse`; `ExecutionStatus`'s 8-arg form covers
`InvocationResponseMapper.toStatus` (rewritten in Task 9) plus the two existing test
call sites at `CommonModelTest.java:74` and `InvocationControllerTest.java:262`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :common:test --tests "*CommonModelTest"`
Expected: PASS.
Then run `./gradlew build` — it must also pass. The compat constructors mean this task
leaves the whole repository compiling; if `build` fails here, a call site was missed and
must be fixed **in this task**, not deferred.

- [ ] **Step 5: Commit**

```bash
git add platform/common/src/main/java/it/unimib/datai/nanofaas/common/model/InvocationResponse.java \
        platform/common/src/main/java/it/unimib/datai/nanofaas/common/model/ExecutionStatus.java \
        platform/common/src/test/java/it/unimib/datai/nanofaas/common/model/CommonModelTest.java
git commit -m "feat: add statusCode/headers/encoding to InvocationResponse and ExecutionStatus"
```

---

### Task 5: `CallbackPayload` gains `statusCode`, `headers`, `encoding` (sdks/java)

**Files:**
- Modify: `sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackPayload.java`
- Test: `sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackPayloadTest.java` (create)

**Interfaces:**
- Produces: `CallbackPayload(boolean success, JsonNode output, ErrorInfo error, Integer statusCode, Map<String,String> headers, String encoding)`.
  `CallbackPayload.success(JsonNode output)` unchanged signature, now delegates with
  nulls. New: `CallbackPayload.successWithEnvelope(JsonNode output, Integer statusCode, Map<String,String> headers, String encoding)`.

- [ ] **Step 1: Write the failing test**

```java
package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.TextNode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CallbackPayloadTest {
    @Test
    void success_hasNullEnvelopeFieldsByDefault() {
        CallbackPayload p = CallbackPayload.success(new TextNode("ok"));
        assertNull(p.statusCode());
        assertNull(p.headers());
        assertNull(p.encoding());
    }

    @Test
    void successWithEnvelope_carriesFields() {
        CallbackPayload p = CallbackPayload.successWithEnvelope(
                new TextNode("ok"), 201, Map.of("Location", "/x"), "base64");
        assertTrue(p.success());
        assertEquals(201, p.statusCode());
        assertEquals("/x", p.headers().get("Location"));
        assertEquals("base64", p.encoding());
    }

    @Test
    void serializedFieldNamesMatchInvocationResult() {
        // CallbackPayload is serialized here and deserialized as InvocationResult on the
        // control plane (/internal/executions/{id}:complete). The two records are never
        // checked against each other by the compiler — only this test catches a rename.
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        String json = mapper.writeValueAsString(CallbackPayload.successWithEnvelope(
                new TextNode("ok"), 201, Map.of("Location", "/x"), "base64"));
        var received = mapper.readValue(json,
                it.unimib.datai.nanofaas.common.model.InvocationResult.class);
        assertTrue(received.success());
        assertEquals(201, received.statusCode());
        assertEquals("/x", received.headers().get("Location"));
        assertEquals("base64", received.encoding());
    }
}
```

That last test is the one that matters: it is the only thing tying the Java callback
*sender* to the control-plane *receiver*, and it also pins the exact JSON key names
(`statusCode`, `headers`, `encoding`) that the Python runtime must emit in Task 13.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdks:java:test --tests "*CallbackPayloadTest"`
Expected: FAIL — no such constructor/factory.

- [ ] **Step 3: Implement**

```java
package it.unimib.datai.nanofaas.sdk.runtime;

import tools.jackson.databind.JsonNode;
import it.unimib.datai.nanofaas.common.model.ErrorInfo;

import java.util.Map;

public record CallbackPayload(
        boolean success,
        JsonNode output,
        ErrorInfo error,
        Integer statusCode,
        Map<String, String> headers,
        String encoding
) {
    public static CallbackPayload success(JsonNode output) {
        return new CallbackPayload(true, output, null, null, null, null);
    }

    public static CallbackPayload successWithEnvelope(JsonNode output, Integer statusCode,
                                                        Map<String, String> headers, String encoding) {
        return new CallbackPayload(true, output, null, statusCode, headers, encoding);
    }

    public static CallbackPayload error(String code, String message) {
        return new CallbackPayload(false, null, new ErrorInfo(code, message), null, null, null);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sdks:java:test --tests "*CallbackPayloadTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackPayload.java \
        sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/CallbackPayloadTest.java
git commit -m "feat: add statusCode/headers/encoding to CallbackPayload"
```

---

### Task 6: The control plane captures caller request headers into `InvocationRequest`

**Files:**
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/InvocationController.java`
- Test: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/InvocationControllerTest.java` (existing — add cases; read it first to match its mocking style)

**Interfaces:**
- Consumes: `InvocationRequest` now has 3 args (Task 2).
- Produces: the `InvocationRequest` handed to `invocationService` on both `:invoke` and
  `:enqueue` has `headers()` populated from the caller's real HTTP headers, filtered and
  lower-cased, **overwriting** anything the caller put in the body's `headers` field.

**Why this is a control-plane task and not an SDK task.** The original version of this
plan captured headers in the runtime's `InvokeController`. That does not work:
`ExternalDispatcher` sends the runtime only `X-Execution-Id`, `X-Dispatch-Attempt`,
`X-Trace-Id` and `Idempotency-Key`, so the runtime's own inbound headers are the control
plane's, never the caller's. A handler asking for `authorization` would get nothing —
and the SDK unit tests, which POST directly at `/invoke`, would still pass, hiding it.
Capturing at ingress and letting the value ride in the body reaches every dispatch mode
(EXTERNAL, DEPLOYMENT, LOCAL) for free, and needs **zero** changes in either runtime SDK
for the Java path: `handlerExecutor.execute(handler, request)` already receives a
deserialized `InvocationRequest` whose `headers()` is now populated.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void invokeSync_capturesCallerHeadersIntoRequest() {
        // Arrange: ArgumentCaptor<InvocationRequest> on invocationService.invokeSyncReactive(...)
        // Act: POST :invoke with headers Authorization: Bearer x, Connection: keep-alive,
        //      X-Trace-Id: t-1, Idempotency-Key: k-1
        // Assert: captured.headers().get("authorization") == "Bearer x"
        // Assert: captured.headers() has no "connection", no "x-trace-id",
        //         no "idempotency-key" (all hop-by-hop or already-dedicated)
    }

    @Test
    void invokeSync_callerCannotForgeHeadersViaBody() {
        // Act: POST :invoke with body {"input":{}, "headers":{"authorization":"forged"}}
        //      and NO Authorization HTTP header
        // Assert: captured.headers() has no "authorization" — the body value is discarded,
        //         not merged.
    }

    @Test
    void invokeAsync_capturesCallerHeadersIntoRequest() {
        // Same as the first test but through :enqueue — async handlers see headers too.
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :control-plane:test --tests "*InvocationControllerTest"`
Expected: FAIL — headers are not captured yet.

- [ ] **Step 3: Implement**

```java
    /**
     * Hop-by-hop headers plus every header the control plane already binds to a dedicated
     * parameter. Anything left is handler-visible.
     */
    private static final Set<String> EXCLUDED_REQUEST_HEADERS = Set.of(
            "connection", "transfer-encoding", "keep-alive", "host", "content-length",
            "x-execution-id", "x-trace-id", "x-dispatch-attempt", "x-timeout-ms",
            "x-nanofaas-offload-hop", "idempotency-key", "traceparent", "tracestate");

    /**
     * Rebuild the request with the caller's real headers, lower-cased. The body's own
     * {@code headers} field is overwritten, never merged: a caller must not be able to
     * forge a header it did not send.
     */
    private static InvocationRequest withCallerHeaders(InvocationRequest request,
                                                       Map<String, String> rawHeaders) {
        Map<String, String> filtered = new LinkedHashMap<>();
        rawHeaders.forEach((name, value) -> {
            String key = name.toLowerCase(java.util.Locale.ROOT);
            if (!EXCLUDED_REQUEST_HEADERS.contains(key)) {
                filtered.put(key, value);
            }
        });
        return new InvocationRequest(request.input(), request.metadata(), filtered);
    }
```

Then add `@RequestHeader Map<String, String> allHeaders` as the last parameter of both
`invokeSync` and `invokeAsync`, and pass `withCallerHeaders(request, allHeaders)` to
`invocationService` instead of `request` in each. Everything else in both methods is
untouched.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :control-plane:test --tests "*InvocationControllerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/InvocationController.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/InvocationControllerTest.java
git commit -m "feat: capture caller request headers into InvocationRequest at ingress"
```

---

### Task 7: `InvokeController` detects `HandlerResponse` and applies status/headers/marker

**Files:**
- Modify: `sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeController.java`
- Test: `sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeControllerTest.java`

**Interfaces:**
- Consumes: `HandlerResponse`, `ResponseHeaderPolicy` (Task 1), `CallbackPayload.successWithEnvelope` (Task 5).
- Produces: when the handler returns a `HandlerResponse`, the HTTP response uses that
  status, allow-listed headers, and adds `X-NanoFaaS-Function-Status: true`; the
  callback uses `CallbackPayload.successWithEnvelope`. Otherwise, identical to today
  (200, cold-start headers only, `CallbackPayload.success`).
- The response body is `envelope.output()` normalized, **verbatim** — no wrapper object.

**Note:** this is the only task that touches `InvokeController`. Request headers are
handled entirely by the control plane (Task 6) and arrive already populated in the
deserialized `InvocationRequest`, so there is nothing to add here for them.

- [ ] **Step 1: Write the failing tests**

Add to `InvokeControllerTest.java`:

```java
    @Test
    void invoke_handlerReturnsHandlerResponse_usesItsStatusAndHeaders() {
        // handler.handle(...) returns HandlerResponse.of(Map.of("error", "not found"), 404,
        //   Map.of("Content-Type", "application/json"));
        // Assert: response.getStatusCode().value() == 404
        // Assert: response.getHeaders().getFirst("Content-Type") == "application/json"
        // Assert: response.getHeaders().getFirst("X-NanoFaaS-Function-Status") == "true"
    }

    @Test
    void invoke_handlerReturnsHandlerResponse_dropsDisallowedHeaders() {
        // HandlerResponse.of(body, 200, Map.of("X-Execution-Id", "spoof", "X-Custom", "nope"))
        // Assert: response has neither header set (X-Execution-Id keeps its real value,
        // X-Custom is simply absent).
    }

    @Test
    void invoke_handlerReturnsHandlerResponse_invalidStatusCodeFallsBackToPlatformError() {
        // HandlerResponse.of(body, 999)
        // Assert: response.getStatusCode().value() == 500
        // Assert: no X-NanoFaaS-Function-Status header
    }

    @Test
    void invoke_handlerReturnsPlainValue_behavesExactlyAsToday() {
        // handler.handle(...) returns Map.of("roman", "XLII") (no envelope)
        // Assert: response.getStatusCode().value() == 200
        // Assert: no X-NanoFaaS-Function-Status header
    }

    @Test
    void invoke_passesCallerHeadersFromBodyToHandler() {
        // Regression guard for Task 6's transport choice: POST /invoke with a body whose
        // InvocationRequest carries headers {"authorization": "Bearer x"} and NO such HTTP
        // header. Assert the InvocationRequest reaching handlerExecutor.execute still has
        // headers().get("authorization") == "Bearer x" — i.e. the runtime reads the body,
        // not its own inbound HTTP headers.
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sdks:java:test --tests "*InvokeControllerTest"`
Expected: FAIL — envelope detection not implemented yet.

- [ ] **Step 3: Implement**

Replace the inline success-path body inside `invoke` (the block that currently builds
`ResponseEntity.ok()` after `handlerExecutor.execute(...)`) with a call to a new
private method, and implement it:

```java
    private ResponseEntity<Object> buildSuccessResponse(Object rawOutput, String executionId,
                                                          InvocationRuntimeContext runtimeContext,
                                                          String dispatchAttempt, boolean isColdStart) {
        int statusCode = 200;
        Map<String, String> allowedHeaders = Map.of();
        String encoding = null;
        Object outputForSerialization = rawOutput;
        boolean isEnvelope = false;

        if (rawOutput instanceof HandlerResponse envelope) {
            if (ResponseHeaderPolicy.isStatusCodeValid(envelope.statusCode())) {
                statusCode = envelope.statusCode();
                allowedHeaders = ResponseHeaderPolicy.filterAllowedHeaders(envelope.headers());
                encoding = envelope.encoding();
                outputForSerialization = envelope.output();
                isEnvelope = true;
            } else {
                log.warn("Handler returned invalid statusCode {} for execution {}, treating as platform error",
                        envelope.statusCode(), executionId);
                callbackDispatcher.submit(executionId,
                        CallbackPayload.error("OUTPUT_SERIALIZATION_ERROR",
                                "Handler returned invalid statusCode: " + envelope.statusCode()),
                        runtimeContext.traceId(), dispatchAttempt);
                return ResponseEntity.status(500)
                        .body(Map.of(ERROR_KEY, "Handler returned invalid statusCode: " + envelope.statusCode()));
            }
        }

        JsonNode output = outputNormalizer.toJsonNode(outputForSerialization);

        callbackDispatcher.submit(
                executionId,
                isEnvelope
                        ? CallbackPayload.successWithEnvelope(output, statusCode, allowedHeaders, encoding)
                        : CallbackPayload.success(output),
                runtimeContext.traceId(),
                dispatchAttempt);

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.status(statusCode);
        allowedHeaders.forEach(responseBuilder::header);
        if (isEnvelope) {
            responseBuilder.header("X-NanoFaaS-Function-Status", "true");
        }
        if (isColdStart) {
            responseBuilder.header("X-Cold-Start", "true");
            responseBuilder.header("X-Init-Duration-Ms", String.valueOf(coldStartTracker.initDurationMs()));
        }
        return responseBuilder.body(output);
    }
```

`invoke`'s try block becomes:

```java
        try {
            FunctionHandler handler = handlerRegistry.resolve();
            Object rawOutput = handlerExecutor.execute(handler, request);
            return buildSuccessResponse(rawOutput, effectiveExecutionId, runtimeContext, dispatchAttempt, isColdStart);
        } catch (OutputSerializationException ex) {
```

(the `OutputSerializationException`/`TimeoutException`/`InterruptedException`/generic
`catch` blocks below stay exactly as they are today — they are the unchanged
platform-error path).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdks:java:test --tests "*InvokeControllerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeController.java \
        sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeControllerTest.java
git commit -m "feat: detect HandlerResponse envelope in InvokeController"
```

---

### Task 8: `ExternalDispatcher` reads the marker header and propagates the envelope

**Files:**
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcher.java`
- Test: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcherTest.java` (existing — add cases; read it first to match its WebClient-mocking style before writing)

**Interfaces:**
- Consumes: `InvocationResult.successWithEnvelope` (Task 3).
- Produces: when the remote response carries `X-NanoFaaS-Function-Status: true`, the
  resulting `InvocationResult` is `success(true)` with `statusCode` set to the actual
  remote HTTP status (even if non-2xx) and `headers` filtered by
  `ResponseHeaderPolicy.filterAllowedHeaders`. Otherwise, behavior is byte-for-byte
  identical to today (2xx → success, non-2xx → `EXTERNAL_ERROR`).

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void dispatch_functionStatusMarkerPresent_treatsNon2xxAsSuccess() {
        // Remote responds 404 with header X-NanoFaaS-Function-Status: true and body {"error":"not found"}.
        // Assert: result.success() == true
        // Assert: result.statusCode() == 404
    }

    @Test
    void dispatch_functionStatusMarkerPresent_propagatesAllowedHeaders() {
        // Remote responds 200 with X-NanoFaaS-Function-Status: true and Content-Type: application/pdf.
        // Assert: result.headers().get("Content-Type") == "application/pdf"
    }

    @Test
    void dispatch_noFunctionStatusMarker_non2xxIsStillExternalError() {
        // Remote responds 500 with no marker header (today's platform-error case).
        // Assert: result.success() == false, error code EXTERNAL_ERROR — unchanged.
    }

    @Test
    void dispatch_functionStatusMarkerWithTextPlain_decodesBodyAsString() {
        // Remote responds 200, X-NanoFaaS-Function-Status: true, Content-Type: text/plain,
        // body "hello".
        // Assert: result.success() == true and result.output() == "hello"
        // (NOT an EXTERNAL_ERROR from a failed Object decode — see Step 3's note).
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :control-plane:test --tests "*ExternalDispatcherTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Replace the `exchangeToMono` body:

```java
                .exchangeToMono(response -> {
                    boolean isCold = "true".equalsIgnoreCase(
                            response.headers().asHttpHeaders().getFirst("X-Cold-Start"));
                    Long initMs = parseInitDuration(
                            response.headers().asHttpHeaders().getFirst("X-Init-Duration-Ms"));
                    boolean isFunctionDecided = "true".equalsIgnoreCase(
                            response.headers().asHttpHeaders().getFirst("X-NanoFaaS-Function-Status"));

                    if (isFunctionDecided) {
                        int statusCode = response.statusCode().value();
                        Map<String, String> headers = extractAllowedHeaders(response.headers().asHttpHeaders());
                        return decodeBody(response)
                                .map(body -> new DispatchResult(
                                        InvocationResult.successWithEnvelope(body, statusCode, headers, null),
                                        isCold, initMs))
                                .defaultIfEmpty(new DispatchResult(
                                        InvocationResult.successWithEnvelope(null, statusCode, headers, null),
                                        isCold, initMs));
                    }

                    if (response.statusCode().is2xxSuccessful()) {
                        return decodeBody(response)
                                .map(body -> new DispatchResult(InvocationResult.success(body), isCold, initMs))
                                .defaultIfEmpty(new DispatchResult(InvocationResult.success(null), isCold, initMs));
                    }
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty(response.statusCode().toString())
                            .map(msg -> new DispatchResult(InvocationResult.error("EXTERNAL_ERROR", msg), isCold, initMs));
                })
```

**The content-type branch must be shared, not duplicated.** The existing 2xx path
special-cases `text/plain` on purpose: `bodyToMono(Object.class)` has no decoder for
`text/plain` and fails, which `onErrorResume` then turns into `EXTERNAL_ERROR`. Since
setting `Content-Type: text/plain` is one of the main things the envelope exists for,
the marker branch would break exactly the case it was added to support. Extract the
existing logic into one helper and call it from both branches:

```java
    private static reactor.core.publisher.Mono<Object> decodeBody(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        MediaType contentType = response.headers().contentType().orElse(MediaType.APPLICATION_JSON);
        if (MediaType.TEXT_PLAIN.isCompatibleWith(contentType)) {
            return response.bodyToMono(String.class).cast(Object.class);
        }
        return response.bodyToMono(Object.class);
    }
```

Add the helper and the import (`it.unimib.datai.nanofaas.common.runtime.ResponseHeaderPolicy`, `org.springframework.http.HttpHeaders`, `java.util.Map`):

```java
    private static Map<String, String> extractAllowedHeaders(org.springframework.http.HttpHeaders headers) {
        Map<String, String> raw = new java.util.LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (!values.isEmpty()) {
                raw.put(name, values.get(0));
            }
        });
        return ResponseHeaderPolicy.filterAllowedHeaders(raw);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :control-plane:test --tests "*ExternalDispatcherTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcher.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcherTest.java
git commit -m "feat: propagate function-decided status/headers in ExternalDispatcher"
```

---

### Task 9: `ExecutionRecord` stores statusCode/headers/encoding; `InvocationResponseMapper` propagates them

**Files:**
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionRecord.java`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationResponseMapper.java`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java`
- Test: `platform/control-plane/src/test/java/.../execution/ExecutionRecordTest.java` (existing — add cases; read first)
- Test: `platform/control-plane/src/test/java/.../service/InvocationResponseMapperTest.java` (existing — add cases; read first)

**Interfaces:**
- Consumes: `InvocationResult` (Task 3), `ExecutionStatus`/`InvocationResponse` (Task 4).
- Produces: `ExecutionRecord.markSuccess(Object output, Integer statusCode, Map<String,String> headers, String encoding)`
  (new overload; existing 1-arg `markSuccess(Object)` delegates with nulls).
  `ExecutionRecord.Snapshot` gains `statusCode`, `headers`, `encoding`.
  `InvocationResponseMapper.toResponse/.terminalResponse/.toStatus` all propagate the
  new fields end to end.

`encoding` is carried here too. It is the field the caller uses to know the output is
base64 binary, and `GET /v1/executions/{id}` plus `terminalResponse` are precisely the
paths where a caller reads a completed result back. Storing status and headers but not
encoding would make the marker vanish on exactly those paths.

- [ ] **Step 1: Write the failing tests**

Add to `ExecutionRecordTest.java`:

```java
    @Test
    void markSuccess_withStatusCodeAndHeaders_reflectedInSnapshot() {
        ExecutionRecord record = new ExecutionRecord("ex-1", someTask());
        record.markSuccess("body", 201, Map.of("Location", "/x"), "base64");
        ExecutionRecord.Snapshot snapshot = record.snapshot();
        assertEquals(201, snapshot.statusCode());
        assertEquals("/x", snapshot.headers().get("Location"));
        assertEquals("base64", snapshot.encoding());
    }

    @Test
    void markSuccess_withoutStatusCode_snapshotHasNullEnvelopeFields() {
        ExecutionRecord record = new ExecutionRecord("ex-2", someTask());
        record.markSuccess("body");
        ExecutionRecord.Snapshot snapshot = record.snapshot();
        assertNull(snapshot.statusCode());
        assertNull(snapshot.headers());
        assertNull(snapshot.encoding());
    }

    @Test
    void resetForRetry_clearsEnvelopeFields() {
        ExecutionRecord record = new ExecutionRecord("ex-3", someTask());
        record.markSuccess("body", 404, Map.of("Location", "/x"), "base64");
        record.resetForRetry();
        ExecutionRecord.Snapshot snapshot = record.snapshot();
        assertNull(snapshot.statusCode());
        assertNull(snapshot.headers());
        assertNull(snapshot.encoding());
    }
```

(use whatever existing `someTask()`/`InvocationTask` construction helper the test file
already has — read the file first).

Add to `InvocationResponseMapperTest.java`:

```java
    @Test
    void toResponse_propagatesStatusCodeAndHeadersFromResult() {
        InvocationResult result = InvocationResult.successWithEnvelope(
                "body", 404, Map.of("Content-Type", "application/json"), null);
        InvocationResponse response = mapper.toResponse(executionRecord, result);
        assertEquals(404, response.statusCode());
        assertEquals("application/json", response.headers().get("Content-Type"));
    }

    @Test
    void toStatus_propagatesStatusCodeAndHeadersFromSnapshot() {
        executionRecord.markSuccess("body", 201, Map.of("Location", "/x"), "base64");
        ExecutionStatus status = mapper.toStatus(executionRecord);
        assertEquals(201, status.statusCode());
        assertEquals("/x", status.headers().get("Location"));
        assertEquals("base64", status.encoding());
    }

    @Test
    void terminalResponse_propagatesEncoding() {
        // Guards the field that is easiest to drop: terminalResponse rebuilds an
        // InvocationResult from the snapshot, so encoding must come from the snapshot,
        // not be hardcoded null.
        executionRecord.markSuccess("body", 200, Map.of(), "base64");
        InvocationResponse response = mapper.terminalResponse(executionRecord);
        assertEquals("base64", response.encoding());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :control-plane:test --tests "*ExecutionRecordTest" --tests "*InvocationResponseMapperTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`ExecutionRecord.java` — add fields, the new overload, update `Snapshot`:

```java
    private Integer statusCode;
    private Map<String, String> headers;
    private String encoding;
```

```java
    public synchronized void markSuccess(Object output) {
        markSuccess(output, null, null, null);
    }

    public synchronized void markSuccess(Object output, Integer statusCode,
                                         Map<String, String> headers, String encoding) {
        if (!canTransition(ExecutionState.SUCCESS)) {
            return;
        }
        this.state = ExecutionState.SUCCESS;
        this.finishedAt = Instant.now();
        this.output = output;
        this.lastError = null;
        this.statusCode = statusCode;
        this.headers = headers;
        this.encoding = encoding;
    }
```

Update `snapshot()` and the `Snapshot` record to include the two new fields (append at
the end of both the constructor call and the record's component list):

```java
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                executionId, task, state, startedAt, finishedAt, dispatchedAt,
                output, lastError, coldStart, initDurationMs, statusCode, headers, encoding
        );
    }

    // ...

    public record Snapshot(
            String executionId, InvocationTask task, ExecutionState state,
            Instant startedAt, Instant finishedAt, Instant dispatchedAt,
            Object output, ErrorInfo lastError, boolean coldStart, Long initDurationMs,
            Integer statusCode, Map<String, String> headers, String encoding
    ) {}
```

Add `import java.util.Map;`, clear `headers` in `cleanup()` alongside the existing
`this.output = null;` (same memory reason — it is the only other unbounded field), and
clear all three in `resetForRetry()` so a retried attempt never inherits the previous
attempt's status:

```java
    // in cleanup(), alongside `this.output = null;`:
        this.headers = null;
    // in resetForRetry(), alongside `this.output = null;`:
        this.statusCode = null;
        this.headers = null;
        this.encoding = null;
```

`InvocationResponseMapper.java` — propagate through all three methods:

```java
package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import org.springframework.stereotype.Service;

@Service
public final class InvocationResponseMapper {

    public InvocationResponse toResponse(ExecutionRecord executionRecord, InvocationResult result) {
        String status = result.success() ? "success" : "error";
        return new InvocationResponse(executionRecord.executionId(), status, result.output(), result.error(),
                result.statusCode(), result.headers(), result.encoding());
    }

    public InvocationResponse timeoutResponse(ExecutionRecord executionRecord) {
        return new InvocationResponse(executionRecord.executionId(), "timeout", null, null);
    }

    public InvocationResponse terminalResponse(ExecutionRecord executionRecord) {
        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        if (snapshot.state() == ExecutionState.SUCCESS || snapshot.state() == ExecutionState.ERROR) {
            InvocationResult result = snapshot.lastError() == null
                    ? InvocationResult.successWithEnvelope(snapshot.output(), snapshot.statusCode(),
                            snapshot.headers(), snapshot.encoding())
                    : new InvocationResult(false, null, snapshot.lastError());
            return toResponse(executionRecord, result);
        }
        if (snapshot.state() == ExecutionState.TIMEOUT) {
            return timeoutResponse(executionRecord);
        }
        return null;
    }

    public ExecutionStatus toStatus(ExecutionRecord executionRecord) {
        ExecutionRecord.Snapshot snapshot = executionRecord.snapshot();
        String status = snapshot.state().name().toLowerCase();
        return new ExecutionStatus(
                snapshot.executionId(), status, snapshot.startedAt(), snapshot.finishedAt(),
                snapshot.output(), snapshot.lastError(), snapshot.coldStart(), snapshot.initDurationMs(),
                snapshot.statusCode(), snapshot.headers(), snapshot.encoding()
        );
    }
}
```

`ExecutionCompletionHandler.java` — update the **2** `executionRecord.markSuccess(result.output())`
call sites (`ExecutionCompletionHandler.java:83` and `:268`) to pass the envelope
fields through:

```java
                executionRecord.markSuccess(result.output(), result.statusCode(),
                        result.headers(), result.encoding());
```

(leave the `executionRecord.markError(result.error())` lines untouched — errors never
carry an envelope).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :control-plane:test --tests "*ExecutionRecordTest" --tests "*InvocationResponseMapperTest" --tests "*ExecutionCompletionHandlerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionRecord.java \
        platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationResponseMapper.java \
        platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionRecordTest.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationResponseMapperTest.java
git commit -m "feat: persist and propagate function status/headers through execution records"
```

---

### Task 10: `InvocationController.invokeSync` uses the function-decided status as the real HTTP response

**Files:**
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/InvocationController.java`
- Test: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/InvocationControllerTest.java` (existing — add cases; read first)

**Interfaces:**
- Consumes: `InvocationResponse.statusCode()/.headers()` (Task 4).
- Produces: when `invocation.response().statusCode()` is non-null, `invokeSync`'s HTTP
  response uses it (plus allow-listed headers) instead of always 200, and adds
  `X-NanoFaaS-Function-Status: true`. `X-Execution-Id` and `X-NanoFaaS-Offloaded`
  headers are still always set exactly as today.

**Two things this task must get right beyond the status code:**

1. **Emit the marker header.** The remote `:invoke` response is itself consumed by
   another nanofaas instance in the offload path, and `DefaultOffloadGateway` currently
   treats *any* non-2xx as a failure (404 gets its own "function not registered"
   branch). Without the marker on this hop, a function-decided 422 from the cloud
   instance surfaces at the edge as a 502. Task 10b reads this header.
2. **Do not re-emit `Content-Type`.** The body of this response is still the
   `InvocationResponse` JSON envelope, not the handler's raw output. Setting the
   handler's `Content-Type: application/pdf` on a JSON body is a lie that will break
   any client that trusts it. It stays readable in `response.headers()` — a caller that
   wants the raw bytes reads `output` plus `headers` and decides for itself. Changing
   `:invoke` to return the raw body instead of the envelope is a separate, larger
   contract change and is explicitly **not** in this milestone.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void invokeSync_functionDecidedStatusCode_usedAsRealHttpStatus() {
        // Mock invocationService.invokeSyncReactive(...) to return a SyncInvocation whose
        // .response() is new InvocationResponse("ex-1", "success", body, null, 404,
        //   Map.of("Location", "/x"), null).
        // Act: call controller.invokeSync(...)
        // Assert: resulting ResponseEntity status is 404
        // Assert: header Location == /x
        // Assert: header X-NanoFaaS-Function-Status == "true"
        // Assert: header X-Execution-Id is still present (unchanged existing behavior)
    }

    @Test
    void invokeSync_doesNotReEmitHandlerContentType() {
        // .response() carries headers Map.of("Content-Type", "application/pdf")
        // Assert: the HTTP Content-Type is NOT application/pdf — the body is still the
        //         InvocationResponse JSON envelope.
        // Assert: response body's headers() still contains Content-Type: application/pdf
        //         (the caller can read it, it is just not applied to this hop).
    }

    @Test
    void invokeSync_noStatusCode_defaultsTo200AsToday() {
        // .response() has statusCode == null
        // Assert: resulting ResponseEntity status is 200
        // Assert: no X-NanoFaaS-Function-Status header
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :control-plane:test --tests "*InvocationControllerTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Replace the `.map(invocation -> ...)` block inside `invokeSync`:

```java
                .map(invocation -> {
                    InvocationResponse response = invocation.response();
                    boolean functionDecided = response.statusCode() != null;
                    int status = functionDecided ? response.statusCode() : 200;
                    ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                            .header("X-Execution-Id", response.executionId());
                    if (functionDecided) {
                        builder.header("X-NanoFaaS-Function-Status", "true");
                    }
                    if (response.headers() != null) {
                        response.headers().forEach((name, value) -> {
                            // ponytail: Content-Type would describe the handler's body, but the
                            // body here is still the InvocationResponse envelope. Readable in
                            // the payload, not applied to this hop.
                            if (!"content-type".equalsIgnoreCase(name)) {
                                builder.header(name, value);
                            }
                        });
                    }
                    if (invocation.offloadedTarget() != null) {
                        builder.header("X-NanoFaaS-Offloaded", invocation.offloadedTarget());
                    }
                    return builder.body(response);
                })
```

Header safety note: `response.headers()` was already filtered by
`ResponseHeaderPolicy.filterAllowedHeaders` upstream (Task 7/8), so no re-filtering is
needed here — this method trusts values that already passed the allow-list once.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :control-plane:test --tests "*InvocationControllerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/InvocationController.java \
        platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/InvocationControllerTest.java
git commit -m "feat: use function-decided status code as the real :invoke HTTP response"
```

---

### Task 10b: `DefaultOffloadGateway` stops turning function-decided statuses into 502

**Files:**
- Modify: `platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/DefaultOffloadGateway.java`
- Test: `platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/DefaultOffloadGatewayTest.java` (existing — add cases; read it first to match its WebClient-mocking style)

**Interfaces:**
- Consumes: the `X-NanoFaaS-Function-Status` marker on `:invoke` (Task 10) and
  `InvocationResponse.statusCode()/.headers()/.encoding()` (Task 4).
- Produces: an offloaded function-decided response reaches the edge caller intact
  instead of becoming a 502.

**Why this task exists.** It was missing from the original plan, and without it Task 10
is a regression rather than a feature for anyone using the offload module.
`DefaultOffloadGateway.invokeRemote` maps *every* non-2xx from the remote to
`OffloadFailedException`, with a dedicated 404 branch that reports "function not
registered on remote". After Task 10 a cloud-side handler answering 422 or 404 makes the
remote `:invoke` return that status for real — so the edge caller gets 502 (or a
misleading "not registered") instead of the function's own answer. Separately,
`toResult` rebuilds the `InvocationResult` with `InvocationResult.success(output)`,
which drops `statusCode`/`headers`/`encoding` even on the 2xx path.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void invokeRemote_functionDecidedNon2xx_isNotAnOffloadFailure() {
        // Remote :invoke responds 422 with X-NanoFaaS-Function-Status: true and body
        // {"executionId":"ex-1","status":"success","output":{"error":"bad"},"statusCode":422}
        // Assert: the Mono emits an InvocationResult (no OffloadFailedException)
        // Assert: result.success() == true, result.statusCode() == 422
    }

    @Test
    void invokeRemote_functionDecided404_isNotReportedAsNotRegistered() {
        // Remote responds 404 WITH the marker header.
        // Assert: no OffloadFailedException; result.statusCode() == 404
        // (guards the dedicated 404 branch, which is the most misleading failure mode)
    }

    @Test
    void invokeRemote_2xx_propagatesEnvelopeFields() {
        // Remote responds 200 with body carrying statusCode 201 and headers {"Location":"/x"}
        // Assert: result.statusCode() == 201 and result.headers().get("Location") == "/x"
    }

    @Test
    void invokeRemote_non2xxWithoutMarker_isStillOffloadFailure() {
        // Remote responds 500, no marker (genuine platform failure).
        // Assert: OffloadFailedException — unchanged behavior.
    }

    @Test
    void invokeRemote_404WithoutMarker_stillReportsNotRegistered() {
        // Assert: OffloadFailedException with the "not registered on remote" message — unchanged.
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :modules:offload:test --tests "*DefaultOffloadGatewayTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Read the marker before any status-based branching, and route marked responses down the
same body-parsing path as 2xx:

```java
                .exchangeToMono(response -> {
                    boolean functionDecided = "true".equalsIgnoreCase(
                            response.headers().asHttpHeaders().getFirst("X-NanoFaaS-Function-Status"));

                    // A marked response is the function's own answer, whatever its status.
                    if (functionDecided || response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(InvocationResponse.class)
                                .map(this::toResult)
                                .switchIfEmpty(Mono.error(new OffloadFailedException(target, false,
                                        "empty response body from remote " + target)));
                    }
                    if (response.statusCode().value() == 404) {
                        return response.releaseBody().then(Mono.error(new OffloadFailedException(target, false,
                                "function '" + task.functionName() + "' not registered on remote " + target)));
                    }
                    int status = response.statusCode().value();
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(new OffloadFailedException(target, false,
                                    REMOTE_PREFIX + target + " returned " + status
                                            + (body.isBlank() ? "" : ": " + body))));
                })
```

And stop dropping the envelope in `toResult`:

```java
    private InvocationResult toResult(InvocationResponse response) {
        return switch (response.status() == null ? "" : response.status()) {
            case "success" -> InvocationResult.successWithEnvelope(
                    response.output(), response.statusCode(), response.headers(), response.encoding());
            case "timeout" -> InvocationResult.error("REMOTE_TIMEOUT", "remote execution timed out");
            default -> response.error() != null
                    ? new InvocationResult(false, null, response.error())
                    : InvocationResult.error("REMOTE_ERROR", "remote execution failed with status " + response.status());
        };
    }
```

Single hop is preserved: nothing here changes the "no local fallback" rule — a marked
response is a *success*, not a failure to fall back from.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :modules:offload:test`
Expected: PASS (whole module — `OffloadPressureE2eTest` exercises the same path and must
stay green).

- [ ] **Step 5: Commit**

```bash
git add platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/DefaultOffloadGateway.java \
        platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/DefaultOffloadGatewayTest.java
git commit -m "feat: propagate function-decided status through the offload hop"
```

---

### Task 11: Java example — `roman-numeral` returns 422 for invalid/out-of-range input

**Files:**
- Modify: `functions/java/roman-numeral/src/main/java/it/unimib/datai/nanofaas/examples/romannumeral/RomanNumeralHandler.java`
- Modify: `functions/java/roman-numeral/src/test/java/it/unimib/datai/nanofaas/examples/romannumeral/RomanNumeralHandlerTest.java`
- Modify: `functions/test-data/roman-numeral/correctness.json` (add `expectedStatusCode`
  to the error cases; read the existing file first to match its structure exactly before
  editing)

**Interfaces:**
- Consumes: `HandlerResponse` (Task 1).

**The fixture is shared by seven implementations, not two.** `functions/test-data/roman-numeral/correctness.json`
is read by `functions/java/roman-numeral`, `functions/java/roman-numeral-lite`,
`functions/go/roman-numeral`, `functions/python/roman-numeral`,
`functions/javascript/roman-numeral`, and `functions/contract-tests/run.sh` (which also
checks `functions/bash/roman-numeral/handler.sh`). Only the Java and Python ones are in
this milestone's scope. Adding a new `expectedStatusCode` key is additive and the other
five simply ignore it, so nothing breaks — but do not describe the fixture as proving a
single cross-language envelope contract, because after this task it does not. Task 15
states exactly what it does prove.

- [ ] **Step 1: Write the failing tests**

Update `RomanNumeralHandlerTest.java`'s existing error-path tests:

```java
    @Test
    void handleReturnsMissingFieldError() {
        var req = new InvocationRequest(Map.of(), null);
        var result = (HandlerResponse) handler.handle(req);
        assertEquals(422, result.statusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result.output();
        assertEquals("missing required field: number", body.get("error"));
    }

    @Test
    void handleReturnsRomanForValidNumber() {
        var req = new InvocationRequest(Map.of("number", 42), null);
        var result = handler.handle(req);
        assertFalse(result instanceof HandlerResponse); // unchanged: plain value, implicit 200
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result;
        assertEquals("XLII", body.get("roman"));
    }

    @Test
    void handleRejectsOutOfRangeNumber() {
        var req = new InvocationRequest(Map.of("number", 4000), null);
        var result = (HandlerResponse) handler.handle(req);
        assertEquals(422, result.statusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result.output();
        assertTrue(((String) body.get("error")).startsWith("number must be between"));
    }
```

Add the import `it.unimib.datai.nanofaas.common.runtime.HandlerResponse;`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :functions:java:roman-numeral:test`
Expected: FAIL — handler still returns a plain `Map` for errors, not `HandlerResponse`.

- [ ] **Step 3: Implement**

In `RomanNumeralHandler.handle`, wrap every error return in `HandlerResponse.of(..., 422)`,
leave the success return unchanged:

```java
    @Override
    @SuppressWarnings("unchecked")
    public Object handle(InvocationRequest request) {
        log.info("roman-numeral invoked, executionId={}", FunctionContext.getExecutionId());

        if (!(request.input() instanceof Map<?, ?> rawInput)) {
            return HandlerResponse.of(Map.of(ERROR_KEY, "Input must be a JSON object"), 422);
        }
        var input = (Map<String, Object>) rawInput;

        if (!input.containsKey("number")) {
            return HandlerResponse.of(Map.of(ERROR_KEY, "missing required field: number"), 422);
        }
        int n;
        try {
            n = ((Number) input.get("number")).intValue();
        } catch (ClassCastException _) {
            return HandlerResponse.of(Map.of(ERROR_KEY, "field 'number' must be an integer"), 422);
        }
        if (n < 1 || n > 3999) {
            return HandlerResponse.of(Map.of(ERROR_KEY, "number must be between 1 and 3999, got: " + n), 422);
        }
        return Map.of("roman", toRoman(n));
    }
```

Add the import `it.unimib.datai.nanofaas.common.runtime.HandlerResponse;`.

Update `functions/test-data/roman-numeral/correctness.json`'s four error-case entries to
add `"expectedStatusCode": 422` alongside their existing `"expected": {"error": ...}`
shape. The success case (`converts a canonical value`) gets no such key — its absence is
what the tests read as "implicit 200".

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :functions:java:roman-numeral:test :functions:java:roman-numeral-lite:test`
Expected: PASS. The `-lite` module is included deliberately: it reads the same fixture
and still returns plain dicts for errors, so it proves the added `expectedStatusCode`
key is inert for the implementations left out of this milestone.

- [ ] **Step 5: Commit**

```bash
git add functions/java/roman-numeral/src/main/java/it/unimib/datai/nanofaas/examples/romannumeral/RomanNumeralHandler.java \
        functions/java/roman-numeral/src/test/java/it/unimib/datai/nanofaas/examples/romannumeral/RomanNumeralHandlerTest.java \
        functions/test-data/roman-numeral/correctness.json
git commit -m "feat: roman-numeral returns 422 via HandlerResponse for invalid input"
```

---

### Task 12: Python SDK — `HandlerResponse` dataclass and request headers in `context`

**Files:**
- Create: `sdks/python/src/nanofaas/sdk/response.py`
- Modify: `sdks/python/src/nanofaas/sdk/context.py`
- Modify: `sdks/python/src/nanofaas/sdk/__init__.py` (export `HandlerResponse`)
- Test: `sdks/python/tests/test_response.py` (create)
- Test: `sdks/python/tests/test_context.py` (existing — add cases; read first)

**Interfaces:**
- Produces: `HandlerResponse(output, status_code, headers=None, encoding=None)` —
  frozen dataclass.
- Produces: `context.set_headers(headers: dict[str,str] | None) -> None`,
  `context.get_headers() -> dict[str,str]` (empty dict, never `None`, if unset).

- [ ] **Step 1: Write the failing tests**

```python
# sdks/python/tests/test_response.py
from nanofaas.sdk.response import HandlerResponse


def test_handler_response_defaults():
    r = HandlerResponse({"error": "not found"}, 404)
    assert r.output == {"error": "not found"}
    assert r.status_code == 404
    assert r.headers == {}
    assert r.encoding is None


def test_handler_response_with_headers_and_encoding():
    r = HandlerResponse(b"binary", 200, headers={"Content-Type": "application/pdf"}, encoding="base64")
    assert r.headers["Content-Type"] == "application/pdf"
    assert r.encoding == "base64"
```

Add to `test_context.py`:

```python
def test_headers_default_empty_dict():
    from nanofaas.sdk import context
    assert context.get_headers() == {}


def test_set_and_get_headers():
    from nanofaas.sdk import context
    context.set_headers({"authorization": "Bearer x"})
    assert context.get_headers()["authorization"] == "Bearer x"


def test_set_headers_none_yields_empty_dict():
    from nanofaas.sdk import context
    context.set_headers(None)
    assert context.get_headers() == {}
```

Keys are lower-case by contract (Global Constraints) — the control plane normalizes them
so the same handler code works in both languages.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd sdks/python && uv run pytest tests/test_response.py tests/test_context.py -v`
Expected: FAIL — `nanofaas.sdk.response` module and `set_headers`/`get_headers` do not exist.

- [ ] **Step 3: Implement**

```python
# sdks/python/src/nanofaas/sdk/response.py
"""Optional response envelope for the nanofaas Python SDK.

A handler may return a :class:`HandlerResponse` instead of a plain value to control
the HTTP status code, a safe set of response headers, and whether ``output`` is
base64-encoded binary. Returning a plain value keeps today's behavior (implicit 200,
no extra headers).
"""
from dataclasses import dataclass, field


@dataclass(frozen=True)
class HandlerResponse:
    output: object
    status_code: int
    headers: dict[str, str] = field(default_factory=dict)
    encoding: str | None = None
```

Append to `context.py`:

```python
_headers: contextvars.ContextVar[dict[str, str] | None] = contextvars.ContextVar(
    "headers", default=None
)


def get_headers() -> dict[str, str]:
    """Return the incoming request headers for the current invocation context.

    :returns: A mapping of header name to value; empty if none were set.
    :rtype: dict[str, str]
    """
    return _headers.get() or {}


def set_headers(headers: dict[str, str] | None) -> None:
    """Populate the request headers for the current invocation context.

    :param headers: Filtered request headers (hop-by-hop and dedicated control
        headers already excluded by the caller).
    :type headers: dict[str, str] | None
    """
    _headers.set(headers)
```

Update `sdk/__init__.py` to export `HandlerResponse` alongside the existing
`nanofaas_function`/`context` exports (match its existing export style — read it first).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd sdks/python && uv run pytest tests/test_response.py tests/test_context.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add sdks/python/src/nanofaas/sdk/response.py sdks/python/src/nanofaas/sdk/context.py \
        sdks/python/src/nanofaas/sdk/__init__.py \
        sdks/python/tests/test_response.py sdks/python/tests/test_context.py
git commit -m "feat: add HandlerResponse dataclass and request headers to Python SDK"
```

---

### Task 13: Python runtime — wire headers-in and the envelope out in `app.py`

**Files:**
- Modify: `sdks/python/src/nanofaas/runtime/app.py`
- Test: existing test file for `app.py` (find it — likely
  `sdks/python/tests/test_app.py` or `sdks/python/tests/runtime/test_app.py`; read it
  first to match its `TestClient`/fixture style before writing new cases)

**Interfaces:**
- Consumes: `HandlerResponse`, `context.set_headers` (Task 12), and the allow-list that
  mirrors `ResponseHeaderPolicy` (Global Constraints section) — define it locally in
  `app.py` since Python has no dependency on the Java `platform/common` module; keep
  the exact same values.

**Headers come from the body, not from `request.headers`.** Same reason as the Java
side (Task 6): the runtime's own inbound HTTP headers belong to the control-plane hop.
`app.py` already parses the body into `payload` and reads `payload.get("input")`, so
this is `payload.get("headers")` right next to it — no request-header filtering code in
the runtime at all. That also means there is no `_EXCLUDED_REQUEST_HEADERS` constant
here: the control plane already filtered and lower-cased.

- [ ] **Step 1: Write the failing tests**

```python
def test_invoke_handler_returns_handler_response_uses_its_status_and_headers(client, register_handler):
    register_handler(lambda input_data: HandlerResponse({"error": "not found"}, 404, {"Content-Type": "application/json"}))
    resp = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-1"})
    assert resp.status_code == 404
    assert resp.headers["content-type"].startswith("application/json")
    assert resp.headers["x-nanofaas-function-status"] == "true"


def test_invoke_handler_returns_handler_response_drops_disallowed_headers(client, register_handler):
    register_handler(lambda input_data: HandlerResponse({"ok": True}, 200, {"X-Execution-Id": "spoof", "X-Custom": "nope"}))
    resp = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-2"})
    assert resp.headers.get("x-custom") is None


def test_invoke_handler_returns_handler_response_invalid_status_falls_back_to_500(client, register_handler):
    register_handler(lambda input_data: HandlerResponse({"x": 1}, 999))
    resp = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-3"})
    assert resp.status_code == 500
    assert "x-nanofaas-function-status" not in resp.headers


def test_invoke_handler_returns_plain_value_behaves_as_today(client, register_handler):
    register_handler(lambda input_data: {"roman": "XLII"})
    resp = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-4"})
    assert resp.status_code == 200
    assert "x-nanofaas-function-status" not in resp.headers


def test_invoke_exposes_request_headers_from_body_to_handler(client, register_handler):
    seen = {}
    def handler(input_data):
        from nanofaas.sdk import context
        seen.update(context.get_headers())
        return {"ok": True}
    register_handler(handler)
    client.post(
        "/invoke",
        json={"input": {}, "headers": {"authorization": "Bearer x"}},
        headers={"X-Execution-Id": "ex-5"},
    )
    assert seen.get("authorization") == "Bearer x"


def test_invoke_ignores_own_http_headers_for_handler_context(client, register_handler):
    # The runtime's inbound headers are the control plane's, not the caller's — reading
    # them here is the bug Task 6 exists to prevent. This test pins that.
    seen = {}
    def handler(input_data):
        from nanofaas.sdk import context
        seen.update(context.get_headers())
        return {"ok": True}
    register_handler(handler)
    client.post("/invoke", json={"input": {}}, headers={
        "X-Execution-Id": "ex-6", "Authorization": "Bearer leaked"})
    assert seen == {}


def test_invoke_envelope_body_is_output_verbatim(client, register_handler):
    # Java returns the normalized output bare; Python must not apply its usual
    # {"result": ...} wrapping in the envelope path.
    register_handler(lambda input_data: HandlerResponse("hello", 200, {"Content-Type": "text/plain"}))
    resp = client.post("/invoke", json={"input": {}}, headers={"X-Execution-Id": "ex-7"})
    assert resp.json() == "hello"
```

(Use whatever `client`/`register_handler` fixtures the existing test file already
defines — read it first; do not invent a different fixture shape.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd sdks/python && uv run pytest tests/ -k "handler_response or filtered_request_headers" -v`
Expected: FAIL.

- [ ] **Step 3: Implement**

Add near the top of `app.py`, alongside the other module-level constants:

```python
from nanofaas.sdk.response import HandlerResponse

# Mirror of ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS (platform/common). Keep in sync.
_ALLOWED_RESPONSE_HEADERS = {
    "content-type", "location", "cache-control", "etag",
    "content-disposition", "content-language", "retry-after", "vary",
}


def _filter_response_headers(raw: dict[str, str] | None) -> dict[str, str]:
    if not raw:
        return {}
    return {k: v for k, v in raw.items() if k.lower() in _ALLOWED_RESPONSE_HEADERS}
```

In `invoke`, the headers come out of the already-parsed body, next to `input`:

```python
        payload = await request.json()
        # Keep the Python runtime aligned with the Java InvocationRequest contract.
        # Handlers receive the input field rather than the transport envelope.
        input_data = payload.get("input") if isinstance(payload, dict) else payload
        # Request headers are captured and filtered by the control plane and ride in the
        # body; this runtime's own HTTP headers belong to the control-plane hop.
        context.set_headers(payload.get("headers") if isinstance(payload, dict) else None)
```

`context.set_context(execution_id, trace_id)` earlier in the function stays exactly as
it is — it runs before the body is read and is unrelated.

Replace the success block (from `output = await asyncio.wait_for(...)` through the
`return JSONResponse(...)` two lines later):

```python
        output = await asyncio.wait_for(invocation, timeout=HANDLER_TIMEOUT_SECONDS)

        response_status = 200
        response_headers = _build_cold_start_headers(is_cold_start)
        response_body = output
        callback_status_code = None
        callback_headers = None
        callback_encoding = None

        if isinstance(output, HandlerResponse):
            if 200 <= output.status_code <= 599:
                response_status = output.status_code
                allowed = _filter_response_headers(output.headers)
                response_headers = {**response_headers, **allowed, "X-NanoFaaS-Function-Status": "true"}
                response_body = output.output
                callback_status_code = output.status_code
                callback_headers = allowed
                callback_encoding = output.encoding
            else:
                logger.warning(f"Handler returned invalid status_code {output.status_code} for execution {execution_id}")
                RUNTIME_INVOCATIONS_TOTAL.labels(function=FUNCTION_NAME, success="false").inc()
                if callback_url:
                    _schedule_callback(
                        background_tasks, callback_url, execution_id, trace_id,
                        {"success": False, "output": None,
                         "error": {"code": "OUTPUT_SERIALIZATION_ERROR",
                                   "message": f"Handler returned invalid status_code: {output.status_code}"}},
                        x_dispatch_attempt,
                    )
                return JSONResponse(status_code=500, content={
                    "error": f"Handler returned invalid status_code: {output.status_code}"})

        RUNTIME_INVOCATIONS_TOTAL.labels(function=FUNCTION_NAME, success="true").inc()
        result = {"success": True, "output": response_body, "error": None}
        if callback_status_code is not None:
            result["statusCode"] = callback_status_code
            result["headers"] = callback_headers
            result["encoding"] = callback_encoding

        if callback_url:
            _schedule_callback(
                background_tasks, callback_url, execution_id, trace_id, result, x_dispatch_attempt
            )

        return JSONResponse(
            status_code=response_status,
            # ponytail: the envelope path emits output verbatim so Java and Python agree on
            # the wire; the plain path keeps today's {"result": ...} wrapping untouched for
            # backward compatibility.
            content=response_body
            if callback_status_code is not None or isinstance(response_body, (dict, list))
            else {"result": response_body},
            headers=response_headers,
        )
```

Note on `Content-Type`: Starlette's `Response.init_headers` only injects its own
`content-type` when the caller did not supply one, so passing the handler's value
through `headers=` correctly overrides the default `application/json`. The body is still
JSON-serialized — a handler claiming `text/plain` gets a JSON-encoded string, which is
the honest representation of what it returned. Emitting genuinely raw bodies is part of
the deferred binary milestone, not this one.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd sdks/python && uv run pytest tests/ -v`
Expected: PASS (full suite — confirms no regression in the untouched error branches).

- [ ] **Step 5: Commit**

```bash
git add sdks/python/src/nanofaas/runtime/app.py
git commit -m "feat: detect HandlerResponse envelope and expose request headers in Python runtime"
```

---

### Task 14: Python example — `roman-numeral` returns 422 for invalid/out-of-range input

**Files:**
- Modify: `functions/python/roman-numeral/handler.py`
- Modify: `functions/python/roman-numeral/tests/test_handler.py`

**Interfaces:**
- Consumes: `HandlerResponse` (Task 12).

- [ ] **Step 1: Write the failing tests**

```python
def test_handle_missing_field():
    result = _invoke({})
    assert isinstance(result, HandlerResponse)
    assert result.status_code == 422
    assert result.output == {"error": "missing required field: number"}


def test_handle_out_of_range_high():
    result = _invoke({"number": 4000})
    assert isinstance(result, HandlerResponse)
    assert result.status_code == 422
    assert "3999" in result.output["error"]


def test_handle_out_of_range_zero():
    result = _invoke({"number": 0})
    assert isinstance(result, HandlerResponse)
    assert result.status_code == 422


def test_handle_non_integer():
    result = _invoke({"number": "abc"})
    assert isinstance(result, HandlerResponse)
    assert result.status_code == 422


def test_handle_valid_number():
    result = _invoke({"number": 42})
    assert result == {"roman": "XLII"}  # unchanged: plain value, implicit 200
```

Add `from nanofaas.sdk.response import HandlerResponse` to the test file's imports.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd functions/python/roman-numeral && uv run pytest tests/test_handler.py -v`
Expected: FAIL — handler still returns plain dicts for errors.

- [ ] **Step 3: Implement**

```python
from nanofaas.sdk import nanofaas_function, context
from nanofaas.sdk.response import HandlerResponse

# ... _ROMAN_TABLE / _to_roman unchanged ...

@nanofaas_function
def handle(input_data):
    logger.info(f"roman-numeral invoked, executionId={context.get_execution_id()}")

    if not isinstance(input_data, dict):
        return HandlerResponse({"error": "Input must be a JSON object"}, 422)

    if "number" not in input_data:
        return HandlerResponse({"error": "missing required field: number"}, 422)

    n = input_data["number"]
    if not isinstance(n, (int, float)) or isinstance(n, bool):
        return HandlerResponse({"error": "field 'number' must be an integer"}, 422)

    n = int(n)

    if not 1 <= n <= 3999:
        return HandlerResponse({"error": f"number must be between 1 and 3999, got: {n}"}, 422)

    return {"roman": _to_roman(n)}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd functions/python/roman-numeral && uv run pytest tests/test_handler.py -v`
Expected: PASS. Note the `test_shared_contract` parametrized test at the bottom of the
file reads `functions/test-data/roman-numeral/correctness.json` and only asserts on
`_invoke(...)`'s dict shape — since `HandlerResponse` is not a `dict`, that
parametrized test starts comparing a `HandlerResponse` instance to a plain dict on the
error cases and will fail. Fix it in this same step by asserting against `.output` when
the result is a `HandlerResponse`:

```python
@pytest.mark.parametrize("contract_case", SHARED_CASES, ids=lambda case: case["name"])
def test_shared_contract(contract_case):
    result = _invoke(contract_case["input"])
    actual = result.output if isinstance(result, HandlerResponse) else result
    assert actual == contract_case["expected"]
    if isinstance(result, HandlerResponse):
        assert result.status_code == contract_case.get("expectedStatusCode", 200)
```

- [ ] **Step 5: Commit**

```bash
git add functions/python/roman-numeral/handler.py functions/python/roman-numeral/tests/test_handler.py
git commit -m "feat: roman-numeral returns 422 via HandlerResponse for invalid input (Python)"
```

---

### Task 15: Cross-SDK regression check — shared contract fixture agrees for both languages

**Files:**
- Modify: `functions/test-data/roman-numeral/correctness.json` (verify only — should
  already carry `expectedStatusCode: 422` on every error case from Task 11; this task is
  the final cross-language proof, not a new fixture)
- No new test files — this task runs the suites back to back and fixes any drift.

**Interfaces:**
- Consumes: everything from Tasks 1–14.

**What this task proves, precisely.** Java and Python read the same fixture and agree on
both the error body *and* the 422 status. It does **not** prove a single cross-language
envelope contract: `roman-numeral` also exists in Go, JavaScript, bash and Java `-lite`,
and those five keep returning plain dicts with an implicit 200 for the same inputs. That
divergence is the intended, scoped consequence of "Java + Python only" — the point of
Step 3 is to confirm it is *inert* (nobody breaks) rather than accidental.

- [ ] **Step 1: Run both in-scope language suites against the shared fixture**

Run: `./gradlew :functions:java:roman-numeral:test`
Run: `cd functions/python/roman-numeral && uv run pytest tests/test_handler.py -v`

Expected: both PASS, and both assert `expectedStatusCode: 422` from the exact same
`functions/test-data/roman-numeral/correctness.json` file — proving the Java and Python
bindings of the envelope produce the same wire-level result for the same input, not just
that each language's tests pass in isolation.

- [ ] **Step 2: Run the full repository regression suites**

Run: `./gradlew test --no-parallel`
Run: `cd sdks/python && uv run pytest tests/ -v`
Run: `cd functions/python/roman-numeral && uv run pytest tests/ -v`

Expected: all PASS — proves the envelope changes did not regress any other function,
module, or control-plane path across the whole repository. `--no-parallel` per CLAUDE.md.

- [ ] **Step 3: Run the cross-language contract runner**

Run: `./functions/contract-tests/run.sh`

Expected: PASS. This is the only gate that exercises the out-of-scope implementations
(Go, JavaScript, bash, Java `-lite`) against the fixture Task 11 edited. It must stay
green: the new `expectedStatusCode` key has to be inert for them. If it is not, fix the
fixture — do not start converting out-of-scope languages to the envelope.

- [ ] **Step 3: Commit (only if Step 1/2 required fixes)**

```bash
git add -A
git commit -m "test: verify shared roman-numeral contract fixture agrees across Java and Python"
```

If Steps 1–2 passed with no changes needed, skip this commit — there is nothing new to
record.

---

## Self-Review Notes

- **Spec coverage**: wire envelope (Task 1), request headers in (Tasks 6, 12, 13),
  response status/headers out (Tasks 7, 8, 9, 10, 10b, 13), binary `encoding` field
  (threaded through every model/record in Tasks 1, 3, 4, 5, 9 — no dedicated task
  encodes a binary example since the spec's binary payload example function work is
  explicitly in the deferred "extensive" milestone, not this one), validation
  (Tasks 7, 13), backward compatibility (regression tests in Tasks 2, 3, 4, 7, 11, 13,
  14, 15), one demonstration example per language (Tasks 11, 14), shared JSON fixture
  (Task 15).
- **Marker header** (`X-NanoFaaS-Function-Status`) was not in the original spec text —
  discovered as a genuine gap while writing Task 8: `ExternalDispatcher` reads a real
  HTTP response across a process boundary and cannot see the Java/Python `instanceof`
  check that happens inside the runtime process. Without a marker, a function-decided
  404 and a genuine platform 404 would be indistinguishable on the wire for the
  sync-dispatch path. It applies to *both* synchronous hops — the runtime's `/invoke`
  and the control plane's `:invoke` — because the offload gateway reads the latter
  exactly as `ExternalDispatcher` reads the former. Added to Global Constraints and
  Tasks 7/8/10/10b/13.

### Corrections applied after reviewing the plan against the code

1. **Request headers now travel in the body, captured at control-plane ingress
   (Task 6, rewritten).** The original Task 6 read the runtime's own inbound HTTP
   headers, which are the control plane's four fixed headers — the caller's
   `Authorization` never arrives. The SDK unit tests POST directly at `/invoke`, so
   they would have gone green on a feature that was dead end to end. Moving capture to
   `InvocationController` also makes `InvocationRequest.headers` (Task 2) the thing
   that actually carries them, covers `:enqueue` and every dispatch mode for free, and
   removes header-capture code from both runtime SDKs.
2. **Offload was missing entirely (new Task 10b).** `DefaultOffloadGateway` maps every
   non-2xx to a 502 and 404 to "function not registered", so Task 10 would have turned
   any offloaded function-decided status into an edge-side failure. Its `toResult` also
   dropped the envelope fields on the success path.
3. **`ExecutionStatus` gets a compatibility constructor (Task 4).** Without it
   `:control-plane` stopped compiling from Task 4 until Task 9, which made Tasks 5–8
   unverifiable — Task 8 declared a `:control-plane:test → PASS` that could not happen.
   Two existing call sites (`CommonModelTest.java:74`,
   `InvocationControllerTest.java:262`) were also unaccounted for.
4. **`ExternalDispatcher`'s marker branch shares the content-type decode (Task 8).** The
   original branch went straight to `bodyToMono(Object.class)`, which fails on
   `text/plain` — breaking precisely the case the envelope exists to enable.
5. **`Content-Type` is no longer re-emitted at the `:invoke` hop (Task 10).** The body
   there is still the `InvocationResponse` envelope; labelling it `application/pdf`
   would be a lie. It stays readable inside the payload.
6. **`encoding` is threaded consistently (Tasks 4, 9).** It was on four records but not
   on `ExecutionStatus` or `ExecutionRecord.Snapshot`, so `terminalResponse` hardcoded
   `null` and the marker vanished on the replay and `GET /executions/{id}` paths — the
   exact paths where a caller reads a completed binary result back.
7. **Header keys are lower-cased (Global Constraints, Tasks 6, 12, 13).** Spring
   preserves the sender's casing, Starlette lower-cases; the original plan's own tests
   encoded that split (`Authorization` in Java, `authorization` in Python).
8. **Python emits envelope bodies verbatim (Task 13).** Its `{"result": ...}` wrapping
   for non-dict outputs would have made the same handler return a different shape than
   Java through the same envelope.
9. **Round-trip serde tests added (Global Constraints, Tasks 2, 3, 4, 5).** Adding a
   secondary constructor to a Jackson-deserialized record is the one change here that
   can break at runtime while every accessor test stays green. Task 5's test is also
   the only thing tying `CallbackPayload` (sender) to `InvocationResult` (receiver).
10. **Fixture path corrected to `functions/test-data/...` (Tasks 11, 14, 15)** — the
    original path did not exist, so the `git add` lines would have failed — and Task 15
    no longer claims the fixture proves a single cross-language contract, since five of
    its seven implementations stay out of scope. Added a `contract-tests/run.sh` gate to
    prove the fixture change is inert for them.
11. **Minor**: Task 9 said three `markSuccess` call sites (there are two); Task 4's
    Step 4 contradicted itself and mis-attributed `InvocationResponseMapper` to Task 10
    instead of Task 9; Task 15 now uses `./gradlew test --no-parallel` per CLAUDE.md.
