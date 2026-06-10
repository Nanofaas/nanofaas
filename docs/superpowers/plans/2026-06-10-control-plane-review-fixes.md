# Control-Plane Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 8 bugs, apply the simplifications, and land the performance improvements identified in the 2026-06-10 deep review of the `control-plane` module.

**Architecture:** All changes stay inside `control-plane/` (core module) plus one doc fix in `CLAUDE.md`. The plan is ordered: correctness bugs first (reactive-path future cancellation, event-loop blocking, store eviction), then dead-code removal, then performance refactors. Each task is independently shippable and keeps the public HTTP API behavior identical except where a bug made it wrong (documented per task).

**Tech Stack:** Java 21, Spring Boot WebFlux (Reactor 3.7.x), Micrometer, JUnit 5 + Mockito + AssertJ, Gradle.

---

## Conventions for every task

- Work on a feature branch (e.g. `fix/control-plane-review-fixes`), created via `superpowers:using-git-worktrees` if isolation is needed.
- Full core test suite: `./scripts/controlplane.sh test --profile core`
- Single test class: `./scripts/controlplane.sh test --profile core -- --tests <FQCN>`
- Package root is `it.unimib.datai.nanofaas` (NOT `com.nanofaas`). 4-space indentation.
- All paths below are relative to the repo root `/Users/micheleciavotta/Downloads/mcFaas`. Source root abbreviation used below: `CP = control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane`, `CPT = control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane`.
- Per project CLAUDE.md: if GitNexus MCP tools are available, run `gitnexus_impact({target, direction: "upstream"})` before editing a symbol and `gitnexus_detect_changes()` before each commit. If the MCP tools are not available in your session, skip them (a PostToolUse hook re-runs `npx gitnexus analyze` after commits).
- Commit after every task (granular commits).

## Background: review findings being fixed

| ID | Finding | Task |
|----|---------|------|
| B1 | `Mono.fromFuture(record.completion())` + `.timeout()` cancels the **shared** CompletableFuture → concurrent idempotent waiters get `CancellationException`/500 | 1 |
| B3 | Exceptional future completion mapped to "timeout" instead of "error" | 2 |
| S2 | Blocking `invokeSync` + `SyncInvocationCoordinator` duplicate the reactive path and are used only by tests | 2 |
| S1 | Admission block (`isNew → enqueue → publish/abandon`) copy-pasted 3× | 2 |
| B2 | `parkPendingClaim()` busy-waits on the Netty event loop; sync controller methods also run blocking-ish work on the event loop | 3 |
| B4 | `ExecutionRecord.canTransition` never consults `ALLOWED_TRANSITIONS` for non-terminal states — the map is dead code | 4 |
| B6 | `IdempotencyStore.getExecutionId` does an unconditional `remove` on TTL expiry (can delete a freshly re-published key) | 5 |
| B5 | `ExecutionStore` never evicts non-terminal records → slow leak | 6 |
| B7 | `ExecutionStore` janitor thread is non-daemon and unnamed | 6 |
| I1 | `ExecutionStore` TTLs hardcoded | 6 |
| I2 | `ExecutionRecord.cleanup()` re-allocates a task every janitor sweep | 6 |
| B8 | `RateLimiter` window check `now != currentWindow` (not monotonic); CLAUDE.md documents rate default 1000 vs actual 1000000 | 7 |
| S3 | Deprecated `ExecutionRecord` setters used only by a legacy test | 8 |
| S4 | `IdempotencyStore.putIfAbsent`/`replaceExecutionId` used only by tests | 9 |
| S5 | `FunctionService.register` no-op `try/catch { throw e; }` | 10 |
| S7 | `PoolDispatcher` redundant locals + inline header parsing | 10 |
| S8 | `GlobalExceptionHandler` duplicate binding-error handlers | 10 |
| P1 | `Metrics` does 4–6 `ConcurrentHashMap` lookups per invocation across 10 maps | 11 |
| P2 | `releasedDispatchAttempts` global `synchronizedMap(WeakHashMap)` | 12 |
| P4 | `completeExecution` holds the record monitor across Micrometer recording and `complete()` callbacks | 13 |
| P3 | `UUID.randomUUID()` (SecureRandom) on the hot path | 14 |
| I3 | `PoolDispatcher` per-function timeout capped by global WebClient `responseTimeout`; 1 MB codec default too small | 15 |
| I4 | Hand-rolled TTL store + janitor in `IdempotencyStore` → Caffeine | 16 (optional) |

## Out of scope (deliberate)

- **`DispatcherRouter` removal**: it is a trivial pass-through, but 5 test files mock it; churn outweighs value.
- **Virtual threads / `ReentrantLock` migration in `ExecutionRecord`**: no virtual-thread executor exists in this codebase today; revisit if one is introduced.
- **`Snapshot.dispatchedAt` removal**: only read by tests, but it is cheap and useful diagnostic data; kept.
- **`Vector` or other legacy collections**: explicitly rejected — current `ConcurrentHashMap`/`EnumMap` usage is correct.

---

### Task 1: B1 — stop cancelling the shared completion future (reactive path)

**Files:**
- Modify: `CP/service/ReactiveInvocationCoordinator.java:62`
- Test: create `CPT/service/ReactiveInvocationCoordinatorTest.java`

- [ ] **Step 1: Write the failing test**

Create `CPT/service/ReactiveInvocationCoordinatorTest.java`:

```java
package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReactiveInvocationCoordinatorTest {

    private final ExecutionStore executionStore = new ExecutionStore();
    private final IdempotencyStore idempotencyStore = new IdempotencyStore();
    private final InvocationExecutionFactory factory =
            new InvocationExecutionFactory(executionStore, idempotencyStore);
    private final Metrics metrics = new Metrics(new SimpleMeterRegistry());
    private final ExecutionCompletionHandler completionHandler = mock(ExecutionCompletionHandler.class);
    private final ReactiveInvocationCoordinator coordinator =
            new ReactiveInvocationCoordinator(null, metrics, null, completionHandler, new InvocationResponseMapper());

    @Test
    void clientTimeoutDoesNotCancelSharedCompletionFuture() {
        FunctionSpec spec = spec("fn-cancel");
        InvocationExecutionFactory.ExecutionLookup lookup =
                factory.createOrReuseExecution("fn-cancel", spec, new InvocationRequest("payload", Map.of()), null, null);

        InvocationResponse response = coordinator.invoke(lookup, spec, 50).block();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("timeout");
        // The shared future must survive a single client's timeout: other idempotent
        // waiters and the completion callback still depend on it.
        assertThat(lookup.record().completion().isCancelled()).isFalse();
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./scripts/controlplane.sh test --profile core -- --tests it.unimib.datai.nanofaas.controlplane.service.ReactiveInvocationCoordinatorTest`
Expected: FAIL on `isCancelled()).isFalse()` (the `.timeout()` operator cancels `Mono.fromFuture`, which cancels the future).

- [ ] **Step 3: Apply the fix**

In `CP/service/ReactiveInvocationCoordinator.java`, change:

```java
        return Mono.fromFuture(record.completion())
```

to:

```java
        // suppressCancel=true: a single subscriber's timeout/disconnect must not cancel
        // the shared completion future other idempotent waiters depend on.
        return Mono.fromFuture(record.completion(), true)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./scripts/controlplane.sh test --profile core -- --tests it.unimib.datai.nanofaas.controlplane.service.ReactiveInvocationCoordinatorTest`
Expected: PASS

- [ ] **Step 5: Run the full core suite, then commit**

Run: `./scripts/controlplane.sh test --profile core`

```bash
git add control-plane/src
git commit -m "fix(control-plane): don't cancel shared completion future on client timeout"
```

---

### Task 2: S2 + B3 + S1 — remove the blocking sync path, handle exceptional completion as error

The blocking `InvocationService.invokeSync` / `SyncInvocationCoordinator` duplicate the reactive path and are used **only by tests** (verified by grep). Removing them also removes bug B3 (exceptional completion mapped to "timeout"); the reactive coordinator gains explicit error handling instead. The triplicated admission block is extracted into a shared helper.

**Files:**
- Delete: `CP/service/SyncInvocationCoordinator.java`
- Modify: `CP/service/InvocationService.java` (remove `invokeSync`, `syncCoordinator` field and ctor params)
- Modify: `CP/service/ReactiveInvocationCoordinator.java` (admission helper + error completion handling)
- Modify: `CP/service/InvocationEnqueueSupport.java` (host the shared admission helper)
- Test: add cases to `CPT/service/ReactiveInvocationCoordinatorTest.java`
- Test (migrate): `CPT/service/InvocationServiceDispatchTest.java`, `CPT/service/InvocationServiceRetryTest.java` and any other test calling `.invokeSync(` (find with grep)

- [ ] **Step 1: Write the failing test for B3 (exceptional completion → error, not timeout)**

Add to `CPT/service/ReactiveInvocationCoordinatorTest.java`:

```java
    @Test
    void exceptionalCompletionYieldsErrorResponseNotTimeout() {
        FunctionSpec spec = spec("fn-boom");
        InvocationExecutionFactory.ExecutionLookup lookup =
                factory.createOrReuseExecution("fn-boom", spec, new InvocationRequest("p", Map.of()), null, null);
        lookup.record().completion().completeExceptionally(new RuntimeException("boom"));

        InvocationResponse response = coordinator.invoke(lookup, spec, 1000).block();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("error");
        assertThat(response.error().code()).isEqualTo("EXECUTION_FAILED");
    }
```

(Needs imports: none beyond Task 1's.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./scripts/controlplane.sh test --profile core -- --tests it.unimib.datai.nanofaas.controlplane.service.ReactiveInvocationCoordinatorTest`
Expected: FAIL — the RuntimeException propagates out of `block()` (no handler for it).

- [ ] **Step 3: Extract the shared admission helper**

In `CP/service/InvocationEnqueueSupport.java` add (keep the existing `enqueueOrThrow`):

```java
    /**
     * Runs the admission flow for a freshly created execution: enqueue/dispatch, then
     * publish the idempotency claim; on failure abandon the claim and rethrow.
     * No-op when the lookup is a replay of an existing execution.
     */
    static void admitIfNew(InvocationExecutionFactory.ExecutionLookup lookup,
                           Runnable admissionAction) {
        if (!lookup.isNew()) {
            return;
        }
        try {
            admissionAction.run();
            lookup.publishAdmission();
        } catch (RuntimeException ex) {
            lookup.abandonAdmission();
            throw ex;
        }
    }
```

- [ ] **Step 4: Rewrite `ReactiveInvocationCoordinator.invoke` to use the helper and handle errors**

Replace the body of `invoke` in `CP/service/ReactiveInvocationCoordinator.java` with:

```java
    public Mono<InvocationResponse> invoke(InvocationExecutionFactory.ExecutionLookup lookup,
                                           FunctionSpec spec,
                                           Integer timeoutOverrideMs) {
        ExecutionRecord record = lookup.record();
        InvocationResponse replay = responseMapper.terminalResponse(record);
        if (replay != null) {
            return Mono.just(replay);
        }

        try {
            InvocationEnqueueSupport.admitIfNew(lookup, () -> {
                if (syncQueueGateway.enabled()) {
                    syncQueueGateway.enqueueOrThrow(record.task());
                } else if (enqueuer.enabled()) {
                    InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, record);
                } else {
                    completionHandler.dispatch(record.task());
                }
            });
        } catch (RuntimeException ex) {
            return Mono.error(ex);
        }

        int timeoutMs = timeoutOverrideMs == null ? spec.timeoutMs() : timeoutOverrideMs;
        // suppressCancel=true: a single subscriber's timeout/disconnect must not cancel
        // the shared completion future other idempotent waiters depend on.
        return Mono.fromFuture(record.completion(), true)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(result -> {
                    if (result.error() != null && "QUEUE_TIMEOUT".equals(result.error().code())) {
                        throw new SyncQueueRejectedException(SyncQueueRejectReason.TIMEOUT, syncQueueGateway.retryAfterSeconds());
                    }
                    return responseMapper.toResponse(record, result);
                })
                .onErrorResume(java.util.concurrent.TimeoutException.class, ex -> {
                    record.markTimeout();
                    metrics.timeout(record.task().functionName());
                    return Mono.just(responseMapper.timeoutResponse(record));
                })
                .onErrorResume(ex -> !(ex instanceof SyncQueueRejectedException), ex -> {
                    InvocationResult failure = InvocationResult.error("EXECUTION_FAILED", ex.getMessage());
                    record.markError(failure.error());
                    metrics.error(record.task().functionName());
                    return Mono.just(responseMapper.toResponse(record, failure));
                });
    }
```

- [ ] **Step 5: Remove the blocking path from `InvocationService`**

In `CP/service/InvocationService.java`:
1. Delete the `invokeSync` method entirely.
2. Delete the `syncCoordinator` field.
3. In the **convenience constructor**, delete the `new SyncInvocationCoordinator(...)` argument line (keep the constructor's external signature unchanged — tests use it).
4. In the **@Autowired constructor**, remove the `SyncInvocationCoordinator syncCoordinator` parameter and its assignment.
5. Rewrite `invokeAsync` to use the shared helper:

```java
    public InvocationResponse invokeAsync(String functionName,
                                          InvocationRequest request,
                                          String idempotencyKey,
                                          String traceId) {
        enforceRateLimit();

        FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
        if (!enqueuer.enabled()) {
            throw new AsyncQueueUnavailableException();
        }

        InvocationExecutionFactory.ExecutionLookup lookup =
                executionFactory.createOrReuseExecution(functionName, spec, request, idempotencyKey, traceId);
        ExecutionRecord record = lookup.record();
        InvocationEnqueueSupport.admitIfNew(lookup,
                () -> InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, record));
        return new InvocationResponse(record.executionId(), "queued", null, null);
    }
```

6. Delete `CP/service/SyncInvocationCoordinator.java`.

- [ ] **Step 6: Migrate tests off the blocking API**

Find all call sites: `grep -rn "\.invokeSync(" control-plane/src/test`.
Mechanical replacement at each site: `invocationService.invokeSync(args...)` → `invocationService.invokeSyncReactive(args...).block()`. Constructors used by tests are unchanged. Any test asserting the **old B3 behavior** (exceptional completion → `"timeout"` status) must be updated to expect `"error"` / `EXECUTION_FAILED` — adjust assertions, do not delete coverage.

- [ ] **Step 7: Run full core suite, fix fallout, verify B3 test passes**

Run: `./scripts/controlplane.sh test --profile core`
Expected: PASS (including `ReactiveInvocationCoordinatorTest`).

- [ ] **Step 8: Commit**

```bash
git add -A control-plane/src
git commit -m "refactor(control-plane): remove blocking sync path; map exceptional completion to error (not timeout)"
```

---

### Task 3: B2 — keep blocking/spinning work off the Netty event loop

`InvocationExecutionFactory.parkPendingClaim` busy-waits (1 ms park loop) and runs on the event loop via the controllers. Move execution-creation onto `boundedElastic`, and make the controller fully deferred so all errors flow through `onErrorResume`.

**Files:**
- Modify: `CP/service/InvocationService.java` (`invokeSyncReactive`)
- Modify: `CP/api/InvocationController.java` (`invokeSync`, `invokeAsync`)
- Test: existing API/service suites (`CPT/service/InvocationServiceDispatchTest.java`, ControlPlaneApiTest)

- [ ] **Step 1: Make `invokeSyncReactive` defer and offload preparation**

In `CP/service/InvocationService.java` replace `invokeSyncReactive` with:

```java
    public Mono<InvocationResponse> invokeSyncReactive(String functionName,
                                                        InvocationRequest request,
                                                        String idempotencyKey,
                                                        String traceId,
                                                        Integer timeoutOverrideMs) {
        record Prepared(FunctionSpec spec, InvocationExecutionFactory.ExecutionLookup lookup) {}
        return Mono.fromCallable(() -> {
                    enforceRateLimit();
                    FunctionSpec spec = functionService.get(functionName).orElseThrow(FunctionNotFoundException::new);
                    // createOrReuseExecution may spin briefly on contended idempotency
                    // claims; it must never run on the Netty event loop.
                    return new Prepared(spec,
                            executionFactory.createOrReuseExecution(functionName, spec, request, idempotencyKey, traceId));
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(prepared -> reactiveCoordinator.invoke(prepared.lookup(), prepared.spec(), timeoutOverrideMs));
    }
```

- [ ] **Step 2: Make the controller fully reactive**

In `CP/api/InvocationController.java` replace `invokeSync` with:

```java
    @PostMapping("/functions/{name}:invoke")
    public Mono<ResponseEntity<InvocationResponse>> invokeSync(
            @PathVariable @NotBlank(message = "Function name is required") String name,
            @RequestBody @Valid InvocationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader(value = "X-Timeout-Ms", required = false) Integer timeoutMs) {
        return invocationService.invokeSyncReactive(name, request, idempotencyKey, traceId, timeoutMs)
                .map(response -> ResponseEntity.ok()
                        .header("X-Execution-Id", response.executionId())
                        .body(response))
                .onErrorResume(FunctionNotFoundException.class, ex ->
                        Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(SyncQueueRejectedException.class, ex ->
                        Mono.just(tooManyRequests(ex)))
                .onErrorResume(RateLimitException.class, ex ->
                        Mono.just(tooManyRequests()))
                .onErrorResume(QueueFullException.class, ex ->
                        Mono.just(tooManyRequests()));
    }
```

and `invokeAsync` with:

```java
    @PostMapping("/functions/{name}:enqueue")
    public Mono<ResponseEntity<InvocationResponse>> invokeAsync(
            @PathVariable @NotBlank(message = "Function name is required") String name,
            @RequestBody @Valid InvocationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return Mono.fromCallable(() -> invocationService.invokeAsync(name, request, idempotencyKey, traceId))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(response -> ResponseEntity.status(HttpStatus.ACCEPTED).body(response))
                .onErrorResume(FunctionNotFoundException.class, ex ->
                        Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(AsyncQueueUnavailableException.class, ex ->
                        Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build()))
                .onErrorResume(RateLimitException.class, ex ->
                        Mono.just(tooManyRequests()))
                .onErrorResume(QueueFullException.class, ex ->
                        Mono.just(tooManyRequests()));
    }
```

Remove the now-unused try/catch imports if any become unused.

- [ ] **Step 3: Run full core suite**

Run: `./scripts/controlplane.sh test --profile core`
Expected: PASS. The existing API tests (404, 429, 501 paths) are the regression net here — exceptions now surface reactively instead of synchronously, and the tests must still pass unchanged.

- [ ] **Step 4: Commit**

```bash
git add control-plane/src
git commit -m "fix(control-plane): keep idempotency-claim spin and enqueue work off the Netty event loop"
```

---

### Task 4: B4 — make `canTransition` honest (it only ever guards terminal states)

The early-return in `canTransition` means `ALLOWED_TRANSITIONS` is never consulted for QUEUED/RUNNING — the map is dead code. Enforcing the map for real would change behavior (e.g. reject QUEUED→SUCCESS) with regression risk; the behavior-preserving fix is to delete the map and state the actual rule.

**Files:**
- Modify: `CP/execution/ExecutionRecord.java`
- Test: existing `CPT/execution/ExecutionRecordStateTransitionTest.java`

- [ ] **Step 1: Replace the dead map with the real rule**

In `CP/execution/ExecutionRecord.java`:
1. Delete the `ALLOWED_TRANSITIONS` field and the entire `static { ... }` block.
2. Remove now-unused imports `java.util.EnumMap`, `java.util.EnumSet`, `java.util.Map`.
3. Replace `canTransition` with:

```java
    /**
     * Terminal states (SUCCESS, ERROR, TIMEOUT) are final; every transition between
     * non-terminal states (including RUNNING -> QUEUED for retries) is allowed.
     */
    private boolean canTransition(ExecutionState target) {
        if (isTerminalState(state)) {
            log.warn("Invalid state transition {} -> {} for execution {}", state, target, executionId);
            return false;
        }
        return true;
    }

    private static boolean isTerminalState(ExecutionState state) {
        return state == ExecutionState.SUCCESS
                || state == ExecutionState.ERROR
                || state == ExecutionState.TIMEOUT;
    }
```

4. Rewrite the existing `isTerminal()` accessor to delegate: `return isTerminalState(state);`.

- [ ] **Step 2: Run the state-transition tests, then the full suite**

Run: `./scripts/controlplane.sh test --profile core -- --tests it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecordStateTransitionTest`
Then: `./scripts/controlplane.sh test --profile core`
Expected: PASS, no behavior change.

- [ ] **Step 3: Commit**

```bash
git add control-plane/src
git commit -m "refactor(control-plane): remove dead ALLOWED_TRANSITIONS map; canTransition == !isTerminal"
```

---

### Task 5: B6 — conditional remove on TTL expiry in `IdempotencyStore.getExecutionId`

**Files:**
- Modify: `CP/execution/IdempotencyStore.java`

- [ ] **Step 1: Apply the fix**

Replace the body of `getExecutionId` with (note: `compose` computed once, two-arg `remove`):

```java
    public Optional<String> getExecutionId(String functionName, String key) {
        String composed = compose(functionName, key);
        StoredKey stored = keys.get(composed);
        if (stored == null) {
            return Optional.empty();
        }
        // Also check TTL during lookup for immediate expiration. Two-arg remove: a
        // concurrent writer may have re-published this key with a fresh value.
        if (isExpired(stored, Instant.now())) {
            keys.remove(composed, stored);
            return Optional.empty();
        }
        if (stored.pending()) {
            return Optional.empty();
        }
        return Optional.of(stored.executionId());
    }
```

- [ ] **Step 2: Run the store tests, then commit**

Run: `./scripts/controlplane.sh test --profile core -- --tests "it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore*"`
Expected: PASS. (The race itself is not deterministically testable without seams; the existing expiry tests are the regression net.)

```bash
git add control-plane/src
git commit -m "fix(control-plane): conditional remove on idempotency TTL expiry (avoid deleting fresh re-publish)"
```

---

### Task 6: ExecutionStore hardening — B5 (non-terminal leak) + B7 (daemon janitor) + I1 (configurable TTLs) + I2 (cleanup once)

**Files:**
- Create: `CP/config/ExecutionStoreProperties.java`
- Modify: `CP/execution/ExecutionStore.java`
- Modify: `CP/execution/ExecutionRecord.java` (cleanup-once flag)
- Modify: `CP/config/CoreDefaults.java` (register properties)
- Test: create `CPT/execution/ExecutionStoreEvictionTest.java`

- [ ] **Step 1: Write the failing test**

Create `CPT/execution/ExecutionStoreEvictionTest.java`:

```java
package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionStoreEvictionTest {

    private static ExecutionRecord record(String id) {
        FunctionSpec spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        InvocationTask task = new InvocationTask(id, "fn", spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1);
        return new ExecutionRecord(id, task);
    }

    @Test
    void nonTerminalRecordsAreEvictedAfterMaxLifetime() throws InterruptedException {
        ExecutionStoreProperties props = new ExecutionStoreProperties(
                Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMillis(50));
        ExecutionStore store = new ExecutionStore(props);
        try {
            ExecutionRecord stuck = record("stuck-queued");
            store.put(stuck); // never transitions: simulates a lost dispatch

            Thread.sleep(120);
            store.evictExpired();

            assertThat(store.getOrNull("stuck-queued")).isNull();
        } finally {
            store.shutdown();
        }
    }

    @Test
    void freshNonTerminalRecordsSurviveEviction() {
        ExecutionStore store = new ExecutionStore(new ExecutionStoreProperties(null, null, null));
        try {
            ExecutionRecord running = record("fresh");
            store.put(running);
            store.evictExpired();
            assertThat(store.getOrNull("fresh")).isNotNull();
        } finally {
            store.shutdown();
        }
    }

    @Test
    void cleanupReleasesPayloadOnlyOnce() {
        ExecutionRecord done = record("done");
        done.markSuccess("out");
        done.cleanup();
        InvocationTask afterFirstCleanup = done.task();
        done.cleanup();
        // cleanup must be idempotent: no new task allocation on repeat sweeps
        assertThat(done.task()).isSameAs(afterFirstCleanup);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./scripts/controlplane.sh test --profile core -- --tests it.unimib.datai.nanofaas.controlplane.execution.ExecutionStoreEvictionTest`
Expected: FAIL (compile error: `ExecutionStoreProperties` and the new `ExecutionStore` constructor / package-private `evictExpired` don't exist).

- [ ] **Step 3: Create the properties record**

Create `CP/config/ExecutionStoreProperties.java`:

```java
package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * TTLs for the in-memory execution store.
 *
 * <p>{@code ttl}: retention of terminal executions (status queryable window).
 * {@code cleanupTtl}: when heavy payloads of terminal executions are released.
 * {@code maxLifetime}: absolute cap after which even non-terminal (stuck) executions
 * are evicted to prevent unbounded growth.</p>
 */
@ConfigurationProperties(prefix = "nanofaas.execution-store")
public record ExecutionStoreProperties(
        Duration ttl,
        Duration cleanupTtl,
        Duration maxLifetime
) {
    public ExecutionStoreProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofMinutes(5);
        }
        if (cleanupTtl == null || cleanupTtl.isNegative() || cleanupTtl.isZero()) {
            cleanupTtl = Duration.ofMinutes(2);
        }
        if (maxLifetime == null || maxLifetime.isNegative() || maxLifetime.isZero()) {
            maxLifetime = Duration.ofMinutes(30);
        }
    }
}
```

- [ ] **Step 4: Register the properties**

In `CP/config/CoreDefaults.java` add on the class:

```java
@org.springframework.boot.context.properties.EnableConfigurationProperties(
        it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties.class)
```

(or the equivalent import + annotation form matching the file's style).

- [ ] **Step 5: Rework `ExecutionStore`**

Replace `CP/execution/ExecutionStore.java` content with:

```java
package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;

@Component
public class ExecutionStore {
    private final Map<String, StoredExecution> executions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService janitor;
    private final Duration cleanupTtl;
    private final Duration ttl;
    private final Duration maxLifetime;

    public ExecutionStore() {
        this(new ExecutionStoreProperties(null, null, null));
    }

    // @Autowired is required: with two constructors Spring would otherwise pick the
    // no-arg one and silently ignore the configured properties.
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionStore(ExecutionStoreProperties properties) {
        this.ttl = properties.ttl();
        this.cleanupTtl = properties.cleanupTtl();
        this.maxLifetime = properties.maxLifetime();
        this.janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "execution-store-janitor");
            t.setDaemon(true);
            return t;
        });
        janitor.scheduleAtFixedRate(this::evictExpired, 1, 1, TimeUnit.MINUTES);
    }

    public void put(ExecutionRecord record) {
        executions.put(record.executionId(), new StoredExecution(record, Instant.now()));
    }

    public Optional<ExecutionRecord> get(String executionId) {
        StoredExecution stored = executions.get(executionId);
        if (stored == null) {
            return Optional.empty();
        }
        return Optional.of(stored.record());
    }

    /**
     * Hot-path lookup without Optional allocation.
     */
    public ExecutionRecord getOrNull(String executionId) {
        StoredExecution stored = executions.get(executionId);
        return stored == null ? null : stored.record();
    }

    public void remove(String executionId) {
        executions.remove(executionId);
    }

    // Package-private for deterministic testing.
    void evictExpired() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(ttl);
        Instant cleanupCutoff = now.minus(cleanupTtl);
        Instant lifetimeCutoff = now.minus(maxLifetime);

        executions.entrySet().removeIf(entry -> {
            StoredExecution stored = entry.getValue();
            ExecutionRecord record = stored.record();
            Instant created = stored.createdAt();
            if (!record.isTerminal()) {
                // Stuck executions (lost dispatch, missing callback) must not leak forever.
                return created.isBefore(lifetimeCutoff);
            }

            Instant completedAt = record.finishedAt();
            Instant retentionAnchor = completedAt == null ? created : completedAt;

            if (retentionAnchor.isBefore(cutoff)) {
                return true;
            }
            if (retentionAnchor.isBefore(cleanupCutoff)) {
                record.cleanup();
            }
            return false;
        });
    }

    @PreDestroy
    public void shutdown() {
        janitor.shutdownNow();
    }

    private record StoredExecution(ExecutionRecord record, Instant createdAt) {
    }
}
```

- [ ] **Step 6: Make `ExecutionRecord.cleanup()` idempotent**

In `CP/execution/ExecutionRecord.java` add a field `private boolean cleaned;` (with the other mutable fields) and change `cleanup()` to:

```java
    public synchronized void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        this.output = null;
        if (this.task != null) {
            // Replace task with one that has no request payload
            this.task = new InvocationTask(
                    task.executionId(),
                    task.functionName(),
                    task.functionSpec(),
                    null, // Clear request
                    task.idempotencyKey(),
                    task.traceId(),
                    task.enqueuedAt(),
                    task.attempt()
            );
        }
    }
```

Also reset the flag in `resetForRetry` (`this.cleaned = false;`) alongside the other field resets.

- [ ] **Step 7: Run the new test, then the full suite**

Run: `./scripts/controlplane.sh test --profile core -- --tests it.unimib.datai.nanofaas.controlplane.execution.ExecutionStoreEvictionTest`
Then: `./scripts/controlplane.sh test --profile core`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add control-plane/src
git commit -m "fix(control-plane): evict stuck executions, daemon janitor, configurable store TTLs, idempotent cleanup"
```

---

### Task 7: B8 — RateLimiter monotonic window + CLAUDE.md doc drift

**Files:**
- Modify: `CP/service/RateLimiter.java`
- Modify: `CLAUDE.md` (Key Configuration section)

- [ ] **Step 1: Make the window roll forward only**

In `CP/service/RateLimiter.java` change the condition in `allow()`:

```java
        if (now > currentWindow && windowStartSecond.compareAndSet(currentWindow, now)) {
```

(was `now != currentWindow` — a backwards clock step must not reset the window).

- [ ] **Step 2: Fix the documented default**

In `CLAUDE.md`, "Key Configuration" section, change:
`- nanofaas.rate.maxPerSecond (1000)` → `- nanofaas.rate.maxPerSecond (1000000)`
(actual value in `control-plane/src/main/resources/application.yml:42`).

- [ ] **Step 3: Run suite and commit**

Run: `./scripts/controlplane.sh test --profile core`

```bash
git add control-plane/src CLAUDE.md
git commit -m "fix(control-plane): monotonic rate-limit window; align documented rate default"
```

---

### Task 8: S3 — delete deprecated `ExecutionRecord` accessors

**Files:**
- Modify: `CP/execution/ExecutionRecord.java`
- Delete: `CPT/execution/ExecutionRecordLegacyAccessorsTest.java`

- [ ] **Step 1: Verify nothing in main/modules uses them**

Run: `grep -rn --include="*.java" -e "updateTask(" -e "\.state(ExecutionState" -e "\.startedAt(Instant" -e "\.finishedAt(Instant" -e "\.lastError(new" -e "\.output(" control-plane/src/main control-plane-modules/*/src/main`
Expected: no matches (verified during review; re-verify before deleting).

- [ ] **Step 2: Delete the six `@Deprecated` setters and the legacy test**

In `CP/execution/ExecutionRecord.java` delete the entire "Legacy setters" section: `updateTask`, `state(ExecutionState)`, `startedAt(Instant)`, `finishedAt(Instant)`, `lastError(ErrorInfo)`, `output(Object)`.
Delete `CPT/execution/ExecutionRecordLegacyAccessorsTest.java`.

- [ ] **Step 3: Run full suite and commit**

Run: `./scripts/controlplane.sh test --profile core`

```bash
git add -A control-plane/src
git commit -m "refactor(control-plane): drop deprecated ExecutionRecord legacy setters"
```

---

### Task 9: S4 — delete unused `IdempotencyStore` legacy mutators

`putIfAbsent` and `replaceExecutionId` are dead in production (only the `acquireOrGet`/`claimIfMatches`/`publishClaim`/`abandonClaim` flow is used). `put` and `getExecutionId` stay: they are the test seeding/assertion seam used by `InvocationServiceDispatchTest` and others.

**Files:**
- Modify: `CP/execution/IdempotencyStore.java`
- Delete: `CPT/execution/IdempotencyStorePutIfAbsentTest.java`
- Modify: any other test exercising `replaceExecutionId` (find with grep)

- [ ] **Step 1: Verify production dead-ness, find test users**

Run: `grep -rn --include="*.java" -e "putIfAbsent(" -e "replaceExecutionId(" control-plane/src control-plane-modules/*/src 2>/dev/null | grep -v IdempotencyStore.java`
Expected: only test files. Note every listed test file.

- [ ] **Step 2: Delete `putIfAbsent` and `replaceExecutionId` from `IdempotencyStore`; delete/trim their tests**

Delete the two methods. Delete `CPT/execution/IdempotencyStorePutIfAbsentTest.java`; remove `replaceExecutionId` test methods from any other file found in Step 1 (keep unrelated tests in those files).

- [ ] **Step 3: Run full suite and commit**

Run: `./scripts/controlplane.sh test --profile core`

```bash
git add -A control-plane/src
git commit -m "refactor(control-plane): drop unused IdempotencyStore putIfAbsent/replaceExecutionId"
```

---

### Task 10: S5 + S7 + S8 — minor cleanups

**Files:**
- Modify: `CP/registry/FunctionService.java`
- Modify: `CP/dispatch/PoolDispatcher.java`
- Modify: `CP/api/GlobalExceptionHandler.java`

- [ ] **Step 1: FunctionService — remove the no-op try/catch**

In `register(FunctionSpec)`, replace:

```java
            RegisteredFunction registered;
            try {
                imageValidator.validate(initialResolved);
                registered = resolveRegistration(initialResolved);
            } catch (RuntimeException e) {
                throw e;
            }
```

with:

```java
            imageValidator.validate(initialResolved);
            RegisteredFunction registered = resolveRegistration(initialResolved);
```

- [ ] **Step 2: PoolDispatcher — extract header parsing, drop redundant locals**

In `CP/dispatch/PoolDispatcher.java`, inside `exchangeToMono`, replace the cold-start/init parsing block with:

```java
                .exchangeToMono(response -> {
                    boolean isCold = "true".equalsIgnoreCase(
                            response.headers().asHttpHeaders().getFirst("X-Cold-Start"));
                    Long initMs = parseInitDuration(
                            response.headers().asHttpHeaders().getFirst("X-Init-Duration-Ms"));
```

(delete the old `coldStart`/`initDurationMs` mutable locals and the `isCold = coldStart; initMs = initDurationMs;` copies; the rest of the lambda already uses `isCold`/`initMs`). Add at class bottom:

```java
    private static Long parseInitDuration(String header) {
        if (header == null) {
            return null;
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
```

- [ ] **Step 3: GlobalExceptionHandler — merge the duplicate binding-error handlers**

Replace `handleValidationErrors` and `handleWebExchangeBindException` with a single handler:

```java
    /**
     * Handles validation errors from @Valid request bodies (servlet and reactive binding).
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, WebExchangeBindException.class})
    public ResponseEntity<Map<String, Object>> handleBindingErrors(Exception ex) {
        var bindingResult = ex instanceof MethodArgumentNotValidException manve
                ? manve.getBindingResult()
                : ((WebExchangeBindException) ex).getBindingResult();
        List<String> errors = bindingResult
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();

        log.debug("Validation failed: {}", errors);
        return ResponseEntity.badRequest().body(validationErrorBody(errors));
    }
```

- [ ] **Step 4: Run full suite and commit**

Run: `./scripts/controlplane.sh test --profile core`

```bash
git add control-plane/src
git commit -m "refactor(control-plane): minor cleanups (no-op catch, header parsing, merged validation handlers)"
```

---

### Task 11: P1 — consolidate `Metrics` into one per-function lookup

Public API of `Metrics` (all `void` counter methods, `latency/initDuration/queueWait/e2eLatency`, package-private `timers()`, the `FunctionTimers` record) stays **identical** — tests mock these. Only the internal storage changes: ten maps → one.

**Files:**
- Modify: `CP/service/Metrics.java`

- [ ] **Step 1: Rewrite the internals**

Replace the field section and private helpers of `CP/service/Metrics.java` (public method signatures unchanged):

```java
@Component
public class Metrics {
    private final MeterRegistry registry;
    private final Map<String, FunctionMeters> meters = new ConcurrentHashMap<>();

    public Metrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void enqueue(String function) { meters(function).enqueue().increment(); }

    public void dispatch(String function) { meters(function).dispatch().increment(); }

    public void success(String function) { meters(function).success().increment(); }

    public void error(String function) { meters(function).error().increment(); }

    public void retry(String function) { meters(function).retry().increment(); }

    public void timeout(String function) { meters(function).timeout().increment(); }

    public void queueRejected(String function) { meters(function).queueRejected().increment(); }

    public void coldStart(String function) { meters(function).coldStart().increment(); }

    public void warmStart(String function) { meters(function).warmStart().increment(); }

    public Timer latency(String function) { return timers(function).latency(); }

    public Timer initDuration(String function) { return timers(function).initDuration(); }

    public Timer queueWait(String function) { return timers(function).queueWait(); }

    public Timer e2eLatency(String function) { return timers(function).e2eLatency(); }

    FunctionTimers timers(String function) {
        return meters(function).timers();
    }

    private FunctionMeters meters(String function) {
        return meters.computeIfAbsent(function, this::registerMeters);
    }

    private FunctionMeters registerMeters(String function) {
        return new FunctionMeters(
                counter("function_enqueue_total", function),
                counter("function_dispatch_total", function),
                counter("function_success_total", function),
                counter("function_error_total", function),
                counter("function_retry_total", function),
                counter("function_timeout_total", function),
                counter("function_queue_rejected_total", function),
                counter("function_cold_start_total", function),
                counter("function_warm_start_total", function),
                new FunctionTimers(
                        timer("function_latency_ms", function),
                        timer("function_init_duration_ms", function),
                        timer("function_queue_wait_ms", function),
                        timer("function_e2e_latency_ms", function)
                )
        );
    }

    private Counter counter(String name, String function) {
        return Counter.builder(name).tag("function", function).register(registry);
    }

    private Timer timer(String name, String function) {
        return Timer.builder(name)
                .tag("function", function)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    record FunctionMeters(Counter enqueue, Counter dispatch, Counter success, Counter error,
                          Counter retry, Counter timeout, Counter queueRejected,
                          Counter coldStart, Counter warmStart, FunctionTimers timers) {
    }

    record FunctionTimers(Timer latency, Timer initDuration, Timer queueWait, Timer e2eLatency) {
    }
}
```

Note the (accepted) behavior change: all counters/timers for a function are now registered on first touch instead of lazily per metric. If a metrics test asserts a meter is *absent* until its event fires, update that test.

- [ ] **Step 2: Run full suite and commit**

Run: `./scripts/controlplane.sh test --profile core`

```bash
git add control-plane/src
git commit -m "perf(control-plane): single per-function meter lookup in Metrics"
```

---

### Task 12: P2 — move dispatch-slot release tracking into `ExecutionRecord`

Replaces the global `Collections.synchronizedMap(new WeakHashMap<>())` (a process-wide lock touched on every completion) with per-record state guarded by the record's own monitor.

**Files:**
- Modify: `CP/execution/ExecutionRecord.java`
- Modify: `CP/service/ExecutionCompletionHandler.java`
- Test: existing `CPT/service/ExecutionCompletionHandlerSlotReleaseTest.java`

- [ ] **Step 1: Add per-record tracking**

In `CP/execution/ExecutionRecord.java` add a field (with the other mutable state):

```java
    private final java.util.Set<Integer> releasedDispatchAttempts = new java.util.HashSet<>();
```

and a method:

```java
    /**
     * Records that the dispatch slot for the given attempt has been released.
     * @return true the first time this attempt is released, false on duplicates
     */
    public synchronized boolean markDispatchSlotReleased(int attempt) {
        return releasedDispatchAttempts.add(attempt);
    }
```

- [ ] **Step 2: Use it in the handler**

In `CP/service/ExecutionCompletionHandler.java`:
1. Delete the `releasedDispatchAttempts` field and the `markDispatchSlotReleased(ExecutionRecord, int)` private method, plus now-unused imports (`Collections`, `HashSet`, `Set`, `WeakHashMap`, `Map`).
2. Change `releaseDispatchSlotOnce` to:

```java
    private void releaseDispatchSlotOnce(ExecutionRecord record, int attempt, String functionName) {
        if (record.markDispatchSlotReleased(attempt)) {
            releaseDispatchSlot(functionName);
        }
    }
```

- [ ] **Step 3: Run the slot-release tests, then the full suite, then commit**

Run: `./scripts/controlplane.sh test --profile core -- --tests it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandlerSlotReleaseTest`
Then: `./scripts/controlplane.sh test --profile core`

```bash
git add control-plane/src
git commit -m "perf(control-plane): per-record dispatch-slot release tracking (drop global WeakHashMap)"
```

---

### Task 13: P4 — complete the future and record metrics outside the record monitor

Today `record.completion().complete(result)` runs while holding `synchronized(record)`, so synchronous `whenComplete` continuations (e.g. async-queue callbacks) execute under the record monitor, and Micrometer recording extends the critical section. Restructure: mutate state under the lock, collect a `FinalCompletion`, publish outside.

**Files:**
- Modify: `CP/service/ExecutionCompletionHandler.java`
- Test: existing `CPT/service/ExecutionCompletionHandlerTest.java`, `CPT/service/InvocationServiceRetryTest.java`, `CPT/service/InvocationServiceRetryQueueFullTest.java`

- [ ] **Step 1: Restructure `completeExecution(ExecutionRecord, DispatchResult, Integer)`**

Replace the private `completeExecution` with:

```java
    private void completeExecution(ExecutionRecord record, DispatchResult dispatchResult, Integer completedAttempt) {
        FinalCompletion completion;
        synchronized (record) {
            completion = completeUnderLock(record, dispatchResult, completedAttempt);
        }
        publishFinalCompletion(record, completion);
    }

    /**
     * State transitions only; meter recording and future completion happen outside the
     * record monitor (see publishFinalCompletion) so synchronous whenComplete callbacks
     * never run while the lock is held.
     */
    private FinalCompletion completeUnderLock(ExecutionRecord record,
                                              DispatchResult dispatchResult,
                                              Integer completedAttempt) {
        InvocationResult result = dispatchResult.result();
        InvocationTask currentTask = record.task();
        int attempt = completedAttempt != null ? completedAttempt : currentTask.attempt();
        if (completedAttempt != null && currentTask.attempt() != completedAttempt) {
            return null;
        }

        String functionName = currentTask.functionName();
        releaseDispatchSlotOnce(record, attempt, functionName);
        if (isTerminal(record.state())) {
            return null;
        }

        boolean shouldRetry = !result.success()
                && currentTask.attempt() < currentTask.functionSpec().maxRetries();

        if (shouldRetry) {
            metrics.retry(functionName);
            InvocationTask retryTask = new InvocationTask(
                    record.executionId(),
                    functionName,
                    currentTask.functionSpec(),
                    currentTask.request(),
                    null,  // No idempotency key for retry - retry is internal
                    currentTask.traceId(),
                    Instant.now(),
                    currentTask.attempt() + 1
            );
            record.resetForRetry(retryTask);
            try {
                InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, record);
                return null;
            } catch (QueueFullException ex) {
                log.warn("Retry queue full for execution {}, completing with error", record.executionId());
                record.markError(result.error());
                return FinalCompletion.retryExhausted(functionName, result);
            }
        }

        Instant enqueuedAt = currentTask.enqueuedAt();
        Instant startedAt = record.startedAt();
        if (result.success()) {
            record.markSuccess(result.output());
        } else {
            record.markError(result.error());
        }
        Instant finishedAt = record.finishedAt();
        if (dispatchResult.coldStart()) {
            record.markColdStart(dispatchResult.initDurationMs() != null ? dispatchResult.initDurationMs() : 0);
        }

        Long latencyMs = (startedAt != null && finishedAt != null)
                ? finishedAt.toEpochMilli() - startedAt.toEpochMilli() : null;
        Long queueWaitMs = (enqueuedAt != null && startedAt != null)
                ? startedAt.toEpochMilli() - enqueuedAt.toEpochMilli() : null;
        Long e2eMs = (enqueuedAt != null && finishedAt != null)
                ? finishedAt.toEpochMilli() - enqueuedAt.toEpochMilli() : null;
        return new FinalCompletion(functionName, result, latencyMs, queueWaitMs, e2eMs,
                dispatchResult.coldStart(), dispatchResult.initDurationMs(), false);
    }

    private void publishFinalCompletion(ExecutionRecord record, FinalCompletion completion) {
        if (completion == null) {
            return;
        }
        String functionName = completion.functionName();
        if (!completion.retryExhausted()) {
            Metrics.FunctionTimers timers = metrics.timers(functionName);
            if (completion.coldStart()) {
                metrics.coldStart(functionName);
                if (completion.initDurationMs() != null) {
                    timers.initDuration().record(completion.initDurationMs(), TimeUnit.MILLISECONDS);
                }
            } else {
                metrics.warmStart(functionName);
            }
            if (completion.latencyMs() != null) {
                timers.latency().record(completion.latencyMs(), TimeUnit.MILLISECONDS);
            }
            if (completion.queueWaitMs() != null && completion.queueWaitMs() >= 0) {
                timers.queueWait().record(completion.queueWaitMs(), TimeUnit.MILLISECONDS);
            }
            if (completion.e2eMs() != null && completion.e2eMs() >= 0) {
                timers.e2eLatency().record(completion.e2eMs(), TimeUnit.MILLISECONDS);
            }
        }
        if (completion.result().success()) {
            metrics.success(functionName);
        } else {
            metrics.error(functionName);
        }
        record.completion().complete(completion.result());
    }

    private record FinalCompletion(String functionName,
                                   InvocationResult result,
                                   Long latencyMs,
                                   Long queueWaitMs,
                                   Long e2eMs,
                                   boolean coldStart,
                                   Long initDurationMs,
                                   boolean retryExhausted) {
        static FinalCompletion retryExhausted(String functionName, InvocationResult result) {
            return new FinalCompletion(functionName, result, null, null, null, false, null, true);
        }
    }
```

Behavioral parity notes (must hold, the existing tests verify them):
- retry-exhausted (queue full) path: `markError` + `metrics.error` + future completed with the failed result, no cold/warm or timer recording — same as before.
- success/error metrics and the timer set recorded exactly once per final completion — same as before.
- `markColdStart` still happens before the future completes (now under the lock, meters outside).

- [ ] **Step 2: Run the handler/retry test classes, then the full suite**

Run: `./scripts/controlplane.sh test --profile core -- --tests "it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandler*"`
Run: `./scripts/controlplane.sh test --profile core -- --tests "it.unimib.datai.nanofaas.controlplane.service.InvocationServiceRetry*"`
Then: `./scripts/controlplane.sh test --profile core`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add control-plane/src
git commit -m "perf(control-plane): complete futures and record meters outside the record monitor"
```

---

### Task 14: P3 — fast execution IDs (drop SecureRandom from the hot path)

**Files:**
- Modify: `CP/service/InvocationExecutionFactory.java`
- Test: add a case to `CPT/service/ReactiveInvocationCoordinatorTest.java` or a new `CPT/service/ExecutionIdTest.java`

- [ ] **Step 1: Write the failing test**

Create `CPT/service/ExecutionIdTest.java`:

```java
package it.unimib.datai.nanofaas.controlplane.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionIdTest {
    private static final Pattern UUID_V4 = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    @Test
    void executionIdsAreValidV4UuidsAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = InvocationExecutionFactory.newExecutionId();
            assertThat(id).matches(UUID_V4);
            assertThat(seen.add(id)).isTrue();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./scripts/controlplane.sh test --profile core -- --tests it.unimib.datai.nanofaas.controlplane.service.ExecutionIdTest`
Expected: FAIL (compile error: `newExecutionId` does not exist).

- [ ] **Step 3: Implement**

In `CP/service/InvocationExecutionFactory.java`, in `newExecutionRecord` replace `String executionId = UUID.randomUUID().toString();` with `String executionId = newExecutionId();` and add:

```java
    /**
     * UUIDv4-shaped id from ThreadLocalRandom. Execution ids need uniqueness, not
     * unpredictability; this avoids the SecureRandom contention of UUID.randomUUID()
     * on the invocation hot path.
     */
    static String newExecutionId() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        long msb = (random.nextLong() & 0xFFFF_FFFF_FFFF_0FFFL) | 0x0000_0000_0000_4000L; // version 4
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // IETF variant
        return new UUID(msb, lsb).toString();
    }
```

- [ ] **Step 4: Run the test, then full suite, then commit**

Run: `./scripts/controlplane.sh test --profile core -- --tests it.unimib.datai.nanofaas.controlplane.service.ExecutionIdTest`
Then: `./scripts/controlplane.sh test --profile core`

```bash
git add control-plane/src
git commit -m "perf(control-plane): ThreadLocalRandom-based execution ids"
```

---

### Task 15: I3 — per-request response timeout in PoolDispatcher + larger codec default

Functions with `timeoutMs > nanofaas.http-client.readTimeoutMs` (default 30 s) currently hit the global WebClient response timeout first and get a generic `POOL_ERROR` instead of `POOL_TIMEOUT`. Set the Reactor Netty response timeout per request from the function spec. Also raise the in-memory codec default from 1 MB to 16 MB (function responses larger than 1 MB currently fail).

**Files:**
- Modify: `CP/dispatch/PoolDispatcher.java`
- Modify: `CP/config/HttpClientProperties.java`
- Check/modify: `control-plane/src/main/resources/application.yml` (only if it sets `nanofaas.http-client.*`)

- [ ] **Step 1: Per-request response timeout**

In `CP/dispatch/PoolDispatcher.java`, move the `long timeoutMs = task.functionSpec().timeoutMs();` line **above** the `WebClient.RequestBodySpec request = ...` builder, then add to the builder chain (after the `.header(...)` calls, before `bodyValue`):

```java
        request.httpRequest(clientHttpRequest -> {
            reactor.netty.http.client.HttpClientRequest reactorRequest = clientHttpRequest.getNativeRequest();
            reactorRequest.responseTimeout(Duration.ofMillis(timeoutMs));
        });
```

(The existing `.timeout(Duration.ofMillis(timeoutMs))` on the Mono stays: it bounds the whole exchange and maps to `POOL_TIMEOUT`.)

- [ ] **Step 2: Raise the codec default**

In `CP/config/HttpClientProperties.java` compact constructor, change the fallback `maxInMemorySizeMb = 1;` to `maxInMemorySizeMb = 16;` and update the record's javadoc to state the defaults (`connect 5000 ms, read 30000 ms, max in-memory 16 MB`). Check `application.yml` for an explicit `nanofaas.http-client` block; if present, align it.

- [ ] **Step 3: Run full suite and commit**

Run: `./scripts/controlplane.sh test --profile core`

```bash
git add control-plane/src
git commit -m "fix(control-plane): honor per-function timeout in pool dispatch; raise codec buffer default to 16MB"
```

---

### Task 16 (OPTIONAL): I4 — replace IdempotencyStore TTL machinery with Caffeine

Execute only after Tasks 1–15 are merged and validated. Benefits: deletes the janitor thread, all `isExpired` branches (expired entries simply vanish, collapsing the "expired → take over claim" branches into the existing `null →` branches), and B6's class of races. Risk: behavior subtleties in claim takeover; native-image compatibility must be verified.

**Files:**
- Modify: `control-plane/build.gradle` (dependencies block)
- Modify: `CP/execution/IdempotencyStore.java`
- Test: existing `CPT/execution/IdempotencyStore*` suites

- [ ] **Step 1: Add the dependency**

In `control-plane/build.gradle` dependencies block add (version managed by Spring Boot's BOM):

```groovy
    implementation 'com.github.ben-manes.caffeine:caffeine'
```

- [ ] **Step 2: Rewrite the store**

Replace `CP/execution/IdempotencyStore.java` with:

```java
package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

@Component
public class IdempotencyStore {
    private final Cache<String, StoredKey> cache;
    private final ConcurrentMap<String, StoredKey> keys;

    public IdempotencyStore() {
        this(Duration.ofMinutes(5));
    }

    public IdempotencyStore(Duration ttl) {
        this(ttl, Ticker.systemTicker());
    }

    IdempotencyStore(Duration ttl, Ticker ticker) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .ticker(ticker)
                .build();
        this.keys = cache.asMap();
    }

    public Optional<String> getExecutionId(String functionName, String key) {
        StoredKey stored = keys.get(compose(functionName, key));
        if (stored == null || stored.pending()) {
            return Optional.empty();
        }
        return Optional.of(stored.executionId());
    }

    public void put(String functionName, String key, String executionId) {
        keys.put(compose(functionName, key), StoredKey.published(executionId, Instant.now()));
    }

    public AcquireResult acquireOrGet(String functionName, String key) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing == null) {
                String token = pendingToken();
                StoredKey pending = StoredKey.pending(token, Instant.now());
                if (keys.putIfAbsent(composed, pending) == null) {
                    return AcquireResult.claimed(token);
                }
                continue;
            }
            if (existing.pending()) {
                return AcquireResult.pending();
            }
            return AcquireResult.existing(existing.executionId());
        }
    }

    public AcquireResult claimIfMatches(String functionName, String key, String expectedExecutionId) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing == null) {
                return AcquireResult.missing();
            }
            if (existing.pending()) {
                return AcquireResult.pending();
            }
            if (!existing.executionId().equals(expectedExecutionId)) {
                return AcquireResult.existing(existing.executionId());
            }
            String token = pendingToken();
            StoredKey pending = StoredKey.pending(token, Instant.now());
            if (keys.replace(composed, existing, pending)) {
                return AcquireResult.claimed(token);
            }
        }
    }

    public void publishClaim(String functionName, String key, String claimToken, String executionId) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing == null || !existing.pending() || !existing.executionId().equals(claimToken)) {
                throw new IllegalStateException("Missing idempotency claim for " + composed);
            }
            StoredKey published = StoredKey.published(executionId, Instant.now());
            if (keys.replace(composed, existing, published)) {
                return;
            }
        }
    }

    public void abandonClaim(String functionName, String key, String claimToken) {
        String composed = compose(functionName, key);
        StoredKey existing = keys.get(composed);
        if (existing != null && existing.pending() && existing.executionId().equals(claimToken)) {
            keys.remove(composed, existing);
        }
    }

    public int size() {
        cache.cleanUp();
        return keys.size();
    }

    private String compose(String functionName, String key) {
        return functionName + ":" + key;
    }

    private String pendingToken() {
        return "pending:" + Instant.now().toEpochMilli() + ":" + System.nanoTime();
    }

    public record AcquireResult(State state, String executionIdOrToken) {
        static AcquireResult claimed(String token) {
            return new AcquireResult(State.CLAIMED, token);
        }

        static AcquireResult existing(String executionId) {
            return new AcquireResult(State.EXISTING, executionId);
        }

        static AcquireResult pending() {
            return new AcquireResult(State.PENDING, null);
        }

        static AcquireResult missing() {
            return new AcquireResult(State.MISSING, null);
        }

        public enum State {
            CLAIMED,
            EXISTING,
            PENDING,
            MISSING
        }
    }

    private record StoredKey(String executionId, Instant storedAt, boolean pending) {
        static StoredKey pending(String claimToken, Instant storedAt) {
            return new StoredKey(claimToken, storedAt, true);
        }

        static StoredKey published(String executionId, Instant storedAt) {
            return new StoredKey(executionId, storedAt, false);
        }
    }
}
```

Deleted vs the old file: the janitor `ScheduledExecutorService`, `@PreDestroy shutdown()`, `evictExpired()`, `isExpired()`, all `isExpired`-based claim-takeover branches. A claim that was previously taken over because "expired" now simply isn't found (Caffeine evicted it) and goes through the `null` branch — same outcome.

- [ ] **Step 3: Adapt expiry tests**

Existing tests that exercised TTL expiry by manipulating `storedAt` or sleeping must inject a `com.github.benmanes.caffeine.cache.testing.FakeTicker`-style ticker through the new package-private constructor (a simple inline `Ticker` backed by an `AtomicLong` is sufficient — no extra test dependency needed):

```java
AtomicLong nanos = new AtomicLong();
IdempotencyStore store = new IdempotencyStore(Duration.ofMinutes(5), nanos::get);
// ... advance time:
nanos.addAndGet(Duration.ofMinutes(6).toNanos());
```

- [ ] **Step 4: Run the store suites, full core suite, and the native build smoke if feasible**

Run: `./scripts/controlplane.sh test --profile core -- --tests "it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore*"`
Then: `./scripts/controlplane.sh test --profile core`
Optional (if GraalVM available): `./scripts/native-build.sh` to confirm Caffeine doesn't break the native image.

- [ ] **Step 5: Commit**

```bash
git add control-plane/build.gradle control-plane/src
git commit -m "refactor(control-plane): Caffeine-backed IdempotencyStore (drop hand-rolled TTL janitor)"
```

---

## Final verification (after the last task)

- [ ] `./scripts/controlplane.sh test --profile core` — green
- [ ] `./scripts/controlplane.sh test --profile all` — green (modules compile against the changed core: `InvocationService` constructor, `SyncQueueService`/`AsyncQueueConfiguration` still complete futures via `record.completion().complete(...)`, untouched)
- [ ] `./scripts/controlplane.sh e2e run docker` — green (requires Docker)
- [ ] `uv run --project tools/controlplane pytest tools/controlplane/tests` — green (guards against doc/gate regressions from the CLAUDE.md edit)
