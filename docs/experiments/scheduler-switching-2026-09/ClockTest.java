import java.lang.management.ManagementFactory;

public final class ClockTest {
    public static void main(String[] args) throws Exception {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        System.out.println("processCpuTime: 10 ms busy-loop deltas (ns):");
        for (int i = 0; i < 8; i++) {
            long a = os.getProcessCpuTime();
            long end = System.nanoTime() + 10_000_000L;
            long x = 1;
            while (System.nanoTime() < end) { x = x * 31 + 7; }
            long b = os.getProcessCpuTime();
            System.out.printf("  %d (busy work %d)%n", b - a, x & 1);
        }
        System.out.println("getCurrentThreadCpuTime: 10 ms busy-loop deltas (ns):");
        for (int i = 0; i < 8; i++) {
            long a = threads.getCurrentThreadCpuTime();
            long end = System.nanoTime() + 10_000_000L;
            long x = 1;
            while (System.nanoTime() < end) { x = x * 31 + 7; }
            long b = threads.getCurrentThreadCpuTime();
            System.out.printf("  %d (busy work %d)%n", b - a, x & 1);
        }
        System.out.println("getThreadCpuTime(main): 1 ms busy-loop deltas (ns):");
        long id = Thread.currentThread().getId();
        for (int i = 0; i < 8; i++) {
            long a = threads.getThreadCpuTime(id);
            long end = System.nanoTime() + 1_000_000L;
            long x = 1;
            while (System.nanoTime() < end) { x = x * 31 + 7; }
            long b = threads.getThreadCpuTime(id);
            System.out.printf("  %d (busy work %d)%n", b - a, x & 1);
        }
    }
}
