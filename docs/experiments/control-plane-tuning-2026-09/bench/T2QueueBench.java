// T2 - queues: profiling monitors and scans against the REAL code
// (SyncQueueService).
//
// The shape the acceptance asks for: one very active function among many quiet
// ones. It is the shape in which the two suspect costs actually bite:
//
//   1) queuedItems(functionName) is an O(depth) scan under the queue monitor, and
//      SyncQueueWorkloadMetricsSource calls it PER FUNCTION on every scrape:
//      O(functions x depth) with the monitor taken every time.
//   2) rotateReadyScanWindow takes the monitor 1 + up to 64 times (once per
//      rotateReadyHead), on a path P2 made hotter.
//
// It does not compare two implementations: it measures what they cost today, and
// how much they disturb a concurrent enqueue. If the cost does not show, the
// intervention is not made (plan section 6).
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueMetrics;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.WaitEstimator;
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class T2QueueBench {

    static final int DEPTH = 200;          // the default max-depth
    static final int QUIET_FUNCTIONS = 50; // many quiet functions
    static final int SCRAPE_ROUNDS = 2000;
    static final int ROTATE_ROUNDS = 2000;
    static final int WARMUP = 200;
    static final int REPS = 6;

    public static void main(String[] args) throws Exception {
        List<long[]> scrape = new ArrayList<>();
        List<long[]> rotate = new ArrayList<>();
        List<long[]> contention = new ArrayList<>();
        List<long[]> baseline = new ArrayList<>();
        List<long[]> scrapeContention = new ArrayList<>();

        for (int i = 0; i < REPS + 1; i++) {
            SyncQueueService queue = newQueue();
            fill(queue);
            long s = timeScrape(queue);
            long r = timeRotate(queue);
            long quiet = timeEnqueue(queue, false, false);
            long c = timeEnqueue(queue, true, false);
            long withScrape = timeEnqueue(queue, false, true);
            if (i > 0) { // the first pass is warm-up
                scrape.add(new long[]{s});
                rotate.add(new long[]{r});
                baseline.add(new long[]{quiet});
                contention.add(new long[]{c});
                scrapeContention.add(new long[]{withScrape});
            }
        }

        System.out.println("{");
        System.out.println("  \"bench\": \"T2QueueBench\",");
        System.out.printf ("  \"params\": {\"depth\": %d, \"quietFunctions\": %d, \"reps\": %d},%n",
                DEPTH, QUIET_FUNCTIONS, REPS);
        emit("scrape_all_functions_ns", scrape, ",");
        emit("rotate_scan_window_ns", rotate, ",");
        emit("enqueue_uncontended_ns", baseline, ",");
        emit("enqueue_under_rotation_ns", contention, ",");
        emit("enqueue_under_scrape_ns", scrapeContention, "");
        System.out.println("}");
        System.out.flush();
        System.exit(0);
    }

    /** A full scrape: queuedItems(fn) for every function, as the metrics source does. */
    static long timeScrape(SyncQueueService queue) {
        List<String> names = names();
        for (int i = 0; i < WARMUP; i++) {
            for (String n : names) queue.queuedItems(n);
        }
        long start = System.nanoTime();
        for (int i = 0; i < SCRAPE_ROUNDS; i++) {
            for (String n : names) queue.queuedItems(n);
        }
        return (System.nanoTime() - start) / SCRAPE_ROUNDS;
    }

    static long timeRotate(SyncQueueService queue) {
        Instant now = Instant.now();
        for (int i = 0; i < WARMUP; i++) queue.rotateReadyScanWindow(now);
        long start = System.nanoTime();
        for (int i = 0; i < ROTATE_ROUNDS; i++) queue.rotateReadyScanWindow(now);
        return (System.nanoTime() - start) / ROTATE_ROUNDS;
    }

    /**
     * The cost of an enqueue alone, with a concurrent rotator, or with a
     * concurrent scraper. The difference between the three is the monitor
     * contention the intervention would reduce; the isolated cost alone says
     * nothing.
     */
    static long timeEnqueue(SyncQueueService queue, boolean withRotator, boolean withScraper) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        Instant now = Instant.now();
        List<String> names = names();
        Thread noise = null;
        if (withRotator || withScraper) {
            noise = new Thread(() -> {
                while (!stop.get()) {
                    if (withRotator) {
                        queue.rotateReadyScanWindow(now);
                    } else {
                        for (String n : names) queue.queuedItems(n);
                    }
                }
            });
            noise.setDaemon(true);
            noise.start();
        }

        int ops = 2000;
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            // The queue is full: enqueueOrThrow refuses, but still takes the monitor,
            // and that is the cost of interest.
            try {
                queue.enqueueOrThrow(task("hot", "probe-" + i));
            } catch (RuntimeException _) {
                // expected refusal on a full queue
            }
        }
        long elapsed = (System.nanoTime() - start) / ops;
        stop.set(true);
        if (noise != null) {
            noise.join(2000);
        }
        return elapsed;
    }

    static List<String> names() {
        List<String> names = new ArrayList<>();
        names.add("hot");
        for (int i = 0; i < QUIET_FUNCTIONS; i++) names.add("quiet-" + i);
        return names;
    }

    static SyncQueueService newQueue() {
        SyncQueueProperties props = new SyncQueueProperties(
                true, false, DEPTH, Duration.ofSeconds(60), Duration.ofSeconds(60), 2,
                Duration.ofSeconds(30), 3);
        SyncQueueService queue = new SyncQueueService(
                props, new ExecutionStore(), new WaitEstimator(Duration.ofSeconds(30), 3),
                new SyncQueueMetrics(new SimpleMeterRegistry()), Clock.systemUTC(),
                SyncQueueConfigSource.fixed(props.runtimeDefaults()),
                new FunctionCapacityRegistry(), null);
        for (String n : names()) queue.registerFunction(n, 1);
        return queue;
    }

    /** One hot function dominates the queue; the others hold one item each. */
    static void fill(SyncQueueService queue) {
        for (int i = 0; i < QUIET_FUNCTIONS; i++) {
            queue.enqueueOrThrow(task("quiet-" + i, "q-" + i));
        }
        int i = 0;
        while (queue.queuedItems() < DEPTH) {
            queue.enqueueOrThrow(task("hot", "h-" + (i++)));
        }
    }

    static InvocationTask task(String fn, String id) {
        FunctionSpec spec = new FunctionSpec(fn, "img", null, Map.of(), null,
                1000, 1, DEPTH, 0, null, ExecutionMode.LOCAL, null, null, null);
        return new InvocationTask(id, fn, spec, new InvocationRequest("p", Map.of()),
                null, null, Instant.now(), 1, InvocationKind.SYNC);
    }

    static void emit(String name, List<long[]> rs, String sep) {
        long[] v = rs.stream().mapToLong(a -> a[0]).sorted().toArray();
        System.out.printf("  \"%s\": {\"median\": %d, \"min\": %d, \"max\": %d, \"runs\": %d}%s%n",
                name, v[v.length / 2], v[0], v[v.length - 1], v.length, sep);
    }
}
