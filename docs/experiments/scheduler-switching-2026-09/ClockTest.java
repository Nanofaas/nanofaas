package it.unimib.datai.nanofaas;

import java.lang.management.ManagementFactory;

/**
 * Measures the resolution of the two CPU clocks the benchmark harness can read, by running a busy
 * loop of known duration against each and printing what the clock reports for it.
 *
 * <p>The answer decides whether a 10 % CPU-per-completion budget can be read from this host at all:
 * a clock that answers "10 ms" for a 10 ms loop and "20 ms" for another has a 10 ms step, and a
 * budget of 10 % cannot be resolved from a total of a few hundred milliseconds of CPU.
 *
 * <p>Standalone, like {@code SchedulerSwitchBenchmark}: compiled against the same classpath and run
 * with {@code java it.unimib.datai.nanofaas.ClockTest}. Not a test — it asserts nothing, it measures.
 */
public final class ClockTest {

    private static final long LOOP_NANOS = 10_000_000L;

    public static void main(String[] args) {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

        System.out.println("A busy loop of 10 000 000 ns, measured by each clock (8 trials):");
        System.out.println("trial  processCpuTime  getCurrentThreadCpuTime");
        for (int trial = 1; trial <= 8; trial++) {
            System.out.printf("%5d  %15d  %24d%n", trial, busy(os::getProcessCpuTime),
                    busy(threads::getCurrentThreadCpuTime));
        }
        System.out.println();
        System.out.println("A busy loop of 1 000 000 ns, measured by getCurrentThreadCpuTime:");
        for (int trial = 1; trial <= 8; trial++) {
            System.out.printf("%5d  %d%n", trial, busy(threads::getCurrentThreadCpuTime, 1_000_000L));
        }
    }

    /** Busy work for the loop's duration; returns what the clock says the work cost. */
    private static long busy(java.util.function.LongSupplier clock) {
        return busy(clock, LOOP_NANOS);
    }

    private static long busy(java.util.function.LongSupplier clock, long nanos) {
        long before = clock.getAsLong();
        long end = System.nanoTime() + nanos;
        long sink = 1;
        while (System.nanoTime() < end) {
            sink = sink * 31 + 7;
        }
        long after = clock.getAsLong();
        if (sink == 0) {
            System.out.println("(impossible)");
        }
        return after - before;
    }
}
