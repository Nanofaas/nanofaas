# SDK Function Parity Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Give all five SDKs the same three reference functions and converge their HTTP runtime behavior on the control-plane contract.

**Architecture:** Use native handlers generated from `fn-init` scaffolds and validate them against shared JSON fixtures. Fix runtime parity one SDK at a time behind focused contract tests, using the Java callback/control-plane models as the wire-contract reference rather than sharing implementation code across languages.

**Tech Stack:** Java 21, Java Lite/GraalVM, Go 1.24, Python 3.11+/FastAPI, Node.js 20+/TypeScript, JUnit 5, pytest, Go test, Node test runner.

---

### Task 1: Add JavaScript `roman-numeral`

**Files:**
- Create: `functions/javascript/roman-numeral/src/handler.ts`
- Create: `functions/javascript/roman-numeral/src/index.ts`
- Create: `functions/javascript/roman-numeral/test/handler.test.ts`
- Create: `functions/javascript/roman-numeral/{package.json,package-lock.json,tsconfig.json,Dockerfile,function.yaml}`
- Create: `functions/javascript/roman-numeral/payloads/{happy-path.json,missing-input.json}`

1. Generate a JavaScript scaffold with `./scripts/fn-init.sh roman-numeral --lang javascript --out <staging> --yes`.
2. Write tests for 1, 4, 9, 42, 1994, 3999, missing `number`, non-integer input, and out-of-range input.
3. Run `npm test` and confirm RED because the function directory/handler is absent.
4. Implement the standard greedy Roman numeral conversion and validation.
5. Run `npm test` and confirm GREEN.

### Task 2: Add Java Lite `roman-numeral`

**Files:**
- Create: `functions/java/roman-numeral-lite/src/main/java/it/unimib/datai/nanofaas/examples/romannumerallite/RomanNumeralLite.java`
- Create: `functions/java/roman-numeral-lite/src/test/java/it/unimib/datai/nanofaas/examples/romannumerallite/RomanNumeralLiteTest.java`
- Create: `functions/java/roman-numeral-lite/{build.gradle,Dockerfile,function.yaml,settings.gradle.docker}`
- Modify: `settings.gradle`

1. Generate a Java scaffold with `./scripts/fn-init.sh roman-numeral --lang java --out <staging> --yes`.
2. Write the same behavior tests used by the full Java implementation.
3. Run `./gradlew :functions:java:roman-numeral-lite:test` and confirm RED because the module is not implemented.
4. Adapt the scaffold to `NanofaasRuntime.builder()` and implement the minimal greedy converter.
5. Run the focused test and existing Java Lite SDK tests; confirm GREEN.

### Task 3: Align JavaScript `json-transform`

**Files:**
- Modify: `functions/javascript/json-transform/src/handler.ts`
- Modify: `functions/javascript/json-transform/test/handler.test.ts`

1. Add tests using `tools/controlplane/scenarios/payloads/json-transform-sample.json`.
2. Confirm RED: the current field-remapping implementation rejects the array payload.
3. Replace field remapping with `groupBy` plus `count|sum|avg|min|max` aggregation.
4. Run both JavaScript function suites.

### Task 4: Fix JavaScript runtime validation and callback parity

**Files:**
- Modify: `sdks/javascript/src/runtime.ts`
- Modify: `sdks/javascript/src/types.ts`
- Test: `sdks/javascript/test/runtime.request-validation.test.ts`
- Test: `sdks/javascript/test/runtime.callback.test.ts`

1. Preserve the existing three failing validation tests as RED evidence.
2. Map JSON parse failures to `INVALID_JSON` and reject non-string metadata with `INVALID_REQUEST`.
3. Add callback retry classification, dispatch-attempt forwarding, and bounded shutdown-aware dispatch.
4. Run `npm test` until all SDK tests pass.

### Task 5: Fix Go callback contract

**Files:**
- Modify: `sdks/go/nanofaas/types.go`
- Modify: `sdks/go/nanofaas/callback_client.go`
- Modify: `sdks/go/nanofaas/http_invoke.go`
- Test: `sdks/go/nanofaas/*_test.go`

1. Add a serialization test requiring `success`, `output`, and `error` fields.
2. Confirm RED because `InvocationResult` omits `success`.
3. Add the field and forward `X-Dispatch-Attempt` through the dispatcher and client.
4. Run `go test ./...`.

### Task 6: Fix Python runtime parity

**Files:**
- Modify: `sdks/python/src/nanofaas/runtime/app.py`
- Test: `sdks/python/tests/test_runtime.py`

1. Add RED tests for malformed JSON, timeout, trace environment fallback, retryable/permanent callback status, bounded callback submission, and dispatch-attempt forwarding.
2. Implement the minimum async-safe timeout and callback dispatcher behavior.
3. Run `uv run --extra test pytest tests -q`.

### Task 7: Fix Java Lite runtime parity

**Files:**
- Modify: `sdks/java-lite/src/main/java/it/unimib/datai/nanofaas/sdk/lite/handler/InvokeHandler.java`
- Modify: `sdks/java-lite/src/main/java/it/unimib/datai/nanofaas/sdk/lite/callback/CallbackClient.java`
- Modify: `sdks/java-lite/src/main/java/it/unimib/datai/nanofaas/sdk/lite/NanofaasRuntime.java`
- Test: corresponding `sdks/java-lite/src/test/java/**` files

1. Add RED tests for timeout, malformed JSON, authoritative callback URL construction, permanent `4xx`, bounded callback dispatch, and serialization failure.
2. Implement one bounded executor, request-arrival cold-start timing, and consistent callback semantics.
3. Run `./gradlew :sdks:java-lite:test`.

### Task 8: Complete Java runtime observability parity

**Files:**
- Modify: `sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/InvocationRuntimeContextResolver.java`
- Modify: `sdks/java/src/main/java/it/unimib/datai/nanofaas/sdk/runtime/TraceLoggingFilter.java`
- Add or modify runtime metric configuration and tests under `sdks/java/src/test/java/**`.

1. Add RED tests for `TRACE_ID` fallback and the common runtime metrics.
2. Implement fallback and counters without changing the handler API.
3. Run `./gradlew :sdks:java:test`.

### Task 9: Add the cross-SDK parity gate

**Files:**
- Create: shared fixture definitions under `functions/contract-tests/`
- Modify: CI/build orchestration only where needed to execute each native test command.

1. Add fixtures for all three function families, including validation edges and deterministic tie ordering.
2. Run every implementation against the same fixtures.
3. Run the complete SDK and function test matrix.
4. Run `gitnexus_detect_changes(scope: "all")` and verify only planned SDK/function flows changed.
