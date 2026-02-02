# Sync Queue Backpressure Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a dedicated sync queue with admission control, queue timeouts, and Micrometer metrics, returning HTTP 429 with Retry-After on backpressure.

**Architecture:** Introduce a SyncQueueService (global, in-memory) with an admission controller and wait estimator. A dedicated SyncScheduler dequeues sync requests and dispatches them while sharing per-function concurrency slots from QueueManager. InvocationService routes sync requests into the sync queue; controller returns 429 with Retry-After and a reason header on rejections.

**Tech Stack:** Java 21, Spring Boot, Micrometer, JUnit 5.

**Skills:** @superpowers:test-driven-development, @superpowers:verification-before-completion

---

### Task 1: Sync queue configuration properties and defaults

**Files:**
- Create: `control-plane/src/main/java/com/nanofaas/controlplane/config/SyncQueueProperties.java`
- Modify: `control-plane/src/main/resources/application.yml`
- Test: `control-plane/src/test/java/com/nanofaas/controlplane/config/SyncQueuePropertiesTest.java`

**Step 1: Write the failing test**

```java
package com.nanofaas.controlplane.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncQueuePropertiesTest {
    @Test
    void bindsConfiguredValues() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("syncQueue.enabled", "true")
                .withProperty("syncQueue.admissionEnabled", "true")
                .withProperty("syncQueue.maxDepth", "200")
                .withProperty("syncQueue.maxEstimatedWait", "2s")
                .withProperty("syncQueue.maxQueueWait", "2s")
                .withProperty("syncQueue.retryAfterSeconds", "2")
                .withProperty("syncQueue.throughputWindow", "30s")
                .withProperty("syncQueue.perFunctionMinSamples", "50");

        SyncQueueProperties props = Binder.get(env)
                .bind("syncQueue", Bindable.of(SyncQueueProperties.class))
                .get();

        assertTrue(props.enabled());
        assertTrue(props.admissionEnabled());
        assertEquals(200, props.maxDepth());
        assertEquals(Duration.ofSeconds(2), props.maxEstimatedWait());
        assertEquals(Duration.ofSeconds(2), props.maxQueueWait());
        assertEquals(2, props.retryAfterSeconds());
        assertEquals(Duration.ofSeconds(30), props.throughputWindow());
        assertEquals(50, props.perFunctionMinSamples());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :control-plane:test --tests com.nanofaas.controlplane.config.SyncQueuePropertiesTest`  
Expected: FAIL with "cannot find symbol: class SyncQueueProperties".

**Step 3: Write minimal implementation**

```java
package com.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "syncQueue")
@Validated
public record SyncQueueProperties(
        boolean enabled,
        boolean admissionEnabled,
        int maxDepth,
        Duration maxEstimatedWait,
        Duration maxQueueWait,
        int retryAfterSeconds,
        Duration throughputWindow,
        int perFunctionMinSamples
) {
}
```

**Step 4: Add defaults to application.yml**

```yaml
syncQueue:
  enabled: true
  admissionEnabled: true
  maxDepth: 200
  maxEstimatedWait: 2s
  maxQueueWait: 2s
  retryAfterSeconds: 2
  throughputWindow: 30s
  perFunctionMinSamples: 50
```

**Step 5: Run test to verify it passes**

Run: `./gradlew :control-plane:test --tests com.nanofaas.controlplane.config.SyncQueuePropertiesTest`  
Expected: PASS.

**Step 6: Commit**

```bash
git add control-plane/src/main/java/com/nanofaas/controlplane/config/SyncQueueProperties.java \
  control-plane/src/main/resources/application.yml \
  control-plane/src/test/java/com/nanofaas/controlplane/config/SyncQueuePropertiesTest.java
git commit -m "feat: add sync queue config properties"
```

---

### Task 2: Wait estimator + admission controller

**Files:**
- Create: `control-plane/src/main/java/com/nanofaas/controlplane/sync/WaitEstimator.java`
- Create: `control-plane/src/main/java/com/nanofaas/controlplane/sync/SyncQueueAdmissionController.java`
- Create: `control-plane/src/main/java/com/nanofaas/controlplane/sync/SyncQueueAdmissionResult.java`
- Create: `control-plane/src/main/java/com/nanofaas/controlplane/sync/SyncQueueRejectReason.java`
- Test: `control-plane/src/test/java/com/nanofaas/controlplane/sync/WaitEstimatorTest.java`
- Test: `control-plane/src/test/java/com/nanofaas/controlplane/sync/SyncQueueAdmissionControllerTest.java`

**Step 1: Write the failing tests (wait estimator)**

```java
package com.nanofaas.controlplane.sync;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaitEstimatorTest {
    @Test
    void usesPerFunctionWhenEnoughSamples() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        estimator.recordDispatch("fn", now.minusSeconds(9));
        estimator.recordDispatch("fn", now.minusSeconds(8));
        estimator.recordDispatch("fn", now.minusSeconds(7));

        double est = estimator.estimateWaitSeconds("fn", 6, now);

        assertEquals(20.0, est, 0.01);
    }

    @Test
    void fallsBackToGlobalWhenSamplesInsufficient() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        estimator.recordDispatch("other", now.minusSeconds(9));
        estimator.recordDispatch("other", now.minusSeconds(8));
        estimator.recordDispatch("other", now.minusSeconds(7));
        estimator.recordDispatch("fn", now.minusSeconds(9));

        double est = estimator.estimateWaitSeconds("fn", 3, now);

        assertEquals(10.0, est, 0.01);
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :control-plane:test --tests com.nanofaas.controlplane.sync.WaitEstimatorTest`  
Expected: FAIL with "cannot find symbol: class WaitEstimator".

**Step 3: Write minimal implementation**

```java
package com.nanofaas.controlplane.sync;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class WaitEstimator {
    private final Duration window;
    private final int perFunctionMinSamples;
    private final Deque<Instant> globalEvents = new ConcurrentLinkedDeque<>();
    private final Map<String, Deque<Instant>> perFunctionEvents = new ConcurrentHashMap<>();

    public WaitEstimator(Duration window, int perFunctionMinSamples) {
        this.window = window;
        this.perFunctionMinSamples = perFunctionMinSamples;
    }

    public void recordDispatch(String functionName, Instant now) {
        globalEvents.addLast(now);
        perFunctionEvents
                .computeIfAbsent(functionName, ignored -> new ConcurrentLinkedDeque<>())
                .addLast(now);
        prune(globalEvents, now);
        prune(perFunctionEvents.get(functionName), now);
    }

    public double estimateWaitSeconds(String functionName, int queueDepth, Instant now) {
        double perFunctionThroughput = throughput(perFunctionEvents.get(functionName), now);
        if (perFunctionSamples(functionName, now) >= perFunctionMinSamples && perFunctionThroughput > 0) {
            return queueDepth / perFunctionThroughput;
        }
        double globalThroughput = throughput(globalEvents, now);
        if (globalThroughput <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return queueDepth / globalThroughput;
    }

    private int perFunctionSamples(String functionName, Instant now) {
        Deque<Instant> events = perFunctionEvents.get(functionName);
        if (events == null) {
            return 0;
        }
        prune(events, now);
        return events.size();
    }

    private double throughput(Deque<Instant> events, Instant now) {
        if (events == null) {
            return 0.0;
        }
        prune(events, now);
        double seconds = Math.max(1.0, window.toSeconds());
        return events.size() / seconds;
    }

    private void prune(Deque<Instant> events, Instant now) {
        if (events == null) {
            return;
        }
        Instant cutoff = now.minus(window);
        while (true) {
            Instant first = events.peekFirst();
            if (first == null || !first.isBefore(cutoff)) {
                return;
            }
            events.pollFirst();
        }
    }
}
```

**Step 4: Write the failing tests (admission controller)**

```java
package com.nanofaas.controlplane.sync;

import com.nanofaas.controlplane.config.SyncQueueProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncQueueAdmissionControllerTest {
    @Test
    void rejectsWhenDepthExceeded() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 1, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        SyncQueueAdmissionController controller = new SyncQueueAdmissionController(props, estimator);

        SyncQueueAdmissionResult result = controller.evaluate("fn", 1, Instant.parse("2026-02-01T00:00:10Z"));

        assertFalse(result.accepted());
        assertEquals(SyncQueueRejectReason.DEPTH, result.reason());
    }

    @Test
    void rejectsWhenEstimatedWaitTooHigh() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, true, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        estimator.recordDispatch("fn", now.minusSeconds(9));
        estimator.recordDispatch("fn", now.minusSeconds(8));
        estimator.recordDispatch("fn", now.minusSeconds(7));
        SyncQueueAdmissionController controller = new SyncQueueAdmissionController(props, estimator);

        SyncQueueAdmissionResult result = controller.evaluate("fn", 6, now);

        assertFalse(result.accepted());
        assertEquals(SyncQueueRejectReason.EST_WAIT, result.reason());
    }

    @Test
    void acceptsWhenUnderLimits() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, true, 10, Duration.ofSeconds(30), Duration.ofSeconds(30), 2, Duration.ofSeconds(30), 3
        );
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        estimator.recordDispatch("fn", now.minusSeconds(9));
        estimator.recordDispatch("fn", now.minusSeconds(8));
        estimator.recordDispatch("fn", now.minusSeconds(7));
        SyncQueueAdmissionController controller = new SyncQueueAdmissionController(props, estimator);

        SyncQueueAdmissionResult result = controller.evaluate("fn", 1, now);

        assertTrue(result.accepted());
    }
}
```

**Step 5: Run tests to verify they fail**

Run: `./gradlew :control-plane:test --tests com.nanofaas.controlplane.sync.SyncQueueAdmissionControllerTest`  
Expected: FAIL with "cannot find symbol: class SyncQueueAdmissionController".

**Step 6: Write minimal implementation**

```java
package com.nanofaas.controlplane.sync;

import com.nanofaas.controlplane.config.SyncQueueProperties;

import java.time.Instant;

public class SyncQueueAdmissionController {
    private final SyncQueueProperties props;
    private final WaitEstimator estimator;

    public SyncQueueAdmissionController(SyncQueueProperties props, WaitEstimator estimator) {
        this.props = props;
        this.estimator = estimator;
    }

    public SyncQueueAdmissionResult evaluate(String functionName, int depth, Instant now) {
        if (depth >= props.maxDepth()) {
            return SyncQueueAdmissionResult.rejected(SyncQueueRejectReason.DEPTH, Double.POSITIVE_INFINITY);
        }
        double estWaitSeconds = estimator.estimateWaitSeconds(functionName, depth, now);
        if (props.admissionEnabled() && estWaitSeconds > props.maxEstimatedWait().toSeconds()) {
            return SyncQueueAdmissionResult.rejected(SyncQueueRejectReason.EST_WAIT, estWaitSeconds);
        }
        return SyncQueueAdmissionResult.accepted(estWaitSeconds);
    }
}
```

```java
package com.nanofaas.controlplane.sync;

public record SyncQueueAdmissionResult(
        boolean accepted,
        SyncQueueRejectReason reason,
        double estimatedWaitSeconds
) {
    public static SyncQueueAdmissionResult accepted(double estWaitSeconds) {
        return new SyncQueueAdmissionResult(true, null, estWaitSeconds);
    }

    public static SyncQueueAdmissionResult rejected(SyncQueueRejectReason reason, double estWaitSeconds) {
        return new SyncQueueAdmissionResult(false, reason, estWaitSeconds);
    }
}
```

```java
package com.nanofaas.controlplane.sync;

public enum SyncQueueRejectReason {
    DEPTH,
    EST_WAIT,
    TIMEOUT
}
```

**Step 7: Run tests to verify they pass**

Run: `./gradlew :control-plane:test --tests com.nanofaas.controlplane.sync.WaitEstimatorTest \
  --tests com.nanofaas.controlplane.sync.SyncQueueAdmissionControllerTest`  
Expected: PASS.

**Step 8: Commit**

```bash
git add control-plane/src/main/java/com/nanofaas/controlplane/sync \
  control-plane/src/test/java/com/nanofaas/controlplane/sync
git commit -m "feat: add sync queue admission and wait estimator"
```

---

### Task 3: Sync queue service, metrics, and exceptions

**Files:**
- Create: `control-plane/src/main/java/com/nanofaas/controlplane/sync/SyncQueueItem.java`
- Create: `control-plane/src/main/java/com/nanofaas/controlplane/sync/SyncQueueMetrics.java`
- Create: `control-plane/src/main/java/com/nanofaas/controlplane/sync/SyncQueueRejectedException.java`
- Create: `control-plane/src/main/java/com/nanofaas/controlplane/sync/SyncQueueService.java`
- Test: `control-plane/src/test/java/com/nanofaas/controlplane/sync/SyncQueueServiceTest.java`

**Step 1: Write the failing tests**

```java
package com.nanofaas.controlplane.sync;

import com.nanofaas.common.model.ExecutionMode;
import com.nanofaas.common.model.FunctionSpec;
import com.nanofaas.common.model.InvocationRequest;
import com.nanofaas.controlplane.config.SyncQueueProperties;
import com.nanofaas.controlplane.execution.ExecutionRecord;
import com.nanofaas.controlplane.execution.ExecutionStore;
import com.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncQueueServiceTest {
    @Test
    void rejectsWhenQueueIsFull() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 1, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueService service = new SyncQueueService(props, store, estimator, metrics, Clock.systemUTC());

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null);
        InvocationTask task1 = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1);
        InvocationTask task2 = new InvocationTask("e2", "fn", spec, new InvocationRequest("two", Map.of()), null, null, Instant.now(), 1);
        store.put(new ExecutionRecord("e1", task1));
        store.put(new ExecutionRecord("e2", task2));

        service.enqueueOrThrow(task1);

        SyncQueueRejectedException ex = assertThrows(SyncQueueRejectedException.class, () -> service.enqueueOrThrow(task2));
        assertEquals(SyncQueueRejectReason.DEPTH, ex.reason());
    }

    @Test
    void timesOutQueuedItem() {
        Instant t0 = Instant.parse("2026-02-01T00:00:00Z");
        Clock fixed = Clock.fixed(t0, ZoneOffset.UTC);
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueService service = new SyncQueueService(props, store, estimator, metrics, fixed);

        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null);
        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, t0, 1);
        ExecutionRecord record = new ExecutionRecord("e1", task);
        store.put(record);

        service.enqueueOrThrow(task);

        service.pollReady(t0.plusSeconds(3));

        assertTrue(record.completion().isDone());
        assertEquals("QUEUE_TIMEOUT", record.completion().join().error().code());
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew :control-plane:test --tests com.nanofaas.controlplane.sync.SyncQueueServiceTest`  
Expected: FAIL with "cannot find symbol: class SyncQueueService".

**Step 3: Write minimal implementation**

```java
package com.nanofaas.controlplane.sync;

import com.nanofaas.controlplane.scheduler.InvocationTask;

import java.time.Instant;

public record SyncQueueItem(
        InvocationTask task,
        Instant enqueuedAt
) {
}
```

```java
package com.nanofaas.controlplane.sync;

public class SyncQueueRejectedException extends RuntimeException {
    private final SyncQueueRejectReason reason;
    private final int retryAfterSeconds;

    public SyncQueueRejectedException(SyncQueueRejectReason reason, int retryAfterSeconds) {
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public SyncQueueRejectReason reason() {
        return reason;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
```

```java
package com.nanofaas.controlplane.sync;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SyncQueueMetrics {
    private final MeterRegistry registry;
    private final Map<String, Counter> rejectedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> timedOutCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> admittedCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> waitTimers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perFunctionDepth = new ConcurrentHashMap<>();
    private final AtomicInteger globalDepth = new AtomicInteger();
    private final Timer globalWaitTimer;

    public SyncQueueMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("sync_queue_depth", globalDepth, AtomicInteger::get).register(registry);
        this.globalWaitTimer = Timer.builder("sync_queue_wait_seconds").register(registry);
    }

    public void registerFunction(String functionName) {
        perFunctionDepth.computeIfAbsent(functionName, name -> {
            AtomicInteger depth = new AtomicInteger();
            Gauge.builder("sync_queue_depth", depth, AtomicInteger::get)
                    .tag("function", name)
                    .register(registry);
            return depth;
        });
    }

    public void admitted(String functionName) {
        counter(admittedCounters, "sync_queue_admitted_total", functionName).increment();
        globalDepth.incrementAndGet();
        perFunctionDepth.computeIfAbsent(functionName, ignored -> new AtomicInteger()).incrementAndGet();
    }

    public void dequeued(String functionName) {
        globalDepth.decrementAndGet();
        AtomicInteger depth = perFunctionDepth.get(functionName);
        if (depth != null) {
            depth.decrementAndGet();
        }
    }

    public void rejected(String functionName) {
        counter(rejectedCounters, "sync_queue_rejected_total", functionName).increment();
    }

    public void timedOut(String functionName) {
        counter(timedOutCounters, "sync_queue_timedout_total", functionName).increment();
    }

    public void recordWait(String functionName, long waitMillis) {
        globalWaitTimer.record(waitMillis, TimeUnit.MILLISECONDS);
        waitTimer(functionName).record(waitMillis, TimeUnit.MILLISECONDS);
    }

    private Counter counter(Map<String, Counter> map, String name, String function) {
        return map.computeIfAbsent(function, key -> Counter.builder(name)
                .tag("function", function)
                .register(registry));
    }

    private Timer waitTimer(String function) {
        return waitTimers.computeIfAbsent(function, key -> Timer.builder("sync_queue_wait_seconds")
                .tag("function", function)
                .register(registry));
    }
}
```

```java
package com.nanofaas.controlplane.sync;

import com.nanofaas.common.model.InvocationResult;
import com.nanofaas.controlplane.config.SyncQueueProperties;
import com.nanofaas.controlplane.execution.ExecutionRecord;
import com.nanofaas.controlplane.execution.ExecutionStore;
import com.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class SyncQueueService {
    private final SyncQueueProperties props;
    private final ExecutionStore executionStore;
    private final WaitEstimator estimator;
    private final SyncQueueMetrics metrics;
    private final Clock clock;
    private final BlockingQueue<SyncQueueItem> queue;
    private final SyncQueueAdmissionController admissionController;

    public SyncQueueService(SyncQueueProperties props,
                            ExecutionStore executionStore,
                            SyncQueueMetrics metrics) {
        this(props,
                executionStore,
                new WaitEstimator(props.throughputWindow(), props.perFunctionMinSamples()),
                metrics,
                Clock.systemUTC());
    }

    SyncQueueService(SyncQueueProperties props,
                     ExecutionStore executionStore,
                     WaitEstimator estimator,
                     SyncQueueMetrics metrics,
                     Clock clock) {
        this.props = props;
        this.executionStore = executionStore;
        this.estimator = estimator;
        this.metrics = metrics;
        this.clock = clock;
        this.queue = new LinkedBlockingQueue<>(props.maxDepth());
        this.admissionController = new SyncQueueAdmissionController(props, estimator);
    }

    public boolean enabled() {
        return props.enabled();
    }

    public int retryAfterSeconds() {
        return props.retryAfterSeconds();
    }

    public void enqueueOrThrow(InvocationTask task) {
        Instant now = clock.instant();
        SyncQueueAdmissionResult decision = admissionController.evaluate(task.functionName(), queue.size(), now);
        if (!decision.accepted()) {
            metrics.rejected(task.functionName());
            throw new SyncQueueRejectedException(decision.reason(), props.retryAfterSeconds());
        }
        if (!queue.offer(new SyncQueueItem(task, now))) {
            metrics.rejected(task.functionName());
            throw new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, props.retryAfterSeconds());
        }
        metrics.registerFunction(task.functionName());
        metrics.admitted(task.functionName());
    }

    public SyncQueueItem peekReady(Instant now) {
        while (true) {
            SyncQueueItem item = queue.peek();
            if (item == null) {
                return null;
            }
            if (isTimedOut(item, now)) {
                queue.poll();
                timeout(item);
                continue;
            }
            return item;
        }
    }

    public SyncQueueItem pollReady(Instant now) {
        SyncQueueItem item = queue.poll();
        if (item != null) {
            metrics.dequeued(item.task().functionName());
            long waitMillis = Duration.between(item.enqueuedAt(), now).toMillis();
            metrics.recordWait(item.task().functionName(), waitMillis);
        }
        return item;
    }

    public void recordDispatched(String functionName, Instant now) {
        estimator.recordDispatch(functionName, now);
    }

    private boolean isTimedOut(SyncQueueItem item, Instant now) {
        return item.enqueuedAt().plus(props.maxQueueWait()).isBefore(now);
    }

    private void timeout(SyncQueueItem item) {
        ExecutionRecord record = executionStore.get(item.task().executionId()).orElse(null);
        if (record != null) {
            record.markTimeout();
            record.completion().complete(InvocationResult.error("QUEUE_TIMEOUT", "Queue wait exceeded"));
        }
        metrics.timedOut(item.task().functionName());
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew :control-plane:test --tests com.nanofaas.controlplane.sync.SyncQueueServiceTest`  
Expected: PASS.

**Step 5: Commit**

```bash
git add control-plane/src/main/java/com/nanofaas/controlplane/sync \
  control-plane/src/test/java/com/nanofaas/controlplane/sync/SyncQueueServiceTest.java
git commit -m "feat: add sync queue service and metrics"
```

---

### Task 4: Sync scheduler and shared concurrency slots

**Files:**
- Modify: `control-plane/src/main/java/com/nanofaas/controlplane/queue/QueueManager.java`
- Create: `control-plane/src/main/java/com/nanofaas/controlplane/scheduler/SyncScheduler.java`
- Test: `control-plane/src/test/java/com/nanofaas/controlplane/scheduler/SyncSchedulerTest.java`

**Step 1: Write the failing test**

```java
package com.nanofaas.controlplane.scheduler;

import com.nanofaas.common.model.ExecutionMode;
import com.nanofaas.common.model.FunctionSpec;
import com.nanofaas.common.model.InvocationRequest;
import com.nanofaas.controlplane.config.SyncQueueProperties;
import com.nanofaas.controlplane.execution.ExecutionRecord;
import com.nanofaas.controlplane.execution.ExecutionStore;
import com.nanofaas.controlplane.queue.QueueManager;
import com.nanofaas.controlplane.sync.SyncQueueMetrics;
import com.nanofaas.controlplane.sync.SyncQueueService;
import com.nanofaas.controlplane.sync.WaitEstimator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncSchedulerTest {
    @Test
    void dispatchesWhenSlotAvailable() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry());
        FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null, 1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null);
        queueManager.getOrCreate(spec);

        SyncQueueProperties props = new SyncQueueProperties(
                true, false, 10, Duration.ofSeconds(2), Duration.ofSeconds(2), 2, Duration.ofSeconds(30), 3
        );
        ExecutionStore store = new ExecutionStore();
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 3);
        SyncQueueMetrics metrics = new SyncQueueMetrics(new SimpleMeterRegistry());
        SyncQueueService queue = new SyncQueueService(props, store, estimator, metrics, Clock.systemUTC());

        InvocationTask task = new InvocationTask("e1", "fn", spec, new InvocationRequest("one", Map.of()), null, null, Instant.now(), 1);
        store.put(new ExecutionRecord("e1", task));
        queue.enqueueOrThrow(task);

        AtomicInteger dispatchCount = new AtomicInteger();
        SyncScheduler scheduler = new SyncScheduler(queueManager, queue, (t) -> dispatchCount.incrementAndGet());

        scheduler.tickOnce();

        assertEquals(1, dispatchCount.get());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :control-plane:test --tests com.nanofaas.controlplane.scheduler.SyncSchedulerTest`  
Expected: FAIL with "cannot find symbol: class SyncScheduler".

**Step 3: Write minimal implementation**

```java
package com.nanofaas.controlplane.queue;

public FunctionQueueState get(String functionName) {
    return queues.get(functionName);
}

public boolean tryAcquireSlot(String functionName) {
    FunctionQueueState state = queues.get(functionName);
    return state != null && state.tryAcquireSlot();
}

public void releaseSlot(String functionName) {
    FunctionQueueState state = queues.get(functionName);
    if (state != null) {
        state.releaseSlot();
    }
}
```

```java
package com.nanofaas.controlplane.scheduler;

import com.nanofaas.controlplane.queue.QueueManager;
import com.nanofaas.controlplane.sync.SyncQueueItem;
import com.nanofaas.controlplane.sync.SyncQueueService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(prefix = "syncQueue", name = "enabled", havingValue = "true")
public class SyncScheduler implements org.springframework.context.SmartLifecycle {
    private final QueueManager queueManager;
    private final SyncQueueService queue;
    private final Consumer<InvocationTask> dispatch;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nanofaas-sync-scheduler");
        t.setDaemon(false);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long tickMs = 2;

    public SyncScheduler(QueueManager queueManager,
                         SyncQueueService queue,
                         com.nanofaas.controlplane.service.InvocationService invocationService) {
        this(queueManager, queue, invocationService::dispatch);
    }

    SyncScheduler(QueueManager queueManager, SyncQueueService queue, Consumer<InvocationTask> dispatch) {
        this.queueManager = queueManager;
        this.queue = queue;
        this.dispatch = dispatch;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this::loop);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    void tickOnce() {
        Instant now = Instant.now();
        SyncQueueItem item = queue.peekReady(now);
        if (item == null) {
            sleep(tickMs);
            return;
        }
        if (!queueManager.tryAcquireSlot(item.task().functionName())) {
            sleep(tickMs);
            return;
        }
        SyncQueueItem polled = queue.pollReady(now);
        if (polled == null) {
            queueManager.releaseSlot(item.task().functionName());
            return;
        }
        queue.recordDispatched(polled.task().functionName(), now);
        dispatch.accept(polled.task());
    }

    private void loop() {
        while (running.get()) {
            tickOnce();
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :control-plane:test --tests com.nanofaas.controlplane.scheduler.SyncSchedulerTest`  
Expected: PASS.

**Step 5: Commit**

```bash
git add control-plane/src/main/java/com/nanofaas/controlplane/queue/QueueManager.java \
  control-plane/src/main/java/com/nanofaas/controlplane/scheduler/SyncScheduler.java \
  control-plane/src/test/java/com/nanofaas/controlplane/scheduler/SyncSchedulerTest.java
git commit -m "feat: add sync scheduler"
```

---

### Task 5: Wire sync queue into invocation flow + HTTP 429 headers

**Files:**
- Modify: `control-plane/src/main/java/com/nanofaas/controlplane/service/InvocationService.java`
- Modify: `control-plane/src/main/java/com/nanofaas/controlplane/api/InvocationController.java`
- Test: `control-plane/src/test/java/com/nanofaas/controlplane/SyncQueueBackpressureApiTest.java`
- Modify: `control-plane/src/test/java/com/nanofaas/controlplane/service/InvocationServiceRetryTest.java`

**Step 1: Write the failing test**

```java
package com.nanofaas.controlplane;

import com.nanofaas.common.model.ExecutionMode;
import com.nanofaas.common.model.FunctionSpec;
import com.nanofaas.common.model.InvocationRequest;
import com.nanofaas.controlplane.registry.FunctionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.rate.maxPerSecond=1000",
                "nanofaas.defaults.timeoutMs=2000",
                "nanofaas.defaults.concurrency=2",
                "nanofaas.defaults.queueSize=10",
                "nanofaas.defaults.maxRetries=3",
                "syncQueue.enabled=true",
                "syncQueue.admissionEnabled=true",
                "syncQueue.maxEstimatedWait=0s",
                "syncQueue.maxQueueWait=2s",
                "syncQueue.maxDepth=200",
                "syncQueue.retryAfterSeconds=2",
                "syncQueue.throughputWindow=30s",
                "syncQueue.perFunctionMinSamples=50"
        })
@AutoConfigureWebTestClient
class SyncQueueBackpressureApiTest {
    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private FunctionService functionService;

    @Test
    void syncInvokeReturns429WithRetryAfter() {
        functionService.register(new FunctionSpec(
                "echo",
                "local",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                3,
                null,
                ExecutionMode.LOCAL,
                null,
                null
        ));

        webTestClient.post()
                .uri("/v1/functions/echo:invoke")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "2")
                .expectHeader().valueEquals("X-Queue-Reject-Reason", "est_wait");
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :control-plane:test --tests com.nanofaas.controlplane.service.InvocationServiceRetryTest`  
Expected: FAIL with constructor mismatch or missing sync queue wiring.

**Step 3: Write minimal implementation**

```java
// InvocationService constructor signature change:
public InvocationService(FunctionService functionService,
                         QueueManager queueManager,
                         ExecutionStore executionStore,
                         IdempotencyStore idempotencyStore,
                         DispatcherRouter dispatcherRouter,
                         RateLimiter rateLimiter,
                         Metrics metrics,
                         SyncQueueService syncQueueService) {
    ...
    this.syncQueueService = syncQueueService;
}
```

```java
// In invokeSync: use sync queue when enabled, otherwise fallback to existing queue
if (lookup.isNew()) {
    if (syncQueueService.enabled()) {
        syncQueueService.enqueueOrThrow(record.task());
    } else {
        enqueueOrThrow(record);
    }
}

InvocationResult result = record.completion().get(timeoutMs, TimeUnit.MILLISECONDS);
if (result.error() != null && "QUEUE_TIMEOUT".equals(result.error().code())) {
    throw new SyncQueueRejectedException(SyncQueueRejectReason.TIMEOUT, syncQueueService.retryAfterSeconds());
}
```

```java
// InvocationController: add Retry-After and reason header
} catch (SyncQueueRejectedException ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
            .header("X-Queue-Reject-Reason", ex.reason().name().toLowerCase())
            .build();
}
```

```java
// InvocationServiceRetryTest: add SyncQueueService mock and pass it to the constructor
@Mock
private SyncQueueService syncQueueService;

// in setUp():
when(syncQueueService.enabled()).thenReturn(false);

invocationService = new InvocationService(
        functionService,
        queueManager,
        executionStore,
        idempotencyStore,
        dispatcherRouter,
        rateLimiter,
        metrics,
        syncQueueService
);
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :control-plane:test --tests com.nanofaas.controlplane.SyncQueueBackpressureApiTest`  
Expected: PASS.

**Step 5: Commit**

```bash
git add control-plane/src/main/java/com/nanofaas/controlplane/service/InvocationService.java \
  control-plane/src/main/java/com/nanofaas/controlplane/api/InvocationController.java \
  control-plane/src/test/java/com/nanofaas/controlplane/SyncQueueBackpressureApiTest.java \
  control-plane/src/test/java/com/nanofaas/controlplane/service/InvocationServiceRetryTest.java
git commit -m "feat: route sync invoke through sync queue"
```

---

### Task 6: Document metrics

**Files:**
- Modify: `docs/observability.md`

**Step 1: Write the failing doc update**

Add a "Sync queue metrics" subsection with:

```
- sync_queue_depth (global + function tag)
- sync_queue_wait_seconds (global + function tag)
- sync_queue_admitted_total
- sync_queue_rejected_total
- sync_queue_timedout_total
```

**Step 2: Commit**

```bash
git add docs/observability.md
git commit -m "docs: add sync queue metrics"
```
