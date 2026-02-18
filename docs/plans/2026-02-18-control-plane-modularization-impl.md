# Control Plane Modularization — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Decompose the control-plane into a minimal core with direct sync dispatch and 6 optional modules (async-queue, sync-queue, autoscaler, runtime-config, image-validator, build-metadata).

**Architecture:** Two new core interfaces (`InvocationEnqueuer`, `ScalingMetricsSource`) allow modules to plug in without the core knowing about them. The core handles sync `:invoke` with direct dispatch via semaphore. Modules are loaded via `ServiceLoader` SPI (existing mechanism).

**Tech Stack:** Java 21, Spring Boot 3.5, Spring WebFlux, Gradle multi-project, ServiceLoader SPI.

**Reference:** `docs/plans/2026-02-18-control-plane-modularization.md` (design doc)

---

## Phase 1: Core Interfaces and Refactoring

This phase introduces the new abstractions and refactors the core to be self-sufficient without modules. All existing tests must keep passing throughout.

### Task 1: Create `InvocationEnqueuer` interface

**Files:**
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationEnqueuer.java`
- Create: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/NoOpInvocationEnqueuerTest.java`

**Step 1: Create the interface with no-op default**

```java
package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

public interface InvocationEnqueuer {

    boolean enqueue(InvocationTask task);

    boolean enabled();

    void decrementInFlight(String functionName);

    static InvocationEnqueuer noOp() {
        return NoOpInvocationEnqueuer.INSTANCE;
    }
}
```

```java
package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

enum NoOpInvocationEnqueuer implements InvocationEnqueuer {
    INSTANCE;

    @Override
    public boolean enqueue(InvocationTask task) {
        throw new UnsupportedOperationException("Async queue module not loaded");
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void decrementInFlight(String functionName) {
        // no-op: direct dispatch does not track in-flight via queue
    }
}
```

**Step 2: Write test for no-op behavior**

```java
package it.unimib.datai.nanofaas.controlplane.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoOpInvocationEnqueuerTest {
    private final InvocationEnqueuer enqueuer = InvocationEnqueuer.noOp();

    @Test
    void enabledReturnsFalse() {
        assertFalse(enqueuer.enabled());
    }

    @Test
    void enqueueThrows() {
        assertThrows(UnsupportedOperationException.class, () -> enqueuer.enqueue(null));
    }

    @Test
    void decrementInFlightIsNoOp() {
        assertDoesNotThrow(() -> enqueuer.decrementInFlight("any"));
    }
}
```

**Step 3: Run test**

```bash
./gradlew :control-plane:test --tests '*NoOpInvocationEnqueuerTest'
```

Expected: PASS

**Step 4: Commit**

```bash
git add -A && git commit -m "feat: add InvocationEnqueuer interface with no-op default"
```

---

### Task 2: Create `ScalingMetricsSource` interface

**Files:**
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ScalingMetricsSource.java`
- Create: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/NoOpScalingMetricsSourceTest.java`

**Step 1: Create the interface with no-op default**

```java
package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;

public interface ScalingMetricsSource {

    int queueDepth(String functionName);

    int inFlight(String functionName);

    void setEffectiveConcurrency(String functionName, int value);

    void updateConcurrencyController(String functionName, ConcurrencyControlMode mode, int targetInFlightPerPod);

    static ScalingMetricsSource noOp() {
        return NoOpScalingMetricsSource.INSTANCE;
    }
}
```

```java
package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;

enum NoOpScalingMetricsSource implements ScalingMetricsSource {
    INSTANCE;

    @Override public int queueDepth(String functionName) { return 0; }
    @Override public int inFlight(String functionName) { return 0; }
    @Override public void setEffectiveConcurrency(String fn, int v) {}
    @Override public void updateConcurrencyController(String fn, ConcurrencyControlMode m, int t) {}
}
```

**Step 2: Write test**

```java
package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoOpScalingMetricsSourceTest {
    private final ScalingMetricsSource source = ScalingMetricsSource.noOp();

    @Test
    void returnsZeros() {
        assertEquals(0, source.queueDepth("fn"));
        assertEquals(0, source.inFlight("fn"));
    }

    @Test
    void mutatorsAreNoOp() {
        assertDoesNotThrow(() -> source.setEffectiveConcurrency("fn", 10));
        assertDoesNotThrow(() -> source.updateConcurrencyController("fn", ConcurrencyControlMode.FIXED, 2));
    }
}
```

**Step 3: Run test**

```bash
./gradlew :control-plane:test --tests '*NoOpScalingMetricsSourceTest'
```

Expected: PASS

**Step 4: Commit**

```bash
git add -A && git commit -m "feat: add ScalingMetricsSource interface with no-op default"
```

---

### Task 3: Create `FunctionRegistrationListener` interface

`FunctionService` currently calls `queueManager.getOrCreate()` on registration and `queueManager.remove()` on delete. After modularization, the queue lives in the async-queue module. We need a listener hook so the module can react to function lifecycle events.

**Files:**
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionRegistrationListener.java`

**Step 1: Create the interface**

```java
package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;

public interface FunctionRegistrationListener {
    void onRegister(FunctionSpec spec);
    void onRemove(String functionName);
}
```

No test needed — it's a pure interface with no behavior.

**Step 2: Commit**

```bash
git add -A && git commit -m "feat: add FunctionRegistrationListener interface"
```

---

### Task 4: Refactor `InvocationService` — replace `QueueManager` with `InvocationEnqueuer`

This is the critical refactoring. `InvocationService` currently takes `QueueManager` directly. We replace it with `InvocationEnqueuer` and add a direct dispatch path.

**Files:**
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationService.java`
- Modify: all tests that instantiate `InvocationService` (constructor changes)

**Step 1: Update `InvocationService` constructor**

Replace `QueueManager queueManager` parameter with `InvocationEnqueuer enqueuer`. Replace all `queueManager.enqueue(task)` calls with `enqueuer.enqueue(task)`. Replace `queueManager.decrementInFlight(fn)` with `enqueuer.decrementInFlight(fn)`.

Key changes in `InvocationService`:
- Field: `private final InvocationEnqueuer enqueuer;` (replaces `QueueManager queueManager`)
- Constructor: `InvocationEnqueuer enqueuer` (replaces `QueueManager queueManager`)
- `enqueueOrThrow()`: uses `enqueuer.enqueue(task)` instead of `queueManager.enqueue(task)`
- `completeExecution()`: uses `enqueuer.decrementInFlight(fn)` instead of `queueManager.decrementInFlight(fn)`
- `invokeSync()`/`invokeSyncReactive()`: when `!enqueuer.enabled() && !syncQueueService.enabled()`, do direct dispatch (acquire semaphore, call `dispatch(task)` inline, await completion)

**Step 2: Add direct dispatch path**

When no enqueuer is enabled, `invokeSync` dispatches directly:

```java
if (lookup.isNew()) {
    if (syncQueueService.enabled()) {
        syncQueueService.enqueueOrThrow(record.task());
    } else if (enqueuer.enabled()) {
        enqueueOrThrow(record);
    } else {
        // Direct dispatch — no queue
        dispatch(record.task());
    }
}
```

Note: `dispatch()` already handles completion asynchronously via `future.whenComplete()`, so the sync wait on `record.completion()` still works.

**Step 3: Update `invokeAsync()`**

```java
public InvocationResponse invokeAsync(String functionName, ...) {
    if (!enqueuer.enabled()) {
        throw new UnsupportedOperationException("Async invocation requires the async-queue module");
    }
    // ... existing logic
}
```

The controller catches this and returns 501.

**Step 4: Update `InvocationController.invokeAsync()`**

Add catch for `UnsupportedOperationException`:

```java
} catch (UnsupportedOperationException ex) {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
}
```

**Step 5: Fix all test constructors**

Every test that creates `InvocationService` directly needs updating. Search for `new InvocationService(` in tests. Use `InvocationEnqueuer.noOp()` or a mock where the test doesn't exercise queueing.

Tests to update (search for `InvocationService` constructor usage):
- `InvocationServiceRetryTest`
- `InvocationServiceRetryQueueFullTest`
- `InvocationServiceDispatchTest`
- Any `@SpringBootTest` that wires `InvocationService` (these should auto-wire)

**Step 6: Run all tests**

```bash
./gradlew :control-plane:test
```

Expected: PASS (all existing tests pass)

**Step 7: Commit**

```bash
git add -A && git commit -m "refactor: InvocationService uses InvocationEnqueuer instead of QueueManager"
```

---

### Task 5: Refactor `FunctionService` — replace `QueueManager` and `TargetLoadMetrics` with listeners

`FunctionService` calls `queueManager.getOrCreate()` / `queueManager.remove()` / `targetLoadMetrics.update()` / `targetLoadMetrics.remove()`. These become optional listener hooks.

**Files:**
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/registry/FunctionService.java`
- Modify: all tests that instantiate `FunctionService`

**Step 1: Replace direct dependencies with `List<FunctionRegistrationListener>`**

```java
@Service
public class FunctionService {
    private final FunctionRegistry registry;
    private final FunctionSpecResolver resolver;
    private final KubernetesResourceManager resourceManager;
    private final ImageValidator imageValidator;
    private final List<FunctionRegistrationListener> listeners;

    public FunctionService(FunctionRegistry registry,
                           FunctionDefaults defaults,
                           @Autowired(required = false) KubernetesResourceManager resourceManager,
                           @Autowired(required = false) ImageValidator imageValidator,
                           @Autowired(required = false) List<FunctionRegistrationListener> listeners) {
        this.registry = registry;
        this.resolver = new FunctionSpecResolver(defaults);
        this.resourceManager = resourceManager;
        this.imageValidator = imageValidator == null ? ImageValidator.noOp() : imageValidator;
        this.listeners = listeners == null ? List.of() : listeners;
    }
```

In `register()`: replace `queueManager.getOrCreate(resolved)` and `targetLoadMetrics.update(resolved)` with:
```java
listeners.forEach(l -> l.onRegister(resolved));
```

In `remove()`: replace `queueManager.remove(name)` and `targetLoadMetrics.remove(name)` with:
```java
listeners.forEach(l -> l.onRemove(name));
```

**Step 2: Fix all test constructors for `FunctionService`**

Pass `List.of()` for listeners in tests that don't need them.

**Step 3: Run all tests**

```bash
./gradlew :control-plane:test
```

Expected: PASS

**Step 4: Commit**

```bash
git add -A && git commit -m "refactor: FunctionService uses FunctionRegistrationListener list"
```

---

### Task 6: Register default beans in core configuration

**Files:**
- Create: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/CoreDefaults.java`

**Step 1: Create configuration class**

```java
package it.unimib.datai.nanofaas.controlplane.config;

import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoreDefaults {

    @Bean
    @ConditionalOnMissingBean
    InvocationEnqueuer invocationEnqueuer() {
        return InvocationEnqueuer.noOp();
    }

    @Bean
    @ConditionalOnMissingBean
    ScalingMetricsSource scalingMetricsSource() {
        return ScalingMetricsSource.noOp();
    }

    @Bean
    @ConditionalOnMissingBean
    ImageValidator imageValidator() {
        return ImageValidator.noOp();
    }
}
```

Note: `SyncQueueService` is currently `@Component` with `@ConditionalOnProperty`. During this phase it stays in the core. It will be extracted in Phase 3.

**Step 2: Remove `ImageValidator.noOp()` fallback from `FunctionService` constructor**

Since the core always provides a default bean, `FunctionService` can use plain `@Autowired`:

```java
public FunctionService(FunctionRegistry registry,
                       FunctionDefaults defaults,
                       @Autowired(required = false) KubernetesResourceManager resourceManager,
                       ImageValidator imageValidator,
                       @Autowired(required = false) List<FunctionRegistrationListener> listeners) {
```

**Step 3: Run all tests**

```bash
./gradlew :control-plane:test
```

Expected: PASS

**Step 4: Commit**

```bash
git add -A && git commit -m "feat: register no-op default beans for InvocationEnqueuer, ScalingMetricsSource, ImageValidator"
```

---

### Task 7: Make `SyncQueueService` dependency optional in `InvocationService`

Currently `InvocationService` has `SyncQueueService` as a mandatory constructor param. Make it optional (`@Autowired(required = false)`) with a null-safe guard.

**Files:**
- Modify: `control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationService.java`

**Step 1: Change constructor**

```java
public InvocationService(FunctionService functionService,
                         InvocationEnqueuer enqueuer,
                         ExecutionStore executionStore,
                         IdempotencyStore idempotencyStore,
                         DispatcherRouter dispatcherRouter,
                         RateLimiter rateLimiter,
                         Metrics metrics,
                         @Autowired(required = false) SyncQueueService syncQueueService) {
```

**Step 2: Guard `syncQueueService` calls**

Replace `syncQueueService.enabled()` with `syncQueueService != null && syncQueueService.enabled()`.
Replace `syncQueueService.retryAfterSeconds()` with a safe accessor or default.

**Step 3: Run all tests**

```bash
./gradlew :control-plane:test
```

Expected: PASS

**Step 4: Commit**

```bash
git add -A && git commit -m "refactor: make SyncQueueService optional in InvocationService"
```

---

### Task 8: Verify core works without queue — write core-only integration test

**Files:**
- Create: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/CoreOnlyApiTest.java`

**Step 1: Write a `@SpringBootTest` that boots only the core (no queue, no sync-queue, no autoscaler)**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "sync-queue.enabled=false",
        "nanofaas.admin.runtime-config.enabled=false"
    })
class CoreOnlyApiTest {

    @Autowired
    private WebTestClient webClient;

    @Autowired
    private InvocationEnqueuer enqueuer;

    @Test
    void enqueuerIsNoOp() {
        assertFalse(enqueuer.enabled());
    }

    @Test
    void asyncEndpointReturns501() {
        webClient.post().uri("/v1/functions/test:enqueue")
            .bodyValue(Map.of("input", "data"))
            .exchange()
            .expectStatus().isEqualTo(501);
    }
}
```

This test validates that the core boots and works standalone.

**Step 2: Run test**

```bash
./gradlew :control-plane:test --tests '*CoreOnlyApiTest'
```

Expected: PASS

**Step 3: Commit**

```bash
git add -A && git commit -m "test: add CoreOnlyApiTest validating core works without queue modules"
```

---

## Phase 2: Extract async-queue Module

### Task 9: Create async-queue module structure

**Files:**
- Create: `control-plane-modules/async-queue/build.gradle`
- Create: `control-plane-modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/AsyncQueueModule.java`
- Create: `control-plane-modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/AsyncQueueConfiguration.java`
- Create: `control-plane-modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/QueueBackedEnqueuer.java`
- Create: `control-plane-modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/QueueBackedMetricsSource.java`
- Create: `control-plane-modules/async-queue/src/main/resources/META-INF/services/it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule`
- Move: `QueueManager`, `FunctionQueueState`, `Scheduler`, `WorkSignaler` from `control-plane` to this module

**Step 1: Create `build.gradle`**

```groovy
plugins {
    id 'java-library'
    id 'io.spring.dependency-management'
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.boot:spring-boot-dependencies:${springBootVersion}"
    }
}

dependencies {
    implementation project(':common')
    implementation project(':control-plane')
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'io.micrometer:micrometer-core'
}
```

Note: depends on `:control-plane` for `InvocationEnqueuer`, `ScalingMetricsSource`, `InvocationService`, etc.

**Step 2: Create `QueueBackedEnqueuer`**

Implements `InvocationEnqueuer` by delegating to `QueueManager`:

```java
package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;

public class QueueBackedEnqueuer implements InvocationEnqueuer {
    private final QueueManager queueManager;

    public QueueBackedEnqueuer(QueueManager queueManager) {
        this.queueManager = queueManager;
    }

    @Override
    public boolean enqueue(InvocationTask task) {
        return queueManager.enqueue(task);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void decrementInFlight(String functionName) {
        queueManager.decrementInFlight(functionName);
    }
}
```

**Step 3: Create `QueueBackedMetricsSource`**

Implements `ScalingMetricsSource` by delegating to `QueueManager`:

```java
package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;

public class QueueBackedMetricsSource implements ScalingMetricsSource {
    private final QueueManager queueManager;

    public QueueBackedMetricsSource(QueueManager queueManager) {
        this.queueManager = queueManager;
    }

    @Override public int queueDepth(String fn) { return queueManager.get(fn) != null ? queueManager.get(fn).queued() : 0; }
    @Override public int inFlight(String fn) { return queueManager.get(fn) != null ? queueManager.get(fn).inFlight() : 0; }
    @Override public void setEffectiveConcurrency(String fn, int v) { queueManager.setEffectiveConcurrency(fn, v); }
    @Override public void updateConcurrencyController(String fn, ConcurrencyControlMode m, int t) { queueManager.updateConcurrencyController(fn, m, t); }
}
```

**Step 4: Create `AsyncQueueConfiguration`**

Registers all beans and implements `FunctionRegistrationListener`:

```java
@Configuration
public class AsyncQueueConfiguration {

    @Bean
    QueueManager queueManager(MeterRegistry meterRegistry) {
        return new QueueManager(meterRegistry);
    }

    @Bean
    InvocationEnqueuer invocationEnqueuer(QueueManager queueManager) {
        return new QueueBackedEnqueuer(queueManager);
    }

    @Bean
    ScalingMetricsSource scalingMetricsSource(QueueManager queueManager) {
        return new QueueBackedMetricsSource(queueManager);
    }

    @Bean
    Scheduler scheduler(QueueManager queueManager, InvocationService invocationService) {
        return new Scheduler(queueManager, invocationService);
    }

    @Bean
    FunctionRegistrationListener queueRegistrationListener(QueueManager queueManager) {
        return new FunctionRegistrationListener() {
            @Override public void onRegister(FunctionSpec spec) { queueManager.getOrCreate(spec); }
            @Override public void onRemove(String name) { queueManager.remove(name); }
        };
    }
}
```

**Step 5: Create `AsyncQueueModule` SPI + services file**

```java
package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;
import java.util.Set;

public final class AsyncQueueModule implements ControlPlaneModule {
    @Override public String name() { return "async-queue"; }
    @Override public Set<Class<?>> configurationClasses() { return Set.of(AsyncQueueConfiguration.class); }
}
```

ServiceLoader file `META-INF/services/it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule`:
```
it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueModule
```

**Step 6: Move classes out of `control-plane`**

Move `QueueManager`, `FunctionQueueState`, `Scheduler`, `WorkSignaler` source files from `control-plane/src/main/java/...` to `control-plane-modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/`. Update package declarations. Remove `@Component` annotations (beans are registered via `@Bean`).

Keep `QueueFullException` in core (`control-plane/...controlplane/queue/QueueFullException.java`).
Keep `InvocationTask` record in core (`control-plane/...controlplane/scheduler/InvocationTask.java`).

**Step 7: Move related tests**

Move queue/scheduler tests to `control-plane-modules/async-queue/src/test/java/...`:
- `QueueManagerTest`, `QueueManagerBranchTest`, `QueueManagerGaugeCleanupTest`
- `FunctionQueueStateTest`, `FunctionQueueStateFloorTest`
- `SchedulerResilienceTest`

Tests that exercise the full stack (`ControlPlaneApiTest`, E2E tests) stay in `control-plane` and run with `-PcontrolPlaneModules=all`.

**Step 8: Update `settings.gradle`**

Ensure `control-plane-modules:async-queue` is included when selected.

**Step 9: Run all tests**

```bash
# Core-only tests (no modules)
./gradlew :control-plane:test

# Full tests with async-queue
./gradlew :control-plane:test -PcontrolPlaneModules=all

# Async-queue module tests
./gradlew :control-plane-modules:async-queue:test
```

Expected: ALL PASS

**Step 10: Commit**

```bash
git add -A && git commit -m "feat: extract async-queue module from control-plane core"
```

---

## Phase 3: Extract Remaining Modules

### Task 10: Extract sync-queue module

**Files:**
- Create: `control-plane-modules/sync-queue/build.gradle` (depends on `:control-plane-modules:async-queue`)
- Move: `SyncQueueService`, `SyncScheduler`, `SyncQueueAdmissionController`, `WaitEstimator`, `SyncQueueMetrics`, `SyncQueueItem`, `SyncQueueAdmissionResult` to `it.unimib.datai.nanofaas.modules.syncqueue`
- Move: `SyncQueueProperties` to module
- Keep in core: `SyncQueueRejectedException`, `SyncQueueRejectReason` (controller needs them)
- Create: `SyncQueueConfiguration`, `SyncQueueModule`
- Move tests: `SyncQueueServiceTest`, `SyncQueueAdmissionControllerTest`, `WaitEstimatorTest`, `SyncSchedulerTest`, `SyncSchedulerLifecycleTest`, `SyncSchedulerBranchTest`, `SyncSchedulerDispatchExceptionTest`, `SyncQueuePropertiesTest`
- Update: `SyncQueueBackpressureApiTest` — runs with `-PcontrolPlaneModules=all`

`SyncQueueConfiguration` registers `SyncQueueService` as a bean. `InvocationService` already handles null `SyncQueueService` (from Task 7).

**Verify:**
```bash
./gradlew :control-plane:test && ./gradlew :control-plane:test -PcontrolPlaneModules=all
```

**Commit:**
```bash
git add -A && git commit -m "feat: extract sync-queue module"
```

---

### Task 11: Extract autoscaler module

**Files:**
- Create: `control-plane-modules/autoscaler/build.gradle`
- Move: `InternalScaler`, `ScalingMetricsReader`, `ScalingProperties`, `ColdStartTracker`, `StaticPerPodConcurrencyController`, `AdaptivePerPodConcurrencyController`, `AdaptiveConcurrencyState`, `ConcurrencyControlMetrics`, `TargetLoadMetrics` to `it.unimib.datai.nanofaas.modules.autoscaler`
- Create: `AutoscalerConfiguration`, `AutoscalerModule`
- Move tests: `InternalScalerTest`, `InternalScalerBranchTest`, `InternalScalerResilienceTest`, `ScalingMetricsReaderTest`, `ScalingPropertiesTest`, `StaticPerPodConcurrencyControllerTest`, `AdaptivePerPodConcurrencyControllerTest`, `ColdStartTrackerTest`, `TargetLoadMetricsTest`, `TargetLoadMetricsBranchTest`

`AutoscalerConfiguration`:
- Registers `InternalScaler`, `ScalingMetricsReader`, `TargetLoadMetrics`, etc. as beans
- `ScalingMetricsReader` depends on `ScalingMetricsSource` (interface from core, implemented by async-queue or no-op)
- Implements `FunctionRegistrationListener` to call `targetLoadMetrics.update()` / `targetLoadMetrics.remove()`

**Verify:**
```bash
./gradlew :control-plane:test && ./gradlew :control-plane:test -PcontrolPlaneModules=all
```

**Commit:**
```bash
git add -A && git commit -m "feat: extract autoscaler module"
```

---

### Task 12: Extract runtime-config module

**Files:**
- Create: `control-plane-modules/runtime-config/build.gradle`
- Move: `RuntimeConfigService`, `RuntimeConfigApplier`, `RuntimeConfigValidator`, `AdminRuntimeConfigController`, `RuntimeConfigPatch`, `RuntimeConfigSnapshot`, `RevisionMismatchException`, `RuntimeConfigApplyException` to `it.unimib.datai.nanofaas.modules.runtimeconfig`
- Create: `RuntimeConfigConfiguration`, `RuntimeConfigModule`
- Move tests: `RuntimeConfigServiceTest`, `RuntimeConfigApplierTest`, `RuntimeConfigValidatorTest`, `AdminRuntimeConfigIntegrationTest`

`RuntimeConfigApplier` uses `@Autowired(required=false)` for sync-queue properties (optional cross-module dependency).

**Verify:**
```bash
./gradlew :control-plane:test && ./gradlew :control-plane:test -PcontrolPlaneModules=all
```

**Commit:**
```bash
git add -A && git commit -m "feat: extract runtime-config module"
```

---

### Task 13: Extract image-validator module

**Files:**
- Create: `control-plane-modules/image-validator/build.gradle`
- Move: `KubernetesImageValidator` to `it.unimib.datai.nanofaas.modules.imagevalidator`
- Keep in core: `ImageValidator` interface, `ImageValidationException`
- Create: `ImageValidatorConfiguration` (registers `KubernetesImageValidator` as `@Bean ImageValidator`)
- Create: `ImageValidatorModule`
- Move tests: `KubernetesImageValidatorTest`

Core `CoreDefaults` already provides `@ConditionalOnMissingBean ImageValidator` (from Task 6). Module overrides it.

**Verify:**
```bash
./gradlew :control-plane:test && ./gradlew :control-plane:test -PcontrolPlaneModules=all
```

**Commit:**
```bash
git add -A && git commit -m "feat: extract image-validator module"
```

---

## Phase 4: Cleanup and Final Validation

### Task 14: Remove dead code from core

After all extractions, verify no unused imports, dead classes, or orphaned packages remain in `control-plane/src/main/java`. Remove empty packages. Clean up any `@ConditionalOnProperty` annotations for features that are now modules.

**Verify:**
```bash
./gradlew :control-plane:test
./gradlew :control-plane:test -PcontrolPlaneModules=all
./gradlew build -PcontrolPlaneModules=all
```

**Commit:**
```bash
git add -A && git commit -m "chore: remove dead code after module extraction"
```

---

### Task 15: Update documentation

**Files:**
- Modify: `docs/control-plane-modules.md` — update module list, add all 6 modules with descriptions
- Modify: `docs/control-plane.md` — update architecture section to reflect core vs modules
- Modify: `CLAUDE.md` — update architecture overview

**Commit:**
```bash
git add -A && git commit -m "docs: update architecture docs for modularized control-plane"
```

---

### Task 16: Full verification matrix

Run every valid combination to ensure nothing is broken:

```bash
# Core only (no modules)
./gradlew :control-plane:test

# Async only
./gradlew :control-plane:test -PcontrolPlaneModules=async-queue

# Async + sync-queue
./gradlew :control-plane:test -PcontrolPlaneModules=async-queue,sync-queue

# Async + autoscaler
./gradlew :control-plane:test -PcontrolPlaneModules=async-queue,autoscaler

# All modules
./gradlew :control-plane:test -PcontrolPlaneModules=all

# Full build
./gradlew build -PcontrolPlaneModules=all
```

Expected: ALL PASS
