import com.sun.management.ThreadMXBean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.service.InvocationExecutionFactory;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reproducible local P07 T1/T2 calibration; not a pass/fail microbenchmark. */
public final class P07Calibration {
    private static final int WARMUP_OPERATIONS = 20_000;
    private static final Duration T1_DURATION = Duration.ofSeconds(2);

    private P07Calibration() {
    }

    public static void main(String[] args) throws Exception {
        System.out.printf("environment java=%s vm=%s arch=%s processors=%d maxHeapBytes=%d warmup=%d durationMs=%d%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory(), WARMUP_OPERATIONS, T1_DURATION.toMillis());
        runT1();
        runT2();
    }

    private static void runT1() {
        ExecutionStore store = new ExecutionStore();
        InvocationExecutionFactory factory = factory(store, new IdempotencyStore());
        FunctionSpec spec = spec("t1-sync");
        InvocationRequest request = new InvocationRequest("ok", Map.of());
        for (int i = 0; i < WARMUP_OPERATIONS; i++) {
            complete(factory, store, spec, request, null, InvocationKind.SYNC);
        }

        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = threads.isThreadAllocatedMemorySupported()
                ? threads.getThreadAllocatedBytes(threadId) : -1;
        long started = System.nanoTime();
        long deadline = started + T1_DURATION.toNanos();
        long operations = 0;
        long[] samples = new long[250_000];
        while (System.nanoTime() < deadline) {
            long one = System.nanoTime();
            complete(factory, store, spec, request, null, InvocationKind.SYNC);
            if (operations < samples.length) {
                samples[(int) operations] = System.nanoTime() - one;
            }
            operations++;
        }
        long elapsed = System.nanoTime() - started;
        long allocatedAfter = allocatedBefore >= 0 ? threads.getThreadAllocatedBytes(threadId) : -1;
        int sampled = (int) Math.min(operations, samples.length);
        Arrays.sort(samples, 0, sampled);
        double bytesPerOperation = allocatedBefore >= 0
                ? (double) (allocatedAfter - allocatedBefore) / operations : -1;
        System.out.printf("T1 offered=%d admitted=%d elapsedMs=%.3f opsPerSecond=%.1f p50us=%.3f p95us=%.3f allocatedBytesPerOp=%.1f outcomes=%d live=%d%n",
                operations, operations, elapsed / 1_000_000d, operations * 1_000_000_000d / elapsed,
                percentile(samples, sampled, 0.50) / 1_000d,
                percentile(samples, sampled, 0.95) / 1_000d,
                bytesPerOperation, store.size(), store.inFlightCount());
    }

    private static void runT2() throws Exception {
        Map<String, Object> shapes = new LinkedHashMap<>();
        shapes.put("flat", new ArrayList<>(List.of("alpha", 7L, true)));
        Object deep = "leaf";
        for (int i = 0; i < 20; i++) deep = new ArrayList<>(List.of(deep));
        shapes.put("deep", deep);
        ArrayList<Object> wide = new ArrayList<>();
        for (int i = 0; i < 4_096; i++) wide.add(i);
        shapes.put("wide", wide);
        shapes.put("large", "x".repeat(256 * 1_024));

        for (Map.Entry<String, Object> shape : shapes.entrySet()) {
            ExecutionStore store = new ExecutionStore(
                    ExecutionStoreProperties.of(Duration.ofMinutes(5), Duration.ofMinutes(30),
                            Duration.ofSeconds(30), 1), new SimpleMeterRegistry());
            IdempotencyStore keys = new IdempotencyStore();
            InvocationExecutionFactory factory = factory(store, keys);
            FunctionSpec spec = spec("t2-" + shape.getKey());
            InvocationRequest request = new InvocationRequest(shape.getValue(), Map.of());
            int offered = 200;
            int admitted = 0;
            for (int i = 0; i < offered; i++) {
                String key = "key-" + i;
                complete(factory, store, spec, request, key, InvocationKind.ASYNC);
                admitted++;
            }
            InvocationExecutionFactory.ExecutionLookup replay = factory.createOrReuseExecution(
                    spec.name(), spec, request, "key-0", null, InvocationKind.ASYNC);
            int redispatched = replay.isNew() ? 1 : 0;
            if (replay.isNew()) replay.abandonAdmission();
            System.out.printf("T2 shape=%s offered=%d admitted=%d canonicalBytes=%s outcomes=%d live=%d redispatchAfterEviction=%d%n",
                    shape.getKey(), offered, admitted, canonicalBytes(request), store.size(),
                    store.inFlightCount(), redispatched);
        }
    }

    private static void complete(InvocationExecutionFactory factory, ExecutionStore store,
                                 FunctionSpec spec, InvocationRequest request, String key,
                                 InvocationKind kind) {
        InvocationExecutionFactory.ExecutionLookup lookup = factory.createOrReuseExecution(
                spec.name(), spec, request, key, null, kind);
        if (!lookup.isNew()) return;
        lookup.publishAdmission();
        ExecutionRecord record = lookup.executionRecord();
        record.markSuccess("ok");
        store.settle(record);
    }

    private static InvocationExecutionFactory factory(ExecutionStore store, IdempotencyStore keys) {
        return new InvocationExecutionFactory(store, keys, new Metrics(new SimpleMeterRegistry()));
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "example/image:latest", null, Map.of(), null,
                30_000, 4, 100, 3, null, ExecutionMode.LOCAL, null, null, null);
    }

    private static long percentile(long[] samples, int size, double percentile) {
        return size == 0 ? 0 : samples[Math.min(size - 1, (int) Math.floor((size - 1) * percentile))];
    }

    private static String canonicalBytes(InvocationRequest request) throws Exception {
        try {
            Class<?> limitsType = Class.forName(
                    "it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator$Limits");
            Object limits = limitsType.getConstructor(int.class, int.class, int.class, long.class)
                    .newInstance(32, 16_384, 65_536, 1L << 20);
            Class<?> canonical = Class.forName(
                    "it.unimib.datai.nanofaas.controlplane.input.CanonicalInvocationInput");
            Method method = canonical.getMethod("canonicalize", InvocationRequest.class, limitsType);
            Object result = method.invoke(null, request, limits);
            Method retainedBytes = result.getClass().getMethod("retainedBytes");
            return String.valueOf(retainedBytes.invoke(result));
        } catch (ClassNotFoundException _) {
            return "not-available-on-P00";
        }
    }
}
