package it.unimib.datai.nanofaas.modules.asyncqueue;

// T2 — batch dello scheduler async: confronto fra 2, 4, 8, 16.
//
// Sta nel package dello scheduler perche' il batch e' un parametro package-private:
// esporlo come proprieta' PRIMA di sapere se conviene cambiarlo sarebbe il contrario
// di quello che chiede il piano.
//
// Il batch e' il numero di dispatch consecutivi che una funzione ottiene prima
// che il ciclo passi ad altre. Piu' largo = meno giri di ciclo per dispatch
// (throughput), ma il turno di una funzione dura di piu' (equita').
//
// Forma dell'accettazione: una funzione molto attiva insieme a molte poco
// attive. Si misurano entrambe le facce:
//   - throughput: dispatch al secondo complessivi
//   - equita': quanto aspetta una funzione poco attiva prima del suo dispatch
//              (e' la metrica che un batch largo peggiora)
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class T2BatchBench {

    static final int[] BATCHES = {2, 4, 8, 16};
    static final int QUIET_FUNCTIONS = 20;
    static final int HOT_TASKS = 2000;
    static final int QUIET_TASKS = 5;      // per funzione poco attiva
    static final int REPS = 5;
    /**
     * Costo di un dispatch. Con un dispatch istantaneo il batch non puo' contare: non c'e'
     * mai una coda su cui il turno di una funzione tolga spazio alle altre, e i quattro
     * bracci risultano indistinguibili misurando solo il rumore del ciclo.
     */
    static final long DISPATCH_COST_NANOS = 50_000;
    static final int CONCURRENCY = 8;      // slot per funzione

    public static void main(String[] args) throws Exception {
        Map<Integer, List<long[]>> results = new java.util.LinkedHashMap<>();
        for (int b : BATCHES) results.put(b, new ArrayList<>());

        // Warm-up, poi ripetizioni con i bracci alternati.
        for (int b : BATCHES) round(b);
        for (int r = 0; r < REPS; r++) {
            for (int b : BATCHES) {
                results.get(b).add(round(b));
            }
        }

        System.out.println("{");
        System.out.println("  \"bench\": \"T2BatchBench\",");
        System.out.printf ("  \"params\": {\"quietFunctions\": %d, \"hotTasks\": %d, \"quietTasksEach\": %d, \"reps\": %d},%n",
                QUIET_FUNCTIONS, HOT_TASKS, QUIET_TASKS, REPS);
        System.out.println("  \"arms\": {");
        for (int i = 0; i < BATCHES.length; i++) {
            int b = BATCHES[i];
            long[] wall = results.get(b).stream().mapToLong(a -> a[0]).sorted().toArray();
            long[] quiet = results.get(b).stream().mapToLong(a -> a[1]).sorted().toArray();
            System.out.printf("    \"batch-%d\": {\"wall_ms_median\": %d, \"quiet_wait_us_p99_median\": %d}%s%n",
                    b, wall[wall.length / 2], quiet[quiet.length / 2],
                    i == BATCHES.length - 1 ? "" : ",");
        }
        System.out.println("  }");
        System.out.println("}");
        System.out.flush();
        System.exit(0);
    }

    /** @return [wallMs, quietWaitP99Ms] */
    static long[] round(int batch) throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueueManager queueManager = new QueueManager(registry);

        int total = HOT_TASKS + QUIET_FUNCTIONS * QUIET_TASKS;
        CountDownLatch done = new CountDownLatch(total);
        Map<String, Long> enqueuedAt = new ConcurrentHashMap<>();
        List<Long> quietWaits = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicLong dispatched = new AtomicLong();

        InvocationService service = org.mockito.Mockito.mock(InvocationService.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            InvocationTask task = invocation.getArgument(0);
            dispatched.incrementAndGet();
            busyFor(DISPATCH_COST_NANOS);
            String id = task.executionId();
            if (id.startsWith("quiet")) {
                Long at = enqueuedAt.get(id);
                if (at != null) {
                    quietWaits.add((System.nanoTime() - at) / 1_000_000);
                }
            }
            queueManager.releaseSlot(task.functionName());
            done.countDown();
            return null;
        }).when(service).dispatch(org.mockito.ArgumentMatchers.any());

        Scheduler scheduler = new Scheduler(queueManager, service, System::nanoTime, batch);
        // Senza questo la coda non sveglia mai il ciclo e non parte nessun dispatch.
        queueManager.setWorkSignaler(scheduler);
        for (int i = 0; i < QUIET_FUNCTIONS; i++) {
            queueManager.getOrCreate(spec("quiet-" + i));
        }
        queueManager.getOrCreate(spec("hot"));
        scheduler.start();

        long start = System.nanoTime();
        for (int i = 0; i < HOT_TASKS; i++) {
            queueManager.enqueue(task("hot", "hot-" + i));
        }
        for (int f = 0; f < QUIET_FUNCTIONS; f++) {
            for (int i = 0; i < QUIET_TASKS; i++) {
                String id = "quiet-" + f + "-" + i;
                enqueuedAt.put(id, System.nanoTime());
                queueManager.enqueue(task("quiet-" + f, id));
            }
        }
        boolean finished = done.await(60, TimeUnit.SECONDS);
        long wallMs = (System.nanoTime() - start) / 1_000_000;
        scheduler.stop();
        if (!finished) {
            throw new IllegalStateException("batch " + batch + ": only " + dispatched.get() + "/" + total);
        }

        long[] waits = quietWaits.stream().mapToLong(Long::longValue).sorted().toArray();
        long p99 = waits.length == 0 ? -1 : waits[Math.min(waits.length - 1, (int) (waits.length * 0.99))];
        return new long[]{wallMs, p99};
    }

    /** Attesa attiva: un sleep avrebbe una granularita' piu' grossa del costo simulato. */
    static void busyFor(long nanos) {
        long deadline = System.nanoTime() + nanos;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img", null, Map.of(), null,
                30000, CONCURRENCY, 10000, 0, null, ExecutionMode.LOCAL, null, null, null);
    }

    static InvocationTask task(String fn, String id) {
        return new InvocationTask(id, fn, spec(fn), new InvocationRequest("p", Map.of()),
                null, null, Instant.now(), 1, InvocationKind.ASYNC);
    }
}
