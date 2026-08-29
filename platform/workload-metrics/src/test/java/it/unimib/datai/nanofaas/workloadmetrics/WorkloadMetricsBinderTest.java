package it.unimib.datai.nanofaas.workloadmetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WorkloadMetricsBinderTest {
    @Test
    void registersFourLazyDuplicateSafeGaugesAndRemovesThem() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Map<String, int[]> values = new java.util.HashMap<>();
        values.put("echo", new int[]{1, 2, 3, 4});
        values.put("other", new int[]{5, 6, 7, 8});
        WorkloadMetricsSource source = new WorkloadMetricsSource() {
            public int queueDepth(String f) { return values.get(f)[0]; }
            public int inFlight(String f) { return values.get(f)[1]; }
            public int effectiveConcurrency(String f) { return values.get(f)[2]; }
            public int dispatchableBacklog(String f) { return values.get(f)[3]; }
        };
        WorkloadMetricsBinder binder = new WorkloadMetricsBinder(meters, source);

        binder.registerFunction("echo");
        binder.registerFunction("echo");
        binder.registerFunction("other");
        assertThat(meters.getMeters()).hasSize(8);
        assertThat(meters.get("function_queue_depth").tag("function", "echo").gauge().value()).isEqualTo(1);
        assertThat(meters.get("function_inFlight").tag("function", "echo").gauge().value()).isEqualTo(2);
        assertThat(meters.get("function_effective_concurrency").tag("function", "echo").gauge().value()).isEqualTo(3);
        assertThat(meters.get("function_dispatchable_backlog").tag("function", "echo").gauge().value()).isEqualTo(4);
        values.get("echo")[0] = 9;
        values.get("echo")[1] = 8;
        values.get("echo")[2] = 7;
        values.get("echo")[3] = 6;
        assertThat(meters.get("function_queue_depth").tag("function", "echo").gauge().value()).isEqualTo(9);
        assertThat(meters.get("function_inFlight").tag("function", "echo").gauge().value()).isEqualTo(8);
        assertThat(meters.get("function_effective_concurrency").tag("function", "echo").gauge().value()).isEqualTo(7);
        assertThat(meters.get("function_dispatchable_backlog").tag("function", "echo").gauge().value()).isEqualTo(6);

        binder.removeFunction("echo");
        assertThat(meters.find("function_queue_depth").tag("function", "echo").gauge()).isNull();
        assertThat(meters.find("function_inFlight").tag("function", "echo").gauge()).isNull();
        assertThat(meters.find("function_effective_concurrency").tag("function", "echo").gauge()).isNull();
        assertThat(meters.find("function_dispatchable_backlog").tag("function", "echo").gauge()).isNull();
        assertThat(meters.get("function_queue_depth").tag("function", "other").gauge().value()).isEqualTo(5);
        assertThat(meters.get("function_inFlight").tag("function", "other").gauge().value()).isEqualTo(6);
        assertThat(meters.get("function_effective_concurrency").tag("function", "other").gauge().value()).isEqualTo(7);
        assertThat(meters.get("function_dispatchable_backlog").tag("function", "other").gauge().value()).isEqualTo(8);
    }

    @Test
    void concurrentRegisterAndRemoveLeavesNoMetersAfterRemoval() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WorkloadMetricsSource source = new WorkloadMetricsSource() {
            public int queueDepth(String f) { return 0; }
            public int inFlight(String f) { return 0; }
            public int effectiveConcurrency(String f) { return 0; }
            public int dispatchableBacklog(String f) { return 0; }
        };
        WorkloadMetricsBinder binder = new WorkloadMetricsBinder(meters, source);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CyclicBarrier phase = new CyclicBarrier(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            workers.submit(() -> runRegisterRace(binder, phase, failure));
            workers.submit(() -> runRemoveRace(binder, phase, failure));
            workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(failure).hasValue(null);
            binder.removeFunction("echo");
            assertThat(meters.getMeters()).isEmpty();
        } finally {
            workers.shutdownNow();
            binder.removeFunction("echo");
        }
    }

    private static void runRegisterRace(WorkloadMetricsBinder binder, CyclicBarrier phase,
                                        AtomicReference<Throwable> failure) {
        try {
            for (int i = 0; i < 1_000; i++) {
                phase.await();
                binder.registerFunction("echo");
                phase.await();
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private static void runRemoveRace(WorkloadMetricsBinder binder, CyclicBarrier phase,
                                      AtomicReference<Throwable> failure) {
        try {
            for (int i = 0; i < 1_000; i++) {
                phase.await();
                binder.removeFunction("echo");
                phase.await();
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

}
