package it.unimib.datai.nanofaas.controlplane.execution;

// T3 — memoria: il tetto degli esiti e' in NUMERO, non in byte.
//
// application.yml documenta il costo cosi': "Un esito compatto misura 116 byte,
// quindi questo tetto costa circa 12 MB" con max-outcomes=100000. E' vero per un
// esito compatto. Ma A5 trattiene il payload per gli esiti *leggibili* (ASYNC o
// con chiave di idempotenza), e li' il payload e' quello che il chiamante ha
// mandato: 100.000 esiti da 64 KB non sono 12 MB.
//
// Sta nel package dello store perche' il costruttore che prende le proprieta' senza
// MeterRegistry e' package-private.
//
// Misura: heap trattenuto dopo GC con N esiti leggibili di payload crescente, e
// costo di stimarne il peso una volta sola all'inserimento.
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class T3MemoryBench {

    static final int OUTCOMES = 20_000;      // scala ridotta: 100k x 64KB non ci sta, ed e' il punto
    static final int[] PAYLOAD_BYTES = {128, 4096, 65536};

    public static void main(String[] args) throws Exception {
        System.out.println("{");
        System.out.println("  \"bench\": \"T3MemoryBench\",");
        System.out.printf ("  \"params\": {\"outcomes\": %d},%n", OUTCOMES);
        System.out.println("  \"arms\": {");
        for (int i = 0; i < PAYLOAD_BYTES.length; i++) {
            int size = PAYLOAD_BYTES[i];
            long[] r = measure(size);
            System.out.printf("    \"payload-%dB\": {\"retained_mb\": %d, \"per_outcome_bytes\": %d, \"stored\": %d}%s%n",
                    size, r[0] / (1024 * 1024), r[1], r[2], i == PAYLOAD_BYTES.length - 1 ? "" : ",");
        }
        System.out.println("  },");
        System.out.println("  \"weigher_ns_per_call\": {");
        int[] shapes = {128, 4096, 65536};
        for (int i = 0; i < shapes.length; i++) {
            System.out.printf("    \"string-%dB\": %d,%n", shapes[i], weighCost(new String(chars(shapes[i]))));
        }
        System.out.printf("    \"map-64-entries\": %d%n", weighCost(sampleMap()));
        System.out.println("  }");
        System.out.println("}");
        System.out.flush();
        System.exit(0);
    }

    static long[] measure(int payloadBytes) throws Exception {
        ExecutionStoreProperties props = ExecutionStoreProperties.of(
                Duration.ofMinutes(30), Duration.ofMinutes(30), Duration.ofMinutes(30), OUTCOMES);
        ExecutionStore store = new ExecutionStore(props);
        char[] template = new char[payloadBytes];
        java.util.Arrays.fill(template, 'x');

        long before = usedHeap();
        for (int i = 0; i < OUTCOMES; i++) {
            // Un'istanza NUOVA per esito: riusare la stessa String farebbe trattenere
            // all'heap un solo payload condiviso da tutti, e la misura direbbe che i
            // payload grandi non costano nulla. E' l'errore che questa misura faceva.
            String payload = new String(template);
            // ASYNC: readableAfterFinishing, quindi il payload viene trattenuto.
            ExecutionRecord record = new ExecutionRecord("exec-" + i, task("fn", "exec-" + i));
            store.put(record);
            record.markSuccess(payload);
            store.settle(record);
        }
        int stored = store.size();
        long after = usedHeap();
        long retained = Math.max(0, after - before);
        return new long[]{retained, stored == 0 ? 0 : retained / stored, stored};
    }

    /** Costo di stimare il peso una volta: e' cio' che si paga a ogni inserimento. */
    static long weighCost(Object output) {
        Outcome outcome = outcomeWith(output);
        for (int i = 0; i < 20_000; i++) {
            OutcomeWeigher.weigh(outcome);
        }
        int rounds = 200_000;
        long start = System.nanoTime();
        long sink = 0;
        for (int i = 0; i < rounds; i++) {
            sink += OutcomeWeigher.weigh(outcome);
        }
        long ns = (System.nanoTime() - start) / rounds;
        if (sink == Long.MIN_VALUE) System.out.print("");
        return ns;
    }

    static Outcome outcomeWith(Object output) {
        ExecutionRecord record = new ExecutionRecord("w", task("fn", "w"));
        record.markSuccess(output);
        return record.toOutcome();
    }

    static char[] chars(int n) {
        char[] c = new char[n];
        java.util.Arrays.fill(c, 'x');
        return c;
    }

    static Map<String, Object> sampleMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 64; i++) {
            map.put("key-" + i, "value-" + i);
        }
        return map;
    }

    static long usedHeap() throws Exception {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(120);
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    static InvocationTask task(String fn, String id) {
        FunctionSpec spec = new FunctionSpec(fn, "img", null, Map.of(), null,
                1000, 1, 100, 0, null, ExecutionMode.LOCAL, null, null, null);
        return new InvocationTask(id, fn, spec, new InvocationRequest("p", Map.of()),
                null, null, Instant.now(), 1, InvocationKind.ASYNC);
    }
}
