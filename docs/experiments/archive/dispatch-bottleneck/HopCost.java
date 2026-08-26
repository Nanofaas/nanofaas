import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Quanto costa mandare la preparazione di una richiesta su boundedElastic,
 * quando la CPU e' gia' satura.
 *
 * Non misura il salto in isolamento: quello e' noto e piccolo. Misura la cosa
 * che il control plane vive davvero, cioe' il salto QUANDO i thread runnable
 * sono piu' dei core. boundedElastic dimensiona il pool a 10 x CPU, quindi su
 * due core mette venti thread accanto ai quattro event loop: chi diventa
 * runnable perche' sono arrivati dei byte aspetta il suo turno dietro a tutti.
 *
 * Il lavoro simulato e' deliberatamente banale (una manciata di nanosecondi),
 * perche' e' quello che fa davvero il percorso di ammissione: un lookup in
 * mappa, due controlli e un record. Se il salto costa piu' del lavoro che
 * protegge, il salto e' il costo.
 */
public class HopCost {

    static long work(int n) {          // il lavoro di preparazione, non ottimizzabile via
        long h = n;                    // dead-code elimination
        for (int i = 0; i < 12; i++) h = h * 31 + i;
        return h;
    }

    static long[] run(boolean hop, int iterations) throws Exception {
        long[] latencies = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            final int n = i;
            CountDownLatch done = new CountDownLatch(1);
            long t0 = System.nanoTime();
            Mono<Long> m = Mono.fromCallable(() -> work(n));
            if (hop) m = m.subscribeOn(Schedulers.boundedElastic());
            m.subscribe(v -> done.countDown());
            done.await();
            latencies[i] = System.nanoTime() - t0;
        }
        return latencies;
    }

    static void report(String label, long[] xs) {
        long[] s = xs.clone();
        Arrays.sort(s);
        double mean = Arrays.stream(s).average().orElse(0) / 1000.0;
        System.out.printf("  %-28s media %8.3f us   p50 %8.3f   p95 %8.3f   p99 %9.3f%n",
                label, mean, s[s.length / 2] / 1000.0,
                s[(int) (s.length * 0.95)] / 1000.0, s[(int) (s.length * 0.99)] / 1000.0);
    }

    public static void main(String[] args) throws Exception {
        int burners = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int iterations = 200_000;

        System.out.printf("CPU viste dalla JVM: %d   thread boundedElastic: %d   burner: %d%n",
                Runtime.getRuntime().availableProcessors(),
                Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE, burners);

        AtomicBoolean stop = new AtomicBoolean(false);
        for (int i = 0; i < burners; i++) {
            Thread t = new Thread(() -> { long h = 1; while (!stop.get()) h = work((int) h); });
            t.setDaemon(true);
            t.start();
        }

        run(false, 50_000); run(true, 50_000);          // riscaldamento
        System.out.println("misura (" + iterations + " giri):");
        report("senza salto", run(false, iterations));
        report("con subscribeOn(bElastic)", run(true, iterations));
        stop.set(true);
    }
}
