# Handler Response Envelope — Milestone 1 Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the three defects the milestone-1 final review deferred, so the JS/Go/watchdog
bindings that follow copy corrected behavior instead of replicating a bug.

**Architecture:** Three independent fixes to already-shipped code. Task 1 makes the response-header
allow-list filter deduplicate case-insensitively in both Java and Python (they are mirrors and must
stay in sync). Task 2 turns two shared-contract tests that structurally tolerate a revert into real
regression guards. Task 3 covers an untested defensive guard in `ExternalDispatcher`.

**Tech Stack:** Java 25 / Spring Boot 4.1 (WebFlux control-plane, WebMVC SDK runtime), Python 3.11+ /
FastAPI, JUnit 5, pytest, okhttp `MockWebServer`.

**Context:** milestone 1 (#174/#175/#176) shipped in `0ffaf217`. This plan is the follow-up section
of GitHub issue #193. It is deliberately scoped to the three deferred minors — the JS/Go/watchdog
bindings, the example-function pass and the `fn-init` templates are separate plans.

## Global Constraints

- Java 25, 4-space indentation, package root `it.unimib.datai.nanofaas` (NOT `com.nanofaas`, despite
  what CLAUDE.md says). Control plane is Spring WebFlux (reactive, no blocking calls); `sdks/java` is
  Spring WebMVC; Python is FastAPI.
- **`ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS` (Java) and `_ALLOWED_RESPONSE_HEADERS` (Python)
  are mirrors and must stay in sync.** Both are exactly `content-type`, `location`, `cache-control`,
  `etag`, `content-disposition`, `content-language`, `retry-after`, `vary`, lower-cased.
- **The original casing of a surviving header key MUST be preserved.** Five existing test sites assert
  exact `"Content-Type"` casing, and `InvocationControllerTest` asserts `$.headers['Content-Type']` in
  the response *body*. That `headers` map is a public API field on `InvocationResponse` — lower-casing
  the keys would be an observable wire change for every consumer, for no functional gain. Dedupe
  without re-casing.
- A dropped or deduplicated header must **never** fail the invocation — it is logged at WARN and the
  response proceeds.
- Status codes: only `[200,599]` are legitimate; outside that range is a platform error, never a
  passthrough.
- Jackson 3 (`tools.jackson`) — there is no `TextNode`; the text-node class is `StringNode`.
- No dependency version literals anywhere — versions come from the `spring-boot-dependencies` BOM.
- **`./gradlew test --no-parallel` HALTS at the pre-existing failure `:nanofaas-cli
  RootCommandTest.versionComesFromTheBuild`,** so modules ordered after it never run. Use
  `./gradlew test --no-parallel --continue` for any full-suite claim. That CLI failure is
  pre-existing, unrelated, and must not be fixed here.
- Do not touch `sdks/javascript`, `sdks/go`, `runtimes/watchdog`, or `tools/fn-init` — those are
  separate plans.
- Do NOT add a `Co-Authored-By` trailer to any commit message.

---

### Task 1: The response-header filter deduplicates case-insensitively (Java + Python)

**Why:** `filterAllowedHeaders` compares case-insensitively but writes the original key, with no
dedupe. A handler returning both `"Content-Type"` and `"content-type"` gets both through as distinct
map entries, and the response then carries two colliding header entries. Both call sites also compute
their "which headers were dropped" WARN list from the allow-list rather than from what actually
survived, so a deduplicated key would be counted as dropped but never named.

**Files:**
- Modify: `platform/common/src/main/java/it/unimib/datai/nanofaas/common/runtime/ResponseHeaderPolicy.java:19-30`
- Modify: `sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeController.java:156-167`
- Modify: `sdks/python/src/nanofaas/runtime/app.py:152-159`
- Test: `platform/common/src/test/java/it/unimib/datai/nanofaas/common/runtime/ResponseHeaderPolicyTest.java`
- Test: `sdks/python/tests/test_runtime.py`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `ResponseHeaderPolicy.filterAllowedHeaders(Map<String,String>)` keeps its exact signature
  and return type (`Map<String,String>`); its contract gains "at most one entry per header name,
  compared case-insensitively; first occurrence in iteration order wins; the surviving key keeps its
  original casing". Python's `_filter_response_headers(raw, execution_id=None) -> dict[str,str]`
  keeps its exact signature and gains the identical contract.

- [ ] **Step 1: Write the failing Java test**

Add to `platform/common/src/test/java/it/unimib/datai/nanofaas/common/runtime/ResponseHeaderPolicyTest.java`:

```java
    @Test
    void filterAllowedHeaders_dedupesCaseInsensitivelyKeepingFirstOccurrence() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("Content-Type", "application/pdf");
        raw.put("content-type", "text/plain");

        Map<String, String> filtered = ResponseHeaderPolicy.filterAllowedHeaders(raw);

        assertEquals(1, filtered.size(), "colliding casings must collapse to one entry");
        assertEquals("application/pdf", filtered.get("Content-Type"), "first occurrence wins");
    }

    @Test
    void filterAllowedHeaders_preservesOriginalCasingOfSurvivors() {
        Map<String, String> filtered = ResponseHeaderPolicy.filterAllowedHeaders(
                Map.of("Content-Type", "application/pdf"));

        assertEquals(Map.of("Content-Type", "application/pdf"), filtered,
                "original casing is part of the public InvocationResponse.headers contract");
    }
```

Add the import `java.util.LinkedHashMap` to the test file if it is not already present.

- [ ] **Step 2: Run the Java test to verify it fails**

Run: `./gradlew :common:test --tests "*ResponseHeaderPolicyTest"`
Expected: FAIL — `filterAllowedHeaders_dedupesCaseInsensitivelyKeepingFirstOccurrence` reports
`expected: <1> but was: <2>`. The second test should already PASS (it pins behavior that must not
regress).

- [ ] **Step 3: Implement the Java dedupe**

Replace the body of `filterAllowedHeaders` in
`platform/common/src/main/java/it/unimib/datai/nanofaas/common/runtime/ResponseHeaderPolicy.java`:

```java
    /**
     * Filters a handler-supplied response header map down to the allow-list.
     *
     * <p>At most one entry survives per header name, compared case-insensitively: HTTP header names
     * are case-insensitive, so emitting both {@code Content-Type} and {@code content-type} would put
     * two colliding entries on the response. The first occurrence in iteration order wins and keeps
     * its original casing — that casing is part of the public {@code InvocationResponse.headers}
     * contract and callers read it.
     */
    public static Map<String, String> filterAllowedHeaders(Map<String, String> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String lowerKey = entry.getKey().toLowerCase();
            if (ALLOWED_RESPONSE_HEADERS.contains(lowerKey) && seen.add(lowerKey)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }
```

Add `import java.util.HashSet;` to the file's imports.

- [ ] **Step 4: Run the Java test to verify it passes**

Run: `./gradlew :common:test --tests "*ResponseHeaderPolicyTest"`
Expected: PASS, all tests in the class.

- [ ] **Step 5: Fix the Java WARN to name what was actually dropped**

`warnOnDroppedHeaders` currently derives `dropped` from the allow-list, so a deduplicated key makes
the size check fire while the name list comes back empty — a WARN that logs nothing useful, or no
WARN at all. Compute it from the survivors instead. In
`sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeController.java`, replace the
method body:

```java
    private void warnOnDroppedHeaders(Map<String, String> rawHeaders, Map<String, String> allowedHeaders,
                                       String executionId) {
        if (rawHeaders == null || rawHeaders.size() == allowedHeaders.size()) {
            return;
        }
        List<String> dropped = rawHeaders.keySet().stream()
                .filter(key -> !allowedHeaders.containsKey(key))
                .toList();
        if (!dropped.isEmpty()) {
            log.warn("Dropped response header(s) {} for execution {}", dropped, executionId);
        }
    }
```

Note the message changed from "Dropped disallowed response header(s)" to "Dropped response
header(s)" — a deduplicated header is not disallowed, so the old wording would be wrong. The existing
test `invoke_handlerReturnsHandlerResponse_dropsDisallowedHeaders` asserts on the dropped header name
and execution id, not on the word "disallowed"; verify that when you run it in Step 7 and adjust the
assertion only if it actually matches on that word.

- [ ] **Step 6: Write the failing Python test**

Add to `sdks/python/tests/test_runtime.py`, next to the existing response-header tests:

```python
def test_filter_response_headers_dedupes_case_insensitively():
    from nanofaas.runtime.app import _filter_response_headers

    filtered = _filter_response_headers({"Content-Type": "application/pdf", "content-type": "text/plain"})

    assert filtered == {"Content-Type": "application/pdf"}, "first occurrence wins, original casing kept"


def test_filter_response_headers_preserves_original_casing():
    from nanofaas.runtime.app import _filter_response_headers

    assert _filter_response_headers({"Content-Type": "application/pdf"}) == {"Content-Type": "application/pdf"}
```

- [ ] **Step 7: Run the Python test to verify it fails**

Run: `cd sdks/python && uv run pytest tests/test_runtime.py -k "filter_response_headers" -v`
Expected: FAIL — `test_filter_response_headers_dedupes_case_insensitively` gets a dict with two keys.

- [ ] **Step 8: Implement the Python dedupe**

Replace `_filter_response_headers` in `sdks/python/src/nanofaas/runtime/app.py`:

```python
def _filter_response_headers(raw: dict[str, str] | None, execution_id: str | None = None) -> dict[str, str]:
    """Filter handler-supplied response headers down to the allow-list.

    At most one entry survives per header name, compared case-insensitively: HTTP header names are
    case-insensitive, so emitting both ``Content-Type`` and ``content-type`` would put two colliding
    entries on the response. The first occurrence wins and keeps its original casing, which is part
    of the public ``InvocationResponse.headers`` contract. Mirrors
    ``ResponseHeaderPolicy.filterAllowedHeaders`` in platform/common — keep the two in sync.
    """
    if not raw:
        return {}
    allowed: dict[str, str] = {}
    seen: set[str] = set()
    for key, value in raw.items():
        lower_key = key.lower()
        if lower_key in _ALLOWED_RESPONSE_HEADERS and lower_key not in seen:
            seen.add(lower_key)
            allowed[key] = value
    if len(allowed) != len(raw):
        dropped = [k for k in raw if k not in allowed]
        logger.warning(f"Dropped response header(s) {dropped} for execution {execution_id}")
    return allowed
```

Note `dropped` now derives from the survivors (`k not in allowed`) rather than from the allow-list,
so a deduplicated key is named too, matching the Java side.

- [ ] **Step 9: Run the Python tests to verify they pass**

Run: `cd sdks/python && uv run pytest tests/ -v`
Expected: PASS — all tests, including the pre-existing
`test_invoke_handler_response_dropped_headers_log_warn_but_still_succeed`. If that test asserts on the
literal word "disallowed" in the log message, update it to match the new wording; it must still assert
the dropped header name, the execution id, and that the invocation returned 200.

- [ ] **Step 10: Run the affected Java suites**

Run: `./gradlew :common:test :sdks:java:test :control-plane:test`
Expected: PASS. `ExternalDispatcherTest`, `InvocationControllerTest` and `InvocationResponseMapperTest`
all assert exact `"Content-Type"` casing on maps that flow through `filterAllowedHeaders` — they must
stay green, which is what proves the casing contract was preserved.

- [ ] **Step 11: Commit**

```bash
git add platform/common/src/main/java/it/unimib/datai/nanofaas/common/runtime/ResponseHeaderPolicy.java \
        platform/common/src/test/java/it/unimib/datai/nanofaas/common/runtime/ResponseHeaderPolicyTest.java \
        sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeController.java \
        sdks/python/src/nanofaas/runtime/app.py \
        sdks/python/tests/test_runtime.py
git commit -m "fix: dedupe response headers case-insensitively in both runtimes"
```

---

### Task 2: The shared-contract tests fail when the envelope wrapping is reverted

**Why:** both tests branch on the return type, so they pass whether or not the handler wraps its error
returns in an envelope. The Java one never asserts `expectedStatusCode` at all; the Python one asserts
it only inside the `isinstance` branch, which a revert skips entirely. They read as regression guards
and are not.

The shared fixture `functions/test-data/roman-numeral/correctness.json` already carries
`"expectedStatusCode": 422` on its four error cases and `200` is absent on the success case (so
`.get("expectedStatusCode", 200)` defaults correctly). Five other language implementations read the
same file and must keep passing — do **not** edit the fixture.

**Files:**
- Modify: `functions/java/roman-numeral/src/test/java/it/unimib/datai/nanofaas/examples/romannumeral/RomanNumeralHandlerTest.java:20-36`
- Modify: `functions/python/roman-numeral/tests/test_handler.py:76-82`

**Interfaces:**
- Consumes: `HandlerResponse(Object output, int statusCode, Map<String,String> headers, String encoding)`
  from `it.unimib.datai.nanofaas.common.runtime` (Java) and the frozen dataclass
  `HandlerResponse(output, status_code, headers, encoding)` from `nanofaas.sdk` (Python), both already
  shipped in milestone 1.
- Produces: nothing other tasks consume.

- [ ] **Step 1: Rewrite the Java contract test so a revert fails it**

Replace `satisfiesSharedContract` in
`functions/java/roman-numeral/src/test/java/it/unimib/datai/nanofaas/examples/romannumeral/RomanNumeralHandlerTest.java`:

```java
    @Test
    @SuppressWarnings("unchecked")
    void satisfiesSharedContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode cases = mapper.readTree(Path.of("../..", "test-data", "roman-numeral", "correctness.json").toFile()).get("cases");
        for (JsonNode contractCase : cases) {
            String name = contractCase.get("name").asText();
            Object input = mapper.convertValue(contractCase.get("input"), Object.class);
            Object result = handler.handle(new InvocationRequest(input, null));

            int expectedStatus = contractCase.has("expectedStatusCode")
                    ? contractCase.get("expectedStatusCode").asInt()
                    : 200;

            Map<String, Object> actual;
            if (expectedStatus == 200) {
                assertFalse(result instanceof HandlerResponse,
                        name + ": a 200 case must return a plain value, not an envelope");
                actual = (Map<String, Object>) result;
            } else {
                assertInstanceOf(HandlerResponse.class, result,
                        name + ": a non-200 case must return a HandlerResponse envelope");
                HandlerResponse response = (HandlerResponse) result;
                assertEquals(expectedStatus, response.statusCode(), name + ": status code");
                actual = (Map<String, Object>) response.output();
            }

            JsonNode expected = contractCase.get("expected");
            String key = expected.has("error") ? "error" : "roman";
            assertEquals(expected.get(key).asText(), actual.get(key), name);
        }
    }
```

The change that matters: the expected shape is now driven by the **fixture**, not by what the handler
happened to return. Reverting the envelope wrapping makes the `assertInstanceOf` fail on all four
error cases.

No import changes are needed: the file already has `import static org.junit.jupiter.api.Assertions.*;`,
`import it.unimib.datai.nanofaas.common.runtime.HandlerResponse;`, `java.util.Map` and `java.nio.file.Path`,
and declares the `handler` field as `private final RomanNumeralHandler handler = new RomanNumeralHandler();`.
Note this module uses Jackson 3 — `tools.jackson.databind.ObjectMapper`, already imported.

- [ ] **Step 2: Verify the Java test genuinely discriminates**

Temporarily revert one error return in
`functions/java/roman-numeral/src/main/java/it/unimib/datai/nanofaas/examples/romannumeral/RomanNumeralHandler.java`
— change `return HandlerResponse.of(Map.of(ERROR_KEY, "missing required field: number"), 422);` back to
`return Map.of(ERROR_KEY, "missing required field: number");`.

Run: `./gradlew :functions:java:roman-numeral:test --tests "*RomanNumeralHandlerTest"`
Expected: FAIL on the "rejects missing number" case with "must return a HandlerResponse envelope".

Then restore the line exactly and re-run.
Expected: PASS.

This step is the whole point of the task — do not skip it, and record both outputs in your report.

- [ ] **Step 3: Rewrite the Python contract test so a revert fails it**

Replace `test_shared_contract` in `functions/python/roman-numeral/tests/test_handler.py`:

```python
@pytest.mark.parametrize("contract_case", SHARED_CASES, ids=lambda case: case["name"])
def test_shared_contract(contract_case):
    result = _invoke(contract_case["input"])
    expected_status = contract_case.get("expectedStatusCode", 200)

    if expected_status == 200:
        assert not isinstance(result, HandlerResponse), "a 200 case must return a plain value"
        actual = result
    else:
        assert isinstance(result, HandlerResponse), "a non-200 case must return a HandlerResponse envelope"
        assert result.status_code == expected_status
        actual = result.output

    assert actual == contract_case["expected"]
```

- [ ] **Step 4: Verify the Python test genuinely discriminates**

Temporarily revert one error return in `functions/python/roman-numeral/handler.py` — change
`return HandlerResponse(output={"error": "missing required field: number"}, status_code=422)` back to
`return {"error": "missing required field: number"}`.

Run: `cd functions/python/roman-numeral && uv run pytest tests/test_handler.py -v`
Expected: FAIL on the "rejects missing number" case with "must return a HandlerResponse envelope".

Then restore the line exactly and re-run.
Expected: PASS, 27 tests.

Record both outputs in your report.

- [ ] **Step 5: Confirm the out-of-scope implementations are still inert**

Run: `./functions/contract-tests/run.sh`
Expected: exit 0. This is the only gate that exercises the five implementations that were NOT
converted (Java-lite, Go, JavaScript, bash, roman-numeral-lite) against the same fixture. You changed
no fixture and no out-of-scope source, so it must stay green; if it does not, stop and report rather
than converting another language.

- [ ] **Step 6: Commit**

```bash
git add functions/java/roman-numeral/src/test/java/it/unimib/datai/nanofaas/examples/romannumeral/RomanNumeralHandlerTest.java \
        functions/python/roman-numeral/tests/test_handler.py
git commit -m "test: drive shared-contract expectations from the fixture, not the return type"
```

---

### Task 3: The out-of-range status guard on the marker path is covered by a test

**Why:** when a marker-bearing response carries a status outside `[200,599]`, `ExternalDispatcher`
falls through to the platform-error path. That behavior is correct and fails safe, but nothing tests
it. The original justification — "unreachable via real HTTP responses" — is overstated: HTTP's
status-line grammar is `3DIGIT`, not semantically bounded to ≤599, and `EXTERNAL`/`DEPLOYMENT`
endpoints are arbitrary URLs with no authentication.

**Files:**
- Test: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcherTest.java`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcher.java:82-85` (comment only)

**Interfaces:**
- Consumes: `ResponseHeaderPolicy.isStatusCodeValid(int)` returning true for `[200,599]` inclusive;
  `DispatchResult(InvocationResult result, boolean coldStart, Long initDurationMs)`;
  `InvocationResult.error(String code, String message)` producing `success == false`.
- Produces: nothing other tasks consume.

- [ ] **Step 1: Write the failing test**

Add to `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcherTest.java`.

This file has **no shared fixtures**: every test builds its own `MockWebServer`, `FunctionSpec`,
`InvocationTask` and `ExternalDispatcher` as locals, uses `addHeader`/`setResponseCode` (not
`setHeader`/`setStatus`), drives the dispatch with `.get()` (not `.block()`), and calls
`server.shutdown()` at the end. Follow that shape exactly:

```java
    @Test
    void dispatch_functionStatusMarkerWithOutOfRangeStatus_isPlatformErrorNotPassthrough() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(999)
                .setBody("{\"ignored\":true}")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-NanoFaaS-Function-Status", "true"));
        server.start();

        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "oor-fn", "image", null, Map.of(), null, 1000, 1, 10, 3,
                endpoint, ExecutionMode.EXTERNAL, null, null, null
        );
        InvocationTask task = new InvocationTask(
                "exec-oor", "oor-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertFalse(dr.result().success(),
                "an out-of-range status must not be trusted as a function decision");
        assertEquals("EXTERNAL_ERROR", dr.result().error().code());
        assertNull(dr.result().statusCode(),
                "the illegal status must never be propagated to the caller");
        server.shutdown();
    }
```

- [ ] **Step 2: Run the test to verify it fails or passes, and say which**

Run: `./gradlew :control-plane:test --tests "*ExternalDispatcherTest"`

Expected: **PASS.** This is a characterization test for behavior that is already correct — unlike
Tasks 1 and 2 there is no red phase, because the guard exists and works; only its coverage is missing.

If it FAILS, that is a genuine finding, not a test bug: it means an out-of-range marker-bearing status
is being trusted or propagated. Stop, report it, and do not "fix" the test to match the behavior.

If `MockWebServer` rejects `setResponseCode(999)`, or Reactor Netty refuses to parse it, report that
plainly rather than weakening the test — an alternative is a Mockito-stubbed `ClientResponse`
returning `HttpStatusCode.valueOf(999)`, which exercises the same branch without the HTTP layer.
Do not substitute an in-range status to make the test pass; that would test nothing.

- [ ] **Step 3: Replace the `ponytail:` comment with one that reflects the settled decision**

In `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcher.java`,
replace:

```java
                        // ponytail: out-of-range status code from a marker-bearing response is not
                        // spec-legal (brief is silent) — fall through and treat as a platform error.
```

with:

```java
                        // An out-of-range status on a marker-bearing response is not spec-legal
                        // ([200,599] only). Fall through to the platform-error path rather than
                        // trusting it: EXTERNAL/DEPLOYMENT endpoints are arbitrary unauthenticated
                        // URLs, and HTTP's status-line grammar is 3DIGIT, so a misbehaving upstream
                        // can emit one. Covered by
                        // dispatch_functionStatusMarkerWithOutOfRangeStatus_isPlatformErrorNotPassthrough.
```

- [ ] **Step 4: Run the module suite**

Run: `./gradlew :control-plane:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcherTest.java \
        platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcher.java
git commit -m "test: cover the out-of-range status guard on the marker dispatch path"
```

---

### Final verification

- [ ] **Step 1: Full Java suite**

Run: `./gradlew test --no-parallel --continue`
Expected: only `:nanofaas-cli RootCommandTest.versionComesFromTheBuild` fails — the known
pre-existing, unrelated failure. Anything else is a regression from this plan.

- [ ] **Step 2: Full Python suites**

Run: `cd sdks/python && uv run pytest tests/ -v`
Expected: PASS.

Run: `cd functions/python/roman-numeral && uv run pytest tests/ -v`
Expected: PASS, 27 tests.

- [ ] **Step 3: Cross-language contract gate**

Run: `./functions/contract-tests/run.sh`
Expected: exit 0.

## Self-Review Notes

- **Spec coverage:** all three deferred items from issue #193's "Follow-up minori non correlati"
  section have a task — casing collision (Task 1), type-branching contract tests (Task 2), untested
  `isStatusCodeValid` guard (Task 3). Nothing else from #193 is in scope for this plan.
- **The casing decision was made deliberately, not by default.** Lower-casing the surviving keys is
  the more canonical fix, but `InvocationResponse.headers` is a public API field and
  `InvocationControllerTest:242` asserts `$.headers['Content-Type']` in the response body — so
  lower-casing would be an observable break for every consumer in exchange for no functional gain.
  The HTTP/2-mandates-lowercase argument does not apply at this hop, where the map is a JSON payload
  field rather than real HTTP headers. Dedupe-preserving-casing is the smaller correct fix.
- **Task 1 keeps Java and Python in one task on purpose.** They are mirrors of the same policy; a
  reviewer needs to see both to verify they stayed in sync, and splitting them invites drift.
- **Task 3 has no red phase and the plan says so explicitly**, so an implementer does not "fix" a
  passing test into a failing one to satisfy a TDD ritual. Tasks 1 and 2 do have real red phases, and
  Task 2's steps 2 and 4 require actually reverting production code to prove the tests discriminate —
  that is the defect being repaired, so asserting it without demonstrating it would repeat the
  original mistake.
