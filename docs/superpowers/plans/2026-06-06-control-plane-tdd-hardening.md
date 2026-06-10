# Control Plane TDD Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the highest-risk control-plane issues found in review using strict red-green-refactor TDD.

**Architecture:** Keep behavioral fixes inside the existing control-plane boundaries: execution lifecycle state in `ExecutionRecord`, lifecycle orchestration in `ExecutionCompletionHandler`, storage retention in `ExecutionStore`, request/idempotency creation in `InvocationExecutionFactory`, sync waiting in `SyncInvocationCoordinator`, and registration-time normalization in `FunctionSpecResolver`. Refactors are allowed only after the relevant tests are green.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, Mockito, Gradle, GitNexus.

---

## Guardrails

- Before editing any class or method symbol, run GitNexus impact analysis for that symbol and record the risk in the implementation notes.
- If GitNexus reports HIGH or CRITICAL risk, stop and warn the user before editing.
- For every behavior change: write the failing test first, run only the narrow test and confirm the expected failure, implement the smallest production change, rerun the narrow test, then run the relevant module tests.
- Do not batch production fixes before seeing each test fail.
- Do not refactor duplicated coordinator code until the behavioral bug tests are green.

## Expected Commands

- Narrow core test: `./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandlerSlotReleaseTest'`
- Execution store test: `./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.execution.ExecutionStoreEvictionTest'`
- Idempotency factory test: `./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.service.InvocationExecutionFactoryTest'`
- Sync coordinator test: `./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.service.InvocationServiceDispatchTest'`
- Registry resolver test: `./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.registry.FunctionSpecResolverTest'`
- Full control-plane verification: `./gradlew :control-plane:test`
- Optional affected module verification: `./gradlew :control-plane-modules:async-queue:test -PcontrolPlaneModules=all`

---

### Task 1: Make Dispatch Slot Release Idempotent Per Attempt

**Files:**
- Create: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandlerSlotReleaseTest.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionRecord.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java`

- [ ] **Step 1: Run impact analysis before editing symbols**

Run:

```bash
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"ExecutionRecord","direction":"upstream"}'
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"ExecutionCompletionHandler","direction":"upstream"}'
```

Expected: identify direct callers/tests and no ignored HIGH/CRITICAL warning.

- [ ] **Step 2: Write the failing duplicate-completion test**

Create `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandlerSlotReleaseTest.java`:

```java
package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExecutionCompletionHandlerSlotReleaseTest {

    @Test
    void completeExecution_duplicateTerminalCallback_releasesDispatchSlotOnlyOnce() {
        ExecutionStore store = new ExecutionStore();
        CountingEnqueuer enqueuer = new CountingEnqueuer();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store,
                enqueuer,
                mock(DispatcherRouter.class),
                new Metrics(new SimpleMeterRegistry())
        );
        InvocationTask task = task("exec-duplicate", "fn");
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        record.markRunning();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("ok")));
        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("late-duplicate")));

        assertThat(enqueuer.releases()).isEqualTo(1);
        store.shutdown();
    }

    @Test
    void completeExecution_retryResetsSlotReleaseForNextAttempt() {
        ExecutionStore store = new ExecutionStore();
        CountingEnqueuer enqueuer = new CountingEnqueuer();
        ExecutionCompletionHandler handler = new ExecutionCompletionHandler(
                store,
                enqueuer,
                mock(DispatcherRouter.class),
                new Metrics(new SimpleMeterRegistry())
        );
        InvocationTask task = task("exec-retry", "fn");
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
        store.put(record);
        record.markRunning();

        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.error("ERR", "first")));
        record.markRunning();
        handler.completeExecution(task.executionId(), DispatchResult.warm(InvocationResult.success("ok")));

        assertThat(enqueuer.releases()).isEqualTo(2);
        store.shutdown();
    }

    private static InvocationTask task(String executionId, String functionName) {
        FunctionSpec spec = new FunctionSpec(
                functionName,
                "image",
                null,
                Map.of(),
                null,
                1_000,
                1,
                10,
                2,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        );
        return new InvocationTask(
                executionId,
                functionName,
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        );
    }

    private static final class CountingEnqueuer implements InvocationEnqueuer {
        private final AtomicInteger releases = new AtomicInteger();

        @Override
        public boolean enqueue(InvocationTask task) {
            return true;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void releaseDispatchSlot(String functionName) {
            releases.incrementAndGet();
        }

        int releases() {
            return releases.get();
        }
    }
}
```

- [ ] **Step 3: Run the test and verify RED**

Run:

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandlerSlotReleaseTest'
```

Expected: `completeExecution_duplicateTerminalCallback_releasesDispatchSlotOnlyOnce` fails because releases are `2`.

- [ ] **Step 4: Implement minimal slot-release state**

In `ExecutionRecord`, add mutable state and methods:

```java
private boolean dispatchSlotReleased;

public synchronized boolean markDispatchSlotReleased() {
    if (dispatchSlotReleased) {
        return false;
    }
    dispatchSlotReleased = true;
    return true;
}
```

In `ExecutionRecord.resetForRetry(...)`, reset the flag:

```java
this.dispatchSlotReleased = false;
```

In `ExecutionCompletionHandler.completeExecution(...)`, replace the direct release with per-record release:

```java
String functionName = record.task().functionName();
releaseDispatchSlotOnce(record, functionName);
if (isTerminal(record.state())) {
    return;
}
```

Add helper:

```java
private void releaseDispatchSlotOnce(ExecutionRecord record, String functionName) {
    if (record.markDispatchSlotReleased()) {
        enqueuer.releaseDispatchSlot(functionName);
    }
}
```

Keep `dispatch(...)` missing-record behavior as direct `releaseDispatchSlot(task.functionName())`, because there is no record to guard.

- [ ] **Step 5: Run GREEN verification**

Run:

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandlerSlotReleaseTest'
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.service.InvocationServiceRetryTest'
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.service.InvocationServiceDispatchTest'
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionRecord.java \
        control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java \
        control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandlerSlotReleaseTest.java
git commit -m "Make dispatch slot release idempotent"
```

---

### Task 2: Stop Evicting Active Executions

**Files:**
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionStoreEvictionTest.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionStore.java`

- [ ] **Step 1: Run impact analysis before editing symbols**

Run:

```bash
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"ExecutionStore","direction":"upstream"}'
```

Expected: identify direct users in invocation service/tests and no ignored HIGH/CRITICAL warning.

- [ ] **Step 2: Change the stale active eviction test to the desired contract**

In `ExecutionStoreEvictionTest`, replace `eviction_removesStaleRunningExecutionAfterStaleTtl` with:

```java
@Test
void eviction_keepsStaleRunningExecutionBecauseCompletionStillOwnsLifecycle() throws Exception {
    ExecutionRecord record = createRecord("exec-stale-running");
    record.markRunning();
    store.put(record);

    backdateEntry("exec-stale-running", Instant.now().minus(Duration.ofMinutes(11)));

    invokeEvictExpired();

    assertThat(store.get("exec-stale-running")).isPresent();
}
```

Add a queued counterpart:

```java
@Test
void eviction_keepsStaleQueuedExecutionBecauseSchedulerStillOwnsLifecycle() throws Exception {
    ExecutionRecord record = createRecord("exec-stale-queued");
    store.put(record);

    backdateEntry("exec-stale-queued", Instant.now().minus(Duration.ofMinutes(11)));

    invokeEvictExpired();

    assertThat(store.get("exec-stale-queued")).isPresent();
}
```

- [ ] **Step 3: Run the test and verify RED**

Run:

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.execution.ExecutionStoreEvictionTest'
```

Expected: the stale running test fails because the current implementation removes the record.

- [ ] **Step 4: Implement minimal retention fix**

In `ExecutionStore.evictExpired()`, remove stale active eviction:

```java
if (!record.isTerminal()) {
    return false;
}
```

Remove the `staleTtl` field and `staleCutoff` local if they become unused.

- [ ] **Step 5: Run GREEN verification**

Run:

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.execution.ExecutionStoreEvictionTest'
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.service.InvocationServiceDispatchTest'
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionStore.java \
        control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/execution/ExecutionStoreEvictionTest.java
git commit -m "Keep active executions in store"
```

---

### Task 3: Preserve Interrupt Semantics in Sync Invocation

**Files:**
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceDispatchTest.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/SyncInvocationCoordinator.java`

- [ ] **Step 1: Run impact analysis before editing symbols**

Run:

```bash
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"SyncInvocationCoordinator","direction":"upstream"}'
```

Expected: affected sync invocation tests/controllers identified.

- [ ] **Step 2: Add failing interrupt test**

Add to `InvocationServiceDispatchTest`:

```java
@Test
void invokeSync_whenInterruptedWhileWaiting_restoresInterruptAndThrowsInterruptedException() {
    FunctionSpec spec = functionSpec("interrupt-fn", ExecutionMode.LOCAL);
    when(functionService.get("interrupt-fn")).thenReturn(Optional.of(spec));
    when(syncQueueGateway.enabled()).thenReturn(false);
    when(enqueuer.enabled()).thenReturn(false);
    when(dispatcherRouter.dispatchLocal(any())).thenReturn(new CompletableFuture<>());

    Thread.currentThread().interrupt();
    try {
        assertThatThrownBy(() -> invocationService.invokeSync(
                "interrupt-fn",
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                1_000
        )).isInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
        Thread.interrupted();
    }
}
```

- [ ] **Step 3: Run the test and verify RED**

Run:

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.service.InvocationServiceDispatchTest.invokeSync_whenInterruptedWhileWaiting_restoresInterruptAndThrowsInterruptedException'
```

Expected: fails because current code returns a timeout response instead of throwing `InterruptedException`.

- [ ] **Step 4: Implement minimal exception split**

In `SyncInvocationCoordinator.invoke(...)`, replace `catch (Exception ex)` with specific catches:

```java
} catch (java.util.concurrent.TimeoutException ex) {
    record.markTimeout();
    metrics.timeout(record.task().functionName());
    return responseMapper.timeoutResponse(record);
} catch (InterruptedException ex) {
    Thread.currentThread().interrupt();
    throw ex;
} catch (java.util.concurrent.ExecutionException ex) {
    record.markTimeout();
    metrics.timeout(record.task().functionName());
    return responseMapper.timeoutResponse(record);
}
```

- [ ] **Step 5: Run GREEN verification**

Run:

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.service.InvocationServiceDispatchTest'
```

Expected: all dispatch tests pass.

- [ ] **Step 6: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/SyncInvocationCoordinator.java \
        control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceDispatchTest.java
git commit -m "Preserve sync invocation interrupts"
```

---

### Task 4: Remove Idempotency Busy Spin

**Files:**
- Create: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationExecutionFactoryTest.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationExecutionFactory.java`

- [ ] **Step 1: Run impact analysis before editing symbols**

Run:

```bash
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"InvocationExecutionFactory","direction":"upstream"}'
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"IdempotencyStore","direction":"upstream"}'
```

Expected: no HIGH/CRITICAL warning ignored.

- [ ] **Step 2: Add failing bounded-wait contention test**

Create `InvocationExecutionFactoryTest` with a test that exposes pending-claim wait behavior without CPU spin:

```java
package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InvocationExecutionFactoryTest {

    @Test
    void createOrReuseExecution_waitsForPendingClaimWithoutTightSpin() throws Exception {
        BlockingExecutionStore store = new BlockingExecutionStore();
        IdempotencyStore idempotencyStore = new IdempotencyStore(Duration.ofMinutes(15));
        InvocationExecutionFactory factory = new InvocationExecutionFactory(store, idempotencyStore);
        FunctionSpec spec = spec("fn");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<InvocationExecutionFactory.ExecutionLookup> first = executor.submit(() ->
                    factory.createOrReuseExecution("fn", spec, new InvocationRequest("payload", Map.of()), "same-key", null));
            store.awaitFirstPutStarted();

            Future<InvocationExecutionFactory.ExecutionLookup> second = executor.submit(() ->
                    factory.createOrReuseExecution("fn", spec, new InvocationRequest("payload", Map.of()), "same-key", null));

            Thread.sleep(50);
            assertThat(second.isDone()).isFalse();

            store.allowFirstPutToComplete();

            assertThat(first.get(1, TimeUnit.SECONDS).record().executionId())
                    .isEqualTo(second.get(1, TimeUnit.SECONDS).record().executionId());
        } finally {
            executor.shutdownNow();
            store.shutdown();
            idempotencyStore.shutdown();
        }
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "image", null, Map.of(), null, 1_000, 1, 10, 1,
                null, ExecutionMode.LOCAL, null, null, null);
    }

    private static final class BlockingExecutionStore extends ExecutionStore {
        private final CountDownLatch firstPutStarted = new CountDownLatch(1);
        private final CountDownLatch allowFirstPutToComplete = new CountDownLatch(1);
        private boolean first = true;

        @Override
        public synchronized void put(ExecutionRecord record) {
            if (first) {
                first = false;
                firstPutStarted.countDown();
                try {
                    allowFirstPutToComplete.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            super.put(record);
        }

        void awaitFirstPutStarted() throws InterruptedException {
            assertThat(firstPutStarted.await(1, TimeUnit.SECONDS)).isTrue();
        }

        void allowFirstPutToComplete() {
            allowFirstPutToComplete.countDown();
        }
    }
}
```

This test may pass before the implementation because it checks behavior, not CPU usage. If it passes, add a focused static check test that fails when `InvocationExecutionFactory.java` contains `Thread.onSpinWait()`:

```java
@Test
void createOrReuseExecution_doesNotUseThreadOnSpinWait() throws Exception {
    String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationExecutionFactory.java"));
    assertThat(source).doesNotContain("Thread.onSpinWait()");
}
```

- [ ] **Step 3: Run the test and verify RED**

Run:

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.service.InvocationExecutionFactoryTest'
```

Expected: if the behavioral test passes, the static check fails because `Thread.onSpinWait()` is present.

- [ ] **Step 4: Implement minimal bounded backoff**

Replace both `Thread.onSpinWait();` calls in `InvocationExecutionFactory` with:

```java
parkPendingClaim();
```

Add helper:

```java
private static void parkPendingClaim() {
    java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(1).toNanos());
    if (Thread.currentThread().isInterrupted()) {
        Thread.currentThread().interrupt();
    }
}
```

This is intentionally small: it removes tight CPU spin without changing public method signatures.

- [ ] **Step 5: Run GREEN verification**

Run:

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.service.InvocationExecutionFactoryTest'
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.service.InvocationServiceDispatchTest'
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationExecutionFactory.java \
        control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationExecutionFactoryTest.java
git commit -m "Avoid busy spin in idempotency claims"
```

---

### Task 5: Validate Scaling Replica Bounds at Registration

**Files:**
- Modify: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionSpecResolverTest.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionSpecResolver.java`

- [ ] **Step 1: Run impact analysis before editing symbols**

Run:

```bash
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"FunctionSpecResolver","direction":"upstream"}'
```

Expected: affected registry/autoscaler tests identified.

- [ ] **Step 2: Add failing invalid scaling bounds tests**

Add to `FunctionSpecResolverTest`:

```java
@Test
void resolve_deploymentScalingRejectsMinReplicasGreaterThanMaxReplicas() {
    FunctionSpecResolver resolver = new FunctionSpecResolver(new FunctionDefaults(1_000, 1, 10, 1));
    FunctionSpec spec = new FunctionSpec(
            "fn",
            "image",
            null,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            ExecutionMode.DEPLOYMENT,
            null,
            null,
            new ScalingConfig(ScalingStrategy.INTERNAL, 5, 2, List.of(new ScalingMetric("queue_depth", "5", null)))
    );

    assertThatThrownBy(() -> resolver.resolve(spec))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minReplicas must be <= maxReplicas");
}

@Test
void resolve_deploymentScalingRejectsNegativeReplicaBounds() {
    FunctionSpecResolver resolver = new FunctionSpecResolver(new FunctionDefaults(1_000, 1, 10, 1));
    FunctionSpec spec = new FunctionSpec(
            "fn",
            "image",
            null,
            Map.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            ExecutionMode.DEPLOYMENT,
            null,
            null,
            new ScalingConfig(ScalingStrategy.INTERNAL, -1, 2, List.of(new ScalingMetric("queue_depth", "5", null)))
    );

    assertThatThrownBy(() -> resolver.resolve(spec))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minReplicas must be >= 0");
}
```

- [ ] **Step 3: Run the tests and verify RED**

Run:

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.registry.FunctionSpecResolverTest'
```

Expected: new tests fail because invalid bounds are currently accepted.

- [ ] **Step 4: Implement minimal validation**

In `FunctionSpecResolver.resolveScalingConfig(...)`, compute bounds before constructing `ScalingConfig`:

```java
int minReplicas = Optional.ofNullable(config.minReplicas()).orElse(1);
int maxReplicas = Optional.ofNullable(config.maxReplicas()).orElse(10);
validateReplicaBounds(minReplicas, maxReplicas);
```

Use those values in the returned `ScalingConfig`.

Add helper:

```java
private void validateReplicaBounds(int minReplicas, int maxReplicas) {
    if (minReplicas < 0) {
        throw new IllegalArgumentException("minReplicas must be >= 0");
    }
    if (maxReplicas < 1) {
        throw new IllegalArgumentException("maxReplicas must be >= 1");
    }
    if (minReplicas > maxReplicas) {
        throw new IllegalArgumentException("minReplicas must be <= maxReplicas");
    }
}
```

- [ ] **Step 5: Run GREEN verification**

Run:

```bash
./gradlew :control-plane:test --tests 'it.unimib.datai.nanofaas.controlplane.registry.FunctionSpecResolverTest'
./gradlew :control-plane-modules:autoscaler:test -PcontrolPlaneModules=all
```

Expected: resolver and autoscaler tests pass.

- [ ] **Step 6: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionSpecResolver.java \
        control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionSpecResolverTest.java
git commit -m "Validate scaling replica bounds"
```

---

### Task 6: Refactor Duplicate Invocation Coordinator Helpers

**Files:**
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationResponseMapper.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/SyncInvocationCoordinator.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ReactiveInvocationCoordinator.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationService.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java`

- [ ] **Step 1: Confirm all behavioral fixes are green before refactor**

Run:

```bash
./gradlew :control-plane:test
```

Expected: pass before refactoring.

- [ ] **Step 2: Run impact analysis before editing symbols**

Run:

```bash
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"InvocationResponseMapper","direction":"upstream"}'
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"SyncInvocationCoordinator","direction":"upstream"}'
codex mcp call gitnexus impact '{"repo":"mcFaas","target":"ReactiveInvocationCoordinator","direction":"upstream"}'
```

Expected: no HIGH/CRITICAL warning ignored.

- [ ] **Step 3: Add mapper test for terminal response behavior**

If not already covered, add a test in `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationResponseMapperTest.java`:

```java
@Test
void terminalResponse_mapsTimeoutRecordToTimeoutResponse() {
    InvocationResponseMapper mapper = new InvocationResponseMapper();
    ExecutionRecord record = new ExecutionRecord("exec-1", task("exec-1"));
    record.markTimeout();

    InvocationResponse response = mapper.terminalResponse(record);

    assertThat(response.status()).isEqualTo("timeout");
}
```

Run it and expect RED because `terminalResponse` does not exist yet.

- [ ] **Step 4: Move duplicated terminal response mapping**

Add to `InvocationResponseMapper`:

```java
public InvocationResponse terminalResponse(ExecutionRecord record) {
    ExecutionRecord.Snapshot snapshot = record.snapshot();
    if (snapshot.state() == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.SUCCESS
            || snapshot.state() == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.ERROR) {
        InvocationResult result = snapshot.lastError() == null
                ? InvocationResult.success(snapshot.output())
                : new InvocationResult(false, null, snapshot.lastError());
        return toResponse(record, result);
    }
    if (snapshot.state() == it.unimib.datai.nanofaas.controlplane.execution.ExecutionState.TIMEOUT) {
        return timeoutResponse(record);
    }
    return null;
}
```

Replace private `terminalResponse(...)` in both coordinators with:

```java
InvocationResponse replay = responseMapper.terminalResponse(record);
```

- [ ] **Step 5: Defer enqueue helper refactor unless tests remain green**

Only after Step 4 is green, extract duplicated enqueue logic into a small collaborator or static helper. Keep exact behavior:

```java
static void enqueueOrThrow(InvocationEnqueuer enqueuer, Metrics metrics, ExecutionRecord record) {
    boolean enqueued = enqueuer.enqueue(record.task());
    if (!enqueued) {
        metrics.queueRejected(record.task().functionName());
        throw new QueueFullException();
    }
    metrics.enqueue(record.task().functionName());
}
```

Use this helper in `InvocationService`, `SyncInvocationCoordinator`, `ReactiveInvocationCoordinator`, and `ExecutionCompletionHandler`.

- [ ] **Step 6: Run GREEN verification**

Run:

```bash
./gradlew :control-plane:test
```

Expected: full control-plane suite passes.

- [ ] **Step 7: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationResponseMapper.java \
        control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/SyncInvocationCoordinator.java \
        control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ReactiveInvocationCoordinator.java \
        control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationService.java \
        control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java \
        control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationResponseMapperTest.java
git commit -m "Deduplicate invocation response helpers"
```

---

### Task 7: Final Verification and GitNexus Change Audit

**Files:**
- No production edits expected.

- [ ] **Step 1: Run full control-plane verification**

Run:

```bash
./gradlew :control-plane:test
```

Expected: pass.

- [ ] **Step 2: Run affected optional module tests**

Run:

```bash
./gradlew :control-plane-modules:async-queue:test -PcontrolPlaneModules=all
./gradlew :control-plane-modules:sync-queue:test -PcontrolPlaneModules=all
./gradlew :control-plane-modules:autoscaler:test -PcontrolPlaneModules=all
```

Expected: pass.

- [ ] **Step 3: Run GitNexus detect changes before final commit or PR**

Run:

```bash
codex mcp call gitnexus detect_changes '{"repo":"mcFaas","scope":"all"}'
```

Expected: changed symbols match the planned execution lifecycle, idempotency, sync wait, scaling validation, and refactor scope.

- [ ] **Step 4: Run final status**

Run:

```bash
git status --short
```

Expected: only intended files changed.

---

## Self-Review

- Spec coverage: covers bugs from the review: duplicate slot release, active execution eviction, interrupt handling, idempotency busy-spin, scaling bounds, and duplicate helper simplification.
- Placeholder scan: no task contains placeholder instructions.
- Type consistency: code snippets use existing package names and constructor shapes from the current repository.
- Known risk: Task 4’s first behavioral test may already pass because CPU spin is hard to assert behaviorally. The static check is intentionally included as the RED guard for removing `Thread.onSpinWait()`.
