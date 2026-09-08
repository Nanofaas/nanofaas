package it.unimib.datai.nanofaas.controlplane.service;

// T4 - diagnostics: meter cost on the hot path.
//
// Metrics.metersOrNull takes a synchronized block on a GLOBAL monitor on every
// call - dispatch, success, error, and every timers(fn) - shared by all
// functions. The lock exists to make removal atomic against registration; the
// common case (an existing, non-removed function) does not need it.
//
// T4's acceptance: the same observability in both arms. No metric is removed
// here: this only measures what registering and reading one costs.
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class T4MetricsBench {

    static final int FUNCTIONS = 32;
    static final int OPS_PER_THREAD = 200_000;
    static final int WARMUP = 50_000;
    static final int REPS = 5;
    static final int[] THREADS = {1, 2, 4, 8};

    public static void main(String[] args) throws Exception {
        System.out.println("{");
        System.out.println("  \"bench\": \"T4MetricsBench\",");
        System.out.printf ("  \"params\": {\"functions\": %d, \"opsPerThread\": %d, \"reps\": %d},%n",
                FUNCTIONS, OPS_PER_THREAD, REPS);
        System.out.println("  \"hot_path_ns_per_op\": {");
        for (int i = 0; i < THREADS.length; i++) {
            List<Long> runs = new ArrayList<>();
            for (int r = 0; r < REPS; r++) {
                runs.add(measure(THREADS[i]));
            }
            long[] v = runs.stream().mapToLong(Long::longValue).sorted().toArray();
            System.out.printf("    \"threads-%d\": {\"median\": %d, \"min\": %d, \"max\": %d}%s%n",
                    THREADS[i], v[v.length / 2], v[0], v[v.length - 1],
                    i == THREADS.length - 1 ? "" : ",");
        }
        System.out.println("  }");
        System.out.println("}");
        System.out.flush();
        System.exit(0);
    }

    /** ns per hot-path operation (one dispatch + one outcome + the timer samples). */
    static long measure(int threads) throws Exception {
        Metrics metrics = new Metrics(new SimpleMeterRegistry());
        String[] names = new String[FUNCTIONS];
        for (int i = 0; i < FUNCTIONS; i++) {
            names[i] = "fn-" + i;
            metrics.registerFunction(names[i]);
        }
        for (int i = 0; i < WARMUP; i++) {
            hotPath(metrics, names[i % FUNCTIONS]);
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong totalNanos = new AtomicLong();
        for (int t = 0; t < threads; t++) {
            final int offset = t;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long began = System.nanoTime();
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    hotPath(metrics, names[(i + offset) % FUNCTIONS]);
                }
                totalNanos.addAndGet(System.nanoTime() - began);
                done.countDown();
            });
            worker.setDaemon(true);
            worker.start();
        }
        start.countDown();
        if (!done.await(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out");
        }
        return totalNanos.get() / ((long) threads * OPS_PER_THREAD);
    }

    /** What an invocation actually does: a dispatch, an outcome, three duration samples. */
    static void hotPath(Metrics metrics, String function) {
        metrics.dispatch(function);
        metrics.success(function);
        Metrics.FunctionTimers timers = metrics.timers(function);
        timers.latency().record(3, TimeUnit.MILLISECONDS);
        timers.queueWait().record(1, TimeUnit.MILLISECONDS);
        timers.e2eLatency().record(4, TimeUnit.MILLISECONDS);
    }
}
