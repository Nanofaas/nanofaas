# Control Plane Hardening Actions 1-4 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix the highest-value control-plane performance and resource-use issues found in the audit: async scheduler spin, sync queue selection cost, metrics/state cleanup, and Kubernetes provisioning churn.

**Architecture:** Keep the current modular design and avoid broad rewrites. Each change is isolated to the owning module, guarded by focused regression tests, and verified with the relevant Gradle module test suite. Use GitNexus impact analysis before editing every symbol named in a task.

**Tech Stack:** Java 21, Spring Boot WebFlux, Reactor Netty WebClient, Micrometer, Fabric8 Kubernetes client, JUnit 5, Mockito, Gradle.

---

## Preflight

**Files:**
- Read: `AGENTS.md`
- Read: `control-plane-modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/Scheduler.java`
- Read: `control-plane-modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncScheduler.java`
- Read: `control-plane-modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueService.java`
- Read: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/Metrics.java`
- Read: `control-plane-modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesResourceManager.java`

**Step 1: Confirm baseline**

Run:

```bash
git status --short
./gradlew :control-plane:test :control-plane-modules:async-queue:test :control-plane-modules:sync-queue:test :control-plane-modules:k8s-deployment-provider:test --continue
```

Expected:
- Note any pre-existing dirty files.
- Tests pass before changes.

**Step 2: Run GitNexus impact analysis before edits**

Run these before touching the related files:

```text
gitnexus_impact({target: "Scheduler", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "SyncScheduler", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "SyncQueueService", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "Metrics", direction: "upstream", repo: "mcFaas"})
gitnexus_impact({target: "KubernetesResourceManager", direction: "upstream", repo: "mcFaas"})
```

Expected:
- Report direct callers and risk before editing.
- If any result is HIGH or CRITICAL, stop and warn the user before continuing.

---

### Task 1: Stop Async Scheduler Busy Loop

**Files:**
- Modify: `control-plane-modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/Scheduler.java`
- Test: `control-plane-modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/SchedulerResilienceTest.java`

**Step 1: Write the failing test**

Add a test that creates one function queue with `concurrency=1`, enqueues two tasks, and makes the first dispatch remain in flight. Verify the scheduler does not repeatedly call `InvocationService.dispatch` for the blocked second task before a slot is released.

Suggested test shape:

```java
@Test
void schedulerDoesNotSpinWhenQueueHasBacklogButNoSlot() {
    QueueManager queueManager = new QueueManager(new SimpleMeterRegistry());
    InvocationService invocationService = mock(InvocationService.class);
    Scheduler scheduler = new Scheduler(queueManager, invocationService);
    scheduler.init();

    FunctionSpec spec = new FunctionSpec(
            "echo", "img", List.of(), Map.of(), null,
            1000, 1, 10, 0, "http://echo", ExecutionMode.POOL,
            RuntimeMode.HTTP, null, null, null
    );
    queueManager.getOrCreate(spec);

    InvocationTask first = task("exec-1", spec);
    InvocationTask second = task("exec-2", spec);
    queueManager.enqueue(first);
    queueManager.enqueue(second);

    scheduler.start();
    await().atMost(Duration.ofSeconds(1))
            .untilAsserted(() -> verify(invocationService, times(1)).dispatch(first));

    Thread.sleep(100);
    verify(invocationService, times(1)).dispatch(any(InvocationTask.class));

    scheduler.stop();
}
```

Adjust helper names to match local test patterns.

**Step 2: Run test to verify failure**

Run:

```bash
./gradlew :control-plane-modules:async-queue:test --tests '*SchedulerResilienceTest.schedulerDoesNotSpinWhenQueueHasBacklogButNoSlot'
```

Expected:
- FAIL, showing repeated dispatch attempts or repeated scheduler activity while no slot is available.

**Step 3: Implement minimal fix**

Change `Scheduler.processFunction` so it only calls `signalWork(functionName)` if at least one task was dispatched or a slot was available and queue still has work. If no slot was acquired, leave wakeup responsibility to `QueueManager.releaseSlot`, which already signals when queued work remains.

Implementation intent:

```java
boolean acquiredAnySlot = false;
int dispatched = 0;
while (running.get() && dispatched < MAX_BATCH_PER_FUNCTION && state.tryAcquireSlot()) {
    acquiredAnySlot = true;
    InvocationTask task = state.poll();
    if (task == null) {
        state.releaseSlot();
        break;
    }
    dispatched++;
    SchedulerDispatchSupport.dispatchWithFailureCleanup(...);
}

if (state.queued() > 0 && dispatched > 0) {
    signalWork(functionName);
}
```

Keep fairness behavior: after dispatching up to `MAX_BATCH_PER_FUNCTION`, requeue the function if it still has backlog.

**Step 4: Run focused and module tests**

Run:

```bash
./gradlew :control-plane-modules:async-queue:test
```

Expected:
- PASS.

**Step 5: Commit**

```bash
git add control-plane-modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/Scheduler.java \
        control-plane-modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/SchedulerResilienceTest.java
git commit -m "Fix async scheduler blocked-queue spin"
```

---

### Task 2: Make Sync Queue Slot Acquisition Safe And Cheaper

**Files:**
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationEnqueuer.java`
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/NoOpInvocationEnqueuer.java`
- Modify: `control-plane-modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/QueueBackedEnqueuer.java`
- Modify: `control-plane-modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncScheduler.java`
- Modify: `control-plane-modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueService.java`
- Test: `control-plane-modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncSchedulerTest.java`
- Test: `control-plane-modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueServiceTest.java`

**Step 1: Run GitNexus impact for interface change**

```text
gitnexus_impact({target: "InvocationEnqueuer", direction: "upstream", repo: "mcFaas"})
```

Expected:
- This may touch core plus queue modules. If risk is HIGH or CRITICAL, report before editing.

**Step 2: Write failing test for failed remove after slot acquire**

Add a test around `SyncScheduler.tickOnce` where `enqueuer.tryAcquireSlot` returns true but the selected queue item is not removed. If direct reproduction through `LinkedBlockingQueue` is hard, use a package-private helper in `SyncQueueService` and test that acquire happens after removal, not inside the selector predicate.

Desired invariant:
- `tryAcquireSlot` is called only for an item that has been removed for dispatch, or any acquired slot is released when dispatch does not happen.

**Step 3: Add non-mutating capacity check**

Extend `InvocationEnqueuer` with a default method:

```java
default boolean hasAvailableSlot(String functionName) {
    return true;
}
```

Implement in `QueueBackedEnqueuer` by delegating to a new `QueueManager.hasAvailableSlot(functionName)`, which reads `FunctionQueueState.inFlight()` and `effectiveConcurrency()` without mutating state.

**Step 4: Refactor sync queue selection**

Change `SyncScheduler.tickOnce` to:

1. Search with `task -> enqueuer.hasAvailableSlot(task.functionName())`.
2. After `pollReadyMatching` returns an item, call `enqueuer.tryAcquireSlot`.
3. If acquire fails because another scheduler/dispatch path raced, do not dispatch; optionally re-enqueue or rely on the item remaining only if not removed. Prefer a new `pollReadyMatchingAndAcquire` helper in `SyncQueueService` only if it can atomically remove and then acquire with rollback.

Keep the current single-thread scheduler assumption, but make the code robust if future tests drive it concurrently.

**Step 5: Reduce scan cost without a full queue rewrite**

Keep the current `LinkedBlockingQueue` for this task, but add a bounded scan limit per tick:

```java
int scanLimit = Math.max(1, Math.min(queue.size(), 64));
```

If no ready item with capacity is found within the limit, use the existing blocked backoff. This avoids scanning hundreds or thousands of entries every 2 ms. Do not introduce per-function queues yet; that belongs in a larger queue architecture change if load tests prove it needed.

**Step 6: Run focused tests**

Run:

```bash
./gradlew :control-plane-modules:sync-queue:test --tests '*SyncSchedulerTest*' --tests '*SyncQueueServiceTest*'
```

Expected:
- PASS.

**Step 7: Run affected module tests**

Run:

```bash
./gradlew :control-plane:test :control-plane-modules:async-queue:test :control-plane-modules:sync-queue:test --continue
```

Expected:
- PASS.

**Step 8: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationEnqueuer.java \
        control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/NoOpInvocationEnqueuer.java \
        control-plane-modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/QueueBackedEnqueuer.java \
        control-plane-modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncScheduler.java \
        control-plane-modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueService.java \
        control-plane-modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/scheduler/SyncSchedulerTest.java \
        control-plane-modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueServiceTest.java
git commit -m "Harden sync queue slot selection"
```

---

### Task 3: Clean Up Per-Function Metrics And Estimator State

**Files:**
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/Metrics.java`
- Modify: `control-plane-modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/AsyncQueueConfiguration.java`
- Modify: `control-plane-modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/sync/WaitEstimator.java`
- Modify: `control-plane-modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueService.java`
- Test: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/MetricsTest.java`
- Test: `control-plane-modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/sync/WaitEstimatorTest.java`

**Step 1: Write failing Metrics cleanup test**

Add a test:

```java
@Test
void removeFunction_removesRegisteredMeters() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Metrics metrics = new Metrics(registry);

    metrics.dispatch("echo");
    assertThat(registry.find("function_dispatch_total").tag("function", "echo").counter()).isNotNull();

    metrics.removeFunction("echo");

    assertThat(registry.find("function_dispatch_total").tag("function", "echo").counter()).isNull();
}
```

**Step 2: Implement Metrics.removeFunction**

Store meter ids in `FunctionMeters` or keep a side map from function name to `Meter.Id` list. Add:

```java
public void removeFunction(String function) {
    FunctionMeters removed = meters.remove(function);
    if (removed == null) {
        return;
    }
    removed.meterIds().forEach(registry::remove);
}
```

Preserve existing hot-path `computeIfAbsent` behavior.

**Step 3: Wire cleanup to lifecycle listener**

In `AsyncQueueConfiguration.queueLifecycleListener`, inject `Metrics` and call `metrics.removeFunction(functionName)` after queue removal. If this broadens constructor wiring too much, create a small core listener in `control-plane` instead.

**Step 4: Write WaitEstimator cleanup test**

Add:

```java
@Test
void removeFunctionState_clearsPerFunctionEvents() {
    WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 1);
    Instant now = Instant.now();

    estimator.recordDispatch("echo", now);
    assertThat(estimator.estimateWaitSeconds("echo", 1, now)).isFinite();

    estimator.removeFunctionState("echo");

    assertThat(estimator.estimateWaitSeconds("echo", 1, now)).isEqualTo(Double.POSITIVE_INFINITY);
}
```

Adjust expected behavior if global throughput fallback should still apply; the important assertion is that per-function state is removed.

**Step 5: Implement WaitEstimator cleanup and pruning cap**

Add:

```java
void removeFunctionState(String functionName) {
    perFunctionEvents.remove(functionName);
}
```

Optionally add a `maxSamplesPerFunction` cap if tests show large unbounded deques under load. Keep this scoped; do not redesign estimator now.

**Step 6: Expose SyncQueueService cleanup**

Add package-private:

```java
void removeFunctionState(String functionName) {
    estimator.removeFunctionState(functionName);
}
```

If no listener currently owns sync queue lifecycle, add one in `SyncQueueConfiguration` or document that sync queue state is global and only estimator cleanup is needed.

**Step 7: Run tests**

Run:

```bash
./gradlew :control-plane:test --tests '*MetricsTest*'
./gradlew :control-plane-modules:sync-queue:test --tests '*WaitEstimatorTest*'
./gradlew :control-plane:test :control-plane-modules:async-queue:test :control-plane-modules:sync-queue:test --continue
```

Expected:
- PASS.

**Step 8: Commit**

```bash
git add control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/Metrics.java \
        control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/MetricsTest.java \
        control-plane-modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/AsyncQueueConfiguration.java \
        control-plane-modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/sync/WaitEstimator.java \
        control-plane-modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueService.java \
        control-plane-modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/sync/WaitEstimatorTest.java
git commit -m "Clean up per-function control-plane state"
```

---

### Task 4: Reduce Kubernetes Provisioning Churn

**Files:**
- Modify: `control-plane-modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesResourceManager.java`
- Modify: `control-plane-modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesDeploymentBuilder.java`
- Modify: `control-plane-modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/config/KubernetesProperties.java`
- Test: `control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesResourceManagerTest.java`
- Test: `control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesDeploymentBuilderTest.java`

**Step 1: Write failing test for provision without delete**

In `KubernetesResourceManagerTest`, verify provisioning uses create-or-replace or server-side apply semantics and does not delete the Deployment/Service first. With Fabric8 mock server, assert resource remains addressable and generation/history is not reset where supported.

Minimum test intent:

```java
@Test
void provision_updatesDeploymentAndServiceWithoutDeletingFirst() {
    FunctionSpec spec = deploymentSpec("echo");

    manager.provision(spec);
    manager.provision(spec);

    // Assert one deployment and one service exist, and no explicit delete action was required.
}
```

If the mock API cannot assert delete calls directly, use resource version/generation behavior or wrap the Fabric8 operations in a package-private adapter for testability.

**Step 2: Implement non-destructive apply**

Replace delete+create at `KubernetesResourceManager.provision` with the least risky supported Fabric8 path for JVM and native image:

Preferred:

```java
client.apps().deployments()
        .inNamespace(resolvedNamespace)
        .resource(deployment)
        .serverSideApply();
```

Fallback if native compatibility is still an issue:

```java
var deployments = client.apps().deployments().inNamespace(resolvedNamespace);
if (deployments.withName(name).get() == null) {
    deployments.resource(deployment).create();
} else {
    deployments.withName(name).patch(PatchContext.of(PatchType.STRATEGIC_MERGE), deployment);
}
```

Use the same approach for Service and HPA.

**Step 3: Delete stale HPA when strategy is not HPA**

Add an explicit else branch:

```java
if (spec.scalingConfig() != null && spec.scalingConfig().strategy() == ScalingStrategy.HPA) {
    applyHpa(spec);
} else {
    client.autoscaling().v2().horizontalPodAutoscalers()
            .inNamespace(resolvedNamespace)
            .withName(KubernetesDeploymentBuilder.deploymentName(spec.name()))
            .delete();
}
```

**Step 4: Make imagePullPolicy configurable**

Add a property to `KubernetesProperties`, for example:

```java
String imagePullPolicy
```

Default to `"IfNotPresent"` for resource efficiency unless existing docs/tests require `"Always"`. Use it in `KubernetesDeploymentBuilder` instead of the hard-coded `"Always"` at container build time.

**Step 5: Update docs if behavior changes**

Modify:
- `docs/k8s.md`
- `docs/control-plane-modules.md`

Document:
- non-destructive deployment reconciliation
- configurable image pull policy
- HPA cleanup behavior

**Step 6: Run tests**

Run:

```bash
./gradlew :control-plane-modules:k8s-deployment-provider:test
```

Expected:
- PASS.

**Step 7: Run broader control-plane tests**

Run:

```bash
./gradlew :control-plane:test :control-plane-modules:k8s-deployment-provider:test :control-plane-modules:image-validator:test --continue
```

Expected:
- PASS.

**Step 8: Commit**

```bash
git add control-plane-modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesResourceManager.java \
        control-plane-modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesDeploymentBuilder.java \
        control-plane-modules/k8s-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/k8s/config/KubernetesProperties.java \
        control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesResourceManagerTest.java \
        control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/dispatch/KubernetesDeploymentBuilderTest.java \
        docs/k8s.md docs/control-plane-modules.md
git commit -m "Reduce Kubernetes deployment reconciliation churn"
```

---

## Final Verification

**Step 1: Run complete affected suite**

```bash
./gradlew :control-plane:test \
          :control-plane-modules:async-queue:test \
          :control-plane-modules:sync-queue:test \
          :control-plane-modules:k8s-deployment-provider:test \
          :control-plane-modules:image-validator:test \
          --continue
```

Expected:
- PASS.

**Step 2: Run GitNexus change detection**

```text
gitnexus_detect_changes({scope: "all", repo: "mcFaas"})
```

Expected:
- Changed symbols and flows match the four planned areas.
- No unexpected high-risk flow is introduced.

**Step 3: Optional full build**

```bash
./gradlew build -PcontrolPlaneModules=all
```

Expected:
- PASS, unless local Docker/K8s prerequisites are unavailable. If unavailable, document the exact skipped/failing prerequisite.
