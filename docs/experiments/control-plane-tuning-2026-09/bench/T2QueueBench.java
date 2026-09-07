// T2 — code: profilo di monitor e scansioni sul codice REALE (SyncQueueService).
//
// Forma richiesta dall'accettazione: una funzione molto attiva insieme a molte
// poco attive. E' la forma in cui i due costi sospetti mordono davvero:
//
//   1) queuedItems(functionName) e' una scansione O(depth) sotto il monitor della
//      coda, e SyncQueueWorkloadMetricsSource la chiama PER FUNZIONE a ogni
//      scrape: costo O(funzioni x profondita') con il monitor preso ogni volta.
//   2) rotateReadyScanWindow prende il monitor 1 + fino a 64 volte (una per
//      rotateReadyHead), su un percorso che P2 ha reso piu' caldo.
//
// Non confronta due implementazioni: misura quanto costano oggi, e quanto
// disturbano l'enqueue in concorrenza. Se il costo non si vede, l'intervento
// non si fa (piano §6).
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

    static final int DEPTH = 200;          // max-depth di default
    static final int QUIET_FUNCTIONS = 50; // molte funzioni poco attive
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
            if (i > 0) { // il primo giro e' warm-up
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

    /** Uno scrape completo: queuedItems(fn) per ogni funzione, come fa il metrics source. */
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
     * Costo di un enqueue da solo, con un rotatore concorrente, o con uno scraper
     * concorrente. La differenza fra i tre e' la contesa sul monitor che
     * l'intervento ridurrebbe; il costo isolato da solo non direbbe nulla.
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
            // La coda e' piena: enqueueOrThrow rifiuta, ma prende comunque il monitor
            // ed e' quello il costo che interessa.
            try {
                queue.enqueueOrThrow(task("hot", "probe-" + i));
            } catch (RuntimeException _) {
                // rifiuto atteso a coda piena
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

    /** Una funzione calda domina la coda; le altre hanno un elemento a testa. */
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
