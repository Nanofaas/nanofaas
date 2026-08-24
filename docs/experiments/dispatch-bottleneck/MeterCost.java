import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;

/**
 * Quanto costa tenere acceso un meter diagnostico sul percorso caldo.
 *
 * Misurato in blocco e non per operazione: System.nanoTime() su questa macchina
 * quantizza a ~42 ns, quindi cronometrare una singola record() misura l'orologio,
 * non il meter. Il minimo di piu' tornate perche' interessa il costo, non il
 * rumore dello scheduler.
 */
public class MeterCost {
    static double perOp(Runnable r, int n, int rounds) {
        double best = Double.MAX_VALUE;
        for (int k = 0; k < rounds; k++) {
            long t = System.nanoTime();
            for (int i = 0; i < n; i++) r.run();
            best = Math.min(best, (System.nanoTime() - t) / (double) n);
        }
        return best;
    }
    public static void main(String[] a) {
        MeterRegistry reg = new SimpleMeterRegistry();
        Timer timer = Timer.builder("probe").register(reg);
        Counter counter = Counter.builder("probe_total").register(reg);
        long[] sink = new long[1];

        Runnable noop = () -> sink[0]++;
        Runnable rec  = () -> timer.record(1234, TimeUnit.NANOSECONDS);
        Runnable clk  = () -> { long t0 = System.nanoTime(); timer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS); };
        Runnable inc  = () -> counter.increment();

        int n = 2_000_000, rounds = 7;
        perOp(noop, n, 3); perOp(rec, n, 3); perOp(clk, n, 3); perOp(inc, n, 3);   // riscaldamento

        double base = perOp(noop, n, rounds);
        System.out.printf("%n  %-36s %7.2f ns%n", "incremento nudo (riferimento)", base);
        for (String[] p : new String[][]{{"Counter.increment", "i"}, {"Timer.record(valore noto)", "r"},
                                          {"Timer.record + due nanoTime", "c"}}) {
            Runnable r = p[1].equals("i") ? inc : p[1].equals("r") ? rec : clk;
            double v = perOp(r, n, rounds);
            System.out.printf("  %-36s %7.2f ns   (netto %+.2f)%n", p[0], v, v - base);
        }
        System.out.printf("%n  A 2.430 richieste/s, su DUE core:%n");
        for (double ns : new double[]{20, 50, 100}) {
            System.out.printf("    un meter da %3.0f ns  ->  %.4f%% del budget%n", ns, ns * 1e-9 * 2430 / 2 * 100);
        }
    }
}
