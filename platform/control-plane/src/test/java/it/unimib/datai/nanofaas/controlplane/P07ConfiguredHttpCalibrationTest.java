package it.unimib.datai.nanofaas.controlplane;

import com.sun.management.ThreadMXBean;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacityProperties;
import it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** Configured-runtime P07 acceptance calibration. Performance numbers are evidence, not thresholds. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.rate.maxPerSecond=100000",
                "nanofaas.registry.path=build/test-p07-configured-http-calibration-functions.json"
        })
@AutoConfigureWebTestClient(timeout = "30s")
class P07ConfiguredHttpCalibrationTest {
    private static final int T1_WARMUP = 100;
    private static final int T1_OFFERED = 1_000;
    private static final int T2_OFFERED_PER_SHAPE = 100;
    private static final List<String> FUNCTIONS = List.of(
            "p07-t1-http", "p07-t2-flat", "p07-t2-deep", "p07-t2-wide", "p07-t2-large");

    @Autowired private WebTestClient client;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private FunctionService functions;
    @Autowired private InvocationEnqueuer enqueuer;
    @Autowired private InvocationCapacity invocations;
    @Autowired private InvocationCapacityProperties properties;
    @Autowired private WaiterCapacity waiters;
    @Autowired private ExecutionStore executions;
    @Autowired private IdempotencyStore keys;
    @MockitoBean private LocalDispatcher dispatcher;

    @AfterEach
    void cleanFunctionsAndVerifyDrain() {
        awaitDrain();
        FUNCTIONS.forEach(functions::remove);
        reset(dispatcher);
    }

    @Test
    void t1CoreOnlySyncUnkeyedThroughConfiguredHttpRuntime() {
        Assumptions.assumeFalse(enqueuer.enabled(), "run with -PcontrolPlaneModules=none");
        assertPublishedDefaults();
        String function = "p07-t1-http";
        register(function);
        when(dispatcher.dispatch(any())).thenReturn(
                CompletableFuture.completedFuture(DispatchResult.warm(InvocationResult.success("ok"))));

        for (int i = 0; i < T1_WARMUP; i++) {
            assertThat(sync(function, "warmup").getStatus().is2xxSuccessful()).isTrue();
        }

        long allocatedBefore = allocatedBytes();
        long started = System.nanoTime();
        long[] latencyNanos = new long[T1_OFFERED];
        int admitted = 0;
        PopulationSampler populations = new PopulationSampler();
        populations.start();
        for (int i = 0; i < T1_OFFERED; i++) {
            long requestStarted = System.nanoTime();
            EntityExchangeResult<byte[]> result = sync(function, "payload");
            latencyNanos[i] = System.nanoTime() - requestStarted;
            if (result.getStatus().is2xxSuccessful()) admitted++;
        }
        populations.close();
        long elapsed = System.nanoTime() - started;
        long allocated = allocationDelta(allocatedBefore, allocatedBytes());
        Arrays.sort(latencyNanos);
        awaitDrain();

        assertThat(admitted).isEqualTo(T1_OFFERED);
        System.out.printf(
                "P07-HTTP-T1 profile=none offered=%d admitted=%d elapsedMs=%.3f opsPerSecond=%.1f "
                        + "p50us=%.3f p95us=%.3f allocatedBytes=%d allocatedBytesPerAdmitted=%.1f "
                        + "%s finalOutcomes=%d finalKeys=%d drainExecutions=%d drainInputBytes=%d "
                        + "drainInputCopyBytes=%d drainWaiters=%d drainLive=%d%n",
                T1_OFFERED, admitted, elapsed / 1_000_000d,
                T1_OFFERED * 1_000_000_000d / elapsed,
                percentile(latencyNanos, 0.50) / 1_000d, percentile(latencyNanos, 0.95) / 1_000d,
                allocated, perAdmission(allocated, admitted), populations.summary(), executions.size(), keys.size(),
                invocations.executionReservedGlobally(), invocations.inputReservedGlobally(),
                invocations.physicalInputCopyReservedGlobally(), waiters.reservedGlobally(),
                executions.inFlightCount());
    }

    @Test
    void t2AsyncKeyedShapesThroughConfiguredQueueHttpRuntime() throws Exception {
        Assumptions.assumeTrue(enqueuer.enabled(), "run with -PcontrolPlaneModules=async-queue");
        assertPublishedDefaults();
        Map<String, Object> shapes = shapes();

        for (Map.Entry<String, Object> shape : shapes.entrySet()) {
            String function = "p07-t2-" + shape.getKey();
            register(function);
            CompletableFuture<Void> releaseRuntime = new CompletableFuture<>();
            AtomicInteger dispatches = new AtomicInteger();
            when(dispatcher.dispatch(any())).thenAnswer(invocation -> {
                InvocationTask task = invocation.getArgument(0);
                dispatches.incrementAndGet();
                return releaseRuntime.thenApply(ignored ->
                        DispatchResult.warm(InvocationResult.success(task.request().input())));
            });

            long[] latencyNanos = new long[T2_OFFERED_PER_SHAPE];
            int admitted = 0;
            int quotaRefused = 0;
            Map<String, Integer> refusalResources = new TreeMap<>();
            long allocatedBefore = allocatedBytes();
            PopulationSampler populations = new PopulationSampler();
            populations.start();
            for (int i = 0; i < T2_OFFERED_PER_SHAPE; i++) {
                long requestStarted = System.nanoTime();
                EntityExchangeResult<byte[]> result = enqueue(function, "key-" + i, shape.getValue());
                latencyNanos[i] = System.nanoTime() - requestStarted;
                if (result.getStatus().value() == 202) {
                    admitted++;
                } else {
                    assertThat(result.getStatus().value()).isEqualTo(429);
                    JsonNode error = objectMapper.readTree(result.getResponseBody());
                    assertThat(error.path("error").asText()).isEqualTo("invocation_quota_exceeded");
                    String resource = error.path("resource").asText();
                    assertThat(resource).isIn("execution", "input", "input_copy");
                    refusalResources.merge(resource, 1, Integer::sum);
                    quotaRefused++;
                }
            }
            releaseRuntime.complete(null);
            awaitDrain();
            populations.close();
            long allocated = allocationDelta(allocatedBefore, allocatedBytes());
            Arrays.sort(latencyNanos);

            int beforeReplay = dispatches.get();
            EntityExchangeResult<byte[]> replay = enqueue(function, "key-0", shape.getValue());
            assertThat(replay.getStatus().value()).isIn(202, 410);
            await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(dispatches).hasValue(beforeReplay));

            assertThat(admitted + quotaRefused).isEqualTo(T2_OFFERED_PER_SHAPE);
            if (shape.getKey().equals("large")) {
                assertThat(refusalResources).containsOnlyKeys("input");
                assertThat(quotaRefused).isPositive();
            } else {
                assertThat(refusalResources).isEmpty();
            }
            System.out.printf(
                    "P07-HTTP-T2 profile=async-queue shape=%s offered=%d admitted=%d quotaRefused=%d "
                            + "refusalResources=%s "
                            + "p50us=%.3f p95us=%.3f allocatedBytes=%d allocatedBytesPerAdmitted=%.1f "
                            + "%s outcomes=%d keys=%d replayStatus=%d redispatchAfterReplay=%d "
                            + "drainExecutions=%d drainInputBytes=%d drainInputCopyBytes=%d drainWaiters=%d "
                            + "drainLive=%d%n",
                    shape.getKey(), T2_OFFERED_PER_SHAPE, admitted, quotaRefused, refusalResources,
                    percentile(latencyNanos, 0.50) / 1_000d, percentile(latencyNanos, 0.95) / 1_000d,
                    allocated, perAdmission(allocated, admitted), populations.summary(), executions.size(), keys.size(),
                    replay.getStatus().value(), dispatches.get() - beforeReplay,
                    invocations.executionReservedGlobally(), invocations.inputReservedGlobally(),
                    invocations.physicalInputCopyReservedGlobally(), waiters.reservedGlobally(),
                    executions.inFlightCount());
            functions.remove(function);
        }
    }

    private EntityExchangeResult<byte[]> sync(String function, Object input) {
        return client.post().uri("/v1/functions/{name}:invoke", function)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InvocationRequest(input, Map.of()))
                .exchange().expectBody().returnResult();
    }

    private EntityExchangeResult<byte[]> enqueue(String function, String key, Object input) {
        return client.post().uri("/v1/functions/{name}:enqueue", function)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InvocationRequest(input, Map.of()))
                .exchange().expectBody().returnResult();
    }

    private void register(String name) {
        functions.remove(name);
        functions.register(new FunctionSpec(name, "local", null, Map.of(), null,
                30_000, 1, 512, 0, null, ExecutionMode.LOCAL, null, null, null));
    }

    private void assertPublishedDefaults() {
        assertThat(properties.getIngressBodyBytes()).isEqualTo(1_048_576L);
        assertThat(properties.getExecutionsGlobal()).isEqualTo(4_096);
        assertThat(properties.getExecutionsPerFunction()).isEqualTo(512);
        assertThat(properties.getCanonicalInputBytesGlobal()).isEqualTo(134_217_728L);
        assertThat(properties.getCanonicalInputBytesPerFunction()).isEqualTo(33_554_432L);
        assertThat(properties.getPhysicalInputCopyBytesGlobal()).isEqualTo(67_108_864L);
        assertThat(properties.getPhysicalInputCopyBytesPerFunction()).isEqualTo(16_777_216L);
        assertThat(properties.getWaitersGlobal()).isEqualTo(8_192);
        assertThat(properties.getWaitersPerFunction()).isEqualTo(1_024);
        assertThat(properties.getMaxInputReferences()).isEqualTo(64);
        assertThat(properties.getRetainedInputMaxDepth()).isEqualTo(32);
        assertThat(properties.getRetainedInputMaxContainerEntries()).isEqualTo(16_384);
        assertThat(properties.getRetainedInputMaxVisitedNodes()).isEqualTo(65_536);
        assertThat(properties.getRetainedInputMaxBytesPerExecution()).isEqualTo(1_048_576L);
    }

    private void awaitDrain() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(invocations.executionReservedGlobally()).isZero();
            assertThat(invocations.inputReservedGlobally()).isZero();
            assertThat(invocations.physicalInputCopyReservedGlobally()).isZero();
            assertThat(waiters.reservedGlobally()).isZero();
            assertThat(executions.inFlightCount()).isZero();
        });
    }

    private static Map<String, Object> shapes() {
        Map<String, Object> shapes = new LinkedHashMap<>();
        shapes.put("flat", new ArrayList<>(List.of("alpha", 7L, true)));
        Object deep = "leaf";
        for (int i = 0; i < 20; i++) deep = new ArrayList<>(List.of(deep));
        shapes.put("deep", deep);
        ArrayList<Object> wide = new ArrayList<>();
        for (int i = 0; i < 4_096; i++) wide.add(i);
        shapes.put("wide", wide);
        shapes.put("large", "x".repeat(256 * 1_024));
        return shapes;
    }

    private static long percentile(long[] sorted, double fraction) {
        return sorted[Math.min(sorted.length - 1, (int) Math.floor((sorted.length - 1) * fraction))];
    }

    private static double perAdmission(long bytes, int admitted) {
        return bytes < 0 || admitted == 0 ? -1 : (double) bytes / admitted;
    }

    private static long allocatedBytes() {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!threads.isThreadAllocatedMemorySupported()) return -1;
        if (!threads.isThreadAllocatedMemoryEnabled()) threads.setThreadAllocatedMemoryEnabled(true);
        long total = 0;
        for (long bytes : threads.getThreadAllocatedBytes(threads.getAllThreadIds())) {
            if (bytes >= 0) total += bytes;
        }
        return total;
    }

    private static long allocationDelta(long before, long after) {
        return before < 0 || after < before ? -1 : after - before;
    }

    private final class PopulationSampler implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean();
        private final Map<String, AtomicLong> peaks = new ConcurrentHashMap<>();
        private Thread thread;

        void start() {
            running.set(true);
            thread = Thread.ofVirtual().name("p07-population-sampler").start(() -> {
                while (running.get()) {
                    sample("peakExecutions", invocations.executionReservedGlobally());
                    sample("peakInputBytes", invocations.inputReservedGlobally());
                    sample("peakInputCopyBytes", invocations.physicalInputCopyReservedGlobally());
                    sample("peakWaiters", waiters.reservedGlobally());
                    sample("peakLive", executions.inFlightCount());
                    sample("peakOutcomes", executions.size());
                    sample("peakKeys", keys.size());
                    LockSupport.parkNanos(100_000);
                }
            });
        }

        private void sample(String name, long value) {
            peaks.computeIfAbsent(name, ignored -> new AtomicLong()).accumulateAndGet(value, Math::max);
        }

        String summary() {
            return "peakExecutions=" + peak("peakExecutions")
                    + " peakInputBytes=" + peak("peakInputBytes")
                    + " peakInputCopyBytes=" + peak("peakInputCopyBytes")
                    + " peakWaiters=" + peak("peakWaiters")
                    + " peakLive=" + peak("peakLive")
                    + " peakOutcomes=" + peak("peakOutcomes")
                    + " peakKeys=" + peak("peakKeys");
        }

        private long peak(String name) {
            AtomicLong value = peaks.get(name);
            return value == null ? 0 : value.get();
        }

        @Override
        public void close() {
            running.set(false);
            if (thread == null) return;
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }
}
