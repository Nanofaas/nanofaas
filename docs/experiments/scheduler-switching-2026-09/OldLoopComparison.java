package it.unimib.datai.nanofaas;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import it.unimib.datai.nanofaas.execution.EngineDispatch;
import it.unimib.datai.nanofaas.execution.EngineQueueSnapshot;
import it.unimib.datai.nanofaas.execution.EngineReadiness;
import it.unimib.datai.nanofaas.execution.PendingEntry;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.execution.StrategyRegistry;
import it.unimib.datai.nanofaas.modules.asyncqueue.FunctionQueueState;
import it.unimib.datai.nanofaas.modules.asyncqueue.PerFunctionSchedulingStrategy;
import it.unimib.datai.nanofaas.modules.asyncqueue.QueueManager;
import it.unimib.datai.nanofaas.modules.asyncqueue.Scheduler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

/**
 * The refactor's own cost, measured: the <em>old</em> async scheduling loop against the <em>new</em>
 * engine, on one corpus, in one process, under one service model (issue #208, Task 12e).
 *
 * <h2>What is measured, and what it is not</h2>
 *
 * The hypothesis is named and concrete: the old async loop dispatches up to
 * {@code Scheduler.DEFAULT_MAX_BATCH_PER_FUNCTION} = 2 attempts back to back per function visit
 * ({@code Scheduler.java:31}, {@code :208}), while the new engine takes exactly one claim per pass
 * ({@code SchedulerEngine.selectAndClaim} → one {@code carry}). That is the loop-layer difference
 * the refactor introduced, and it is the subject.
 *
 * <p>This is a <em>profile comparison</em> — level 2 of the spec's taxonomy
 * ({@code spec:316}: «Questo misura un profilo, non attribuisce il risultato al solo algoritmo»),
 * not level 1, which the spec defines as one engine with only the strategy swapped
 * ({@code spec:315}). Nothing here may be presented as satisfying level 1.
 *
 * <h2>Two arms, one dispatch path each</h2>
 *
 * {@link OldArm} drives the real {@link Scheduler} over the real {@link QueueManager} and
 * {@link FunctionQueueState}, dispatching into {@link InvocationDispatch}. {@link NewArm} drives the
 * real {@link SchedulerEngine} with the real {@link PerFunctionSchedulingStrategy} — the port of
 * that same loop — dispatching into {@link EngineDispatch}. The driver takes the place of the
 * transport/lifecycle on the far side of each interface: the seat the real adapter occupies, not a
 * second path beside it. The old loop is never re-wired into the engine; a re-wired old loop would
 * acquire leases from a relocated registry and dispatch into a rewritten transport, a configuration
 * that exists in no revision.
 *
 * <h2>Why the two arms run in one process</h2>
 *
 * CPU and post-GC heap are process-wide and attribute to no scheduler (M11), so a comparison of them
 * across two JVMs measures the JVMs. Here both arms run in the same JVM, with the same thread count
 * (one scheduler/worker thread each, one shared driver thread), the same heap, and the same
 * allocation profile, alternating repetition by repetition. The corpus, the offered rate, the
 * service model, the contract deadline and the useful-completion classification are one code path
 * shared by both arms; only the arm's own dispatch implementation varies.
 *
 * <h2>Why the same file as the committed harness</h2>
 *
 * This class is in package {@code it.unimib.datai.nanofaas} and is compiled together with
 * {@code SchedulerSwitchBenchmark.java}, so the corpus is <em>the committed one</em> —
 * {@link SchedulerSwitchBenchmark.Profiles} and {@link SchedulerSwitchBenchmark.Profile} are used
 * directly, with no copy and no drift — and the span, warm-up, depth-sampling cadence and
 * trailing-window grid are {@link SchedulerSwitchBenchmark}'s own constants. It reuses
 * {@code summarize.py}'s settlement rule and JSONL schema for the same reason: a restated corpus or a
 * restated schema is a second thing that can drift from the one being compared.
 *
 * <h2>One reflected constructor, and why</h2>
 *
 * {@link QueueManager}'s constructor is package-private in
 * {@code it.unimib.datai.nanofaas.modules.asyncqueue}, and this class cannot be in that package and
 * in {@code SchedulerSwitchBenchmark}'s at once. Exactly one constructor is reached by reflection
 * ({@link #newQueueManager}); nothing else in either loop is. Every other old-loop type —
 * {@link Scheduler}, {@link FunctionQueueState}, {@link QueueManager#getOrCreate},
 * {@link QueueManager#enqueue}, {@link QueueManager#remove} — is reached through its ordinary API,
 * as {@code PerFunctionSchedulingStrategyTraceComparisonTest} already does in-tree.
 *
 * <h2>Output</h2>
 *
 * One JSON object per line on stdout: {@code header} once, then one {@code sample} per measured run,
 * in the committed harness's schema and with its field names, so {@code summarize.py} reads it.
 * {@code run-old.sh} tees stdout into {@code raw/}. No JSON library: the JDK is enough.
 */
public final class OldLoopComparison {

    /** The old async loop, as its own arm. */
    static final String OLD_ARM = "old-async (no change)";
    /**
     * The new engine's no-change arm, driven by the strategy that ports the old loop. The label is
     * the committed harness's own ({@code Arm.PER_FUNCTION_NO_CHANGE}), so a sample of this arm and
     * a sample of that one describe the same configuration and can be read side by side.
     */
    static final String NEW_ARM = SchedulerSwitchBenchmark.Arm.PER_FUNCTION_NO_CHANGE.label();
    static final String NEW_STRATEGY = SchedulerSwitchBenchmark.PER_FUNCTION;

    /**
     * The committed span, warm-up, depth cadence and trailing-window grid. Read off
     * {@link SchedulerSwitchBenchmark} rather than restated: a harness that restated them could
     * drift from the campaign it is being compared against.
     */
    static final int SPAN_MS = SchedulerSwitchBenchmark.SPAN_MS;
    static final int WARMUP_MS = SchedulerSwitchBenchmark.WARMUP_MS;
    static final int DEPTH_SAMPLE_MS = SchedulerSwitchBenchmark.DEPTH_SAMPLE_MS;
    static final int DEPTH_SAMPLES = SchedulerSwitchBenchmark.DEPTH_SAMPLES;
    static final int[] TRAILING_WINDOWS_MS = SchedulerSwitchBenchmark.TRAILING_WINDOWS_MS;

    /** Completed-attempt end-to-end latencies, in nanos. See the committed harness's note. */
    static final int SAMPLE_CAPACITY = 1 << 20;
    static final long[] LATENCY = new long[SAMPLE_CAPACITY];
    static final int[] LATENCY_FUNCTION = new int[SAMPLE_CAPACITY];
    /** When each recorded sample was admitted: what puts it in one trailing window or another. */
    static final long[] LATENCY_ADMITTED = new long[SAMPLE_CAPACITY];
    static final long[] SCRATCH = new long[SAMPLE_CAPACITY];
    static int sampleCount;
    static boolean sampleOverflow;

    /**
     * The profiles this comparison covers, in the brief's included set minus one.
     *
     * <p>Derived from {@link SchedulerSwitchBenchmark.Profiles}, never restated. Excluded here:
     * anything with 500 sporadic functions (the old async loop bounds admission per function, so
     * 501 × queueSize admits offers the engine's single global bound refuses — no alignment of one
     * number makes the arms reject the same offers at the same times), {@code heterogeneous-burst}
     * and {@code capacity-change} and {@code switch-under-load} (each needs a driver feature this
     * comparison does not implement — see {@link #checkSupported}), and
     * {@code head-of-line-blocking}: readiness, which that workload is built on, exists nowhere in
     * the old tree ({@code git grep} for {@code readiness} at {@code 05f49dcb} over the modules'
     * {@code src/main} is empty) and is new at HEAD ({@link EngineReadiness}), so the two arms would
     * not be running the same workload.
     */
    static final String DEFAULT_PROFILES =
            "low-load,saturated,unqueued,queued,churn-drain,mixed-kind-retry";

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private static PrintStream out = System.out;

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        int repetitions = intOpt(opts, "repetitions", 5);
        String selection = opts.getOrDefault("profiles", DEFAULT_PROFILES);
        Set<String> arms = Set.of(opts.getOrDefault("arms", "old,new").split(","));
        boolean includeOld = arms.contains("old");
        boolean includeNew = arms.contains("new");
        if (!includeOld && !includeNew) {
            throw new IllegalArgumentException("--arms must name at least one of old,new");
        }
        if (opts.containsKey("out")) {
            out = new PrintStream(Files.newOutputStream(Path.of(opts.get("out"))),
                    false, StandardCharsets.UTF_8);
        }

        List<SchedulerSwitchBenchmark.Profile> profiles =
                SchedulerSwitchBenchmark.Profiles.select(selection);
        for (SchedulerSwitchBenchmark.Profile profile : profiles) {
            checkSupported(profile);
        }
        emitHeader(selection, repetitions, includeOld, includeNew, profiles);

        for (int repetition = 1; repetition <= repetitions; repetition++) {
            for (SchedulerSwitchBenchmark.Profile profile : profiles) {
                // Alternating order across repetitions so a monotone host drift cannot land on one
                // arm: an order fixed for every repetition would confound the arm with the clock.
                boolean oldFirst = repetition % 2 == 1;
                if (includeOld && oldFirst) {
                    runOne(profile, OLD_ARM, repetition, false);
                }
                if (includeNew) {
                    runOne(profile, NEW_ARM, repetition, true);
                }
                if (includeOld && !oldFirst) {
                    runOne(profile, OLD_ARM, repetition, false);
                }
            }
        }
        out.flush();
    }

    private static void runOne(SchedulerSwitchBenchmark.Profile profile, String arm, int repetition,
                              boolean newEngine) throws Exception {
        MeasuredRun run = new MeasuredRun(profile, arm, repetition, newEngine);
        run.execute();
        emitSample(run);
    }

    /**
     * Refuses a profile whose workload needs a driver feature this comparison does not implement.
     *
     * <p>A silent skip here would be a sample whose {@code note} describes a workload that did not
     * run. The refusals are loud and named instead: readiness has no old-tree counterpart at all,
     * and burst and capacity-change would have to be implemented twice, once per arm, to stay paired.
     */
    private static void checkSupported(SchedulerSwitchBenchmark.Profile profile) {
        if (profile.notReadyCount > 0) {
            throw new IllegalArgumentException(profile.name
                    + ": readiness is new-at-HEAD only; the old loop has no readiness gate and "
                    + "would dispatch work the engine refuses, so this profile is not comparable");
        }
        if (profile.burstCount > 0) {
            throw new IllegalArgumentException(profile.name + ": the burst feature is not implemented");
        }
        if (profile.capacityChangeAtPercent > 0 || profile.capacityRestoreAtPercent > 0) {
            throw new IllegalArgumentException(profile.name
                    + ": the capacity-change feature is not implemented");
        }
        if (profile.prefill > 0) {
            throw new IllegalArgumentException(profile.name + ": the prefill feature is not implemented");
        }
    }

    // ==================================================================
    // Header
    // ==================================================================

    private static void emitHeader(String selection, int repetitions, boolean includeOld,
                                   boolean includeNew,
                                   List<SchedulerSwitchBenchmark.Profile> profiles) {
        StringBuilder json = new StringBuilder(1024);
        json.append("{\"kind\":\"header\"")
                .append(",\"campaign\":\"scheduler-switching-2026-09\"")
                .append(",\"task\":\"12e\"")
                .append(",\"artifact\":\"").append(prop("nanofaas.artifact", "jvm")).append('"')
                .append(",\"sha\":\"").append(prop("nanofaas.sha", "unknown")).append('"')
                .append(",\"harnessSha256\":\"").append(prop("nanofaas.harnessSha", "unknown")).append('"')
                .append(",\"benchmarkSha256\":\"")
                .append(prop("nanofaas.benchmarkSha", "unknown")).append('"')
                .append(",\"profiles\":\"").append(escape(selection)).append('"')
                .append(",\"repetitions\":").append(repetitions)
                .append(",\"arms\":[")
                .append(includeOld ? "\"" + OLD_ARM + "\"" : "")
                .append(includeOld && includeNew ? "," : "")
                .append(includeNew ? "\"" + NEW_ARM + "\"" : "")
                .append(']')
                .append(",\"spanMillis\":").append(SPAN_MS)
                .append(",\"warmupMillis\":").append(WARMUP_MS)
                .append(",\"admissionAlignment\":")
                .append("\"old per-function queueSize = floor(profile.maxPending / functions); "
                        + "the totals are equal, the rejection instants are not\"")
                .append(",\"expiryInOldArm\":")
                .append("\"none: the old async loop has no deadline and the driver adds no reaper\"")
                .append(",\"jvm\":\"").append(escape(System.getProperty("java.vm.name", "?") + " "
                        + System.getProperty("java.vm.version", "?"))).append('"')
                .append(",\"javaVersion\":\"").append(System.getProperty("java.version", "?")).append('"')
                .append(",\"host\":\"").append(escape(System.getProperty("os.name", "?") + " "
                        + System.getProperty("os.version", "?") + " "
                        + System.getProperty("os.arch", "?"))).append('"')
                .append(",\"availableProcessors\":")
                .append(Runtime.getRuntime().availableProcessors())
                .append(",\"profilesInRun\":[");
        for (int i = 0; i < profiles.size(); i++) {
            SchedulerSwitchBenchmark.Profile profile = profiles.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"name\":\"").append(profile.name).append('"')
                    .append(",\"note\":\"").append(escape(profile.note)).append('"')
                    .append(",\"functions\":").append(profile.functions.length)
                    .append(",\"capacity\":").append(profile.capacity)
                    .append(",\"maxPending\":").append(profile.maxPending)
                    .append(",\"oldQueueSizePerFunction\":").append(oldQueueSize(profile))
                    .append(",\"offeredRatePerSecond\":").append(profile.totalOfferedPerSecond())
                    .append(",\"contractMillis\":").append(profile.contractMs)
                    .append(",\"retryFraction\":").append(profile.retryFraction)
                    .append(",\"syncFraction\":").append(profile.syncFraction)
                    .append(",\"churnAtPercent\":").append(profile.churnAtPercent)
                    .append(",\"stopAtPercent\":").append(profile.stopAtPercent)
                    .append('}');
        }
        json.append("]}");
        out.println(json);
    }

    /**
     * The old loop's per-function admission bound, aligned to the engine's single global one.
     *
     * <p>The engine bounds the <em>total</em> offered-but-not-submitted population
     * ({@code PendingWorkStore(maxPending)}); the old async loop bounds each function's own queue
     * plus its dispatch reservations ({@code FunctionQueueState.queueSize}). Dividing the one by the
     * function count makes the totals equal, which is the only alignment that exists: the engine
     * refuses globally and the old loop refuses per function, so the two cannot be made to refuse
     * the same offer at the same instant (M2). The residual mismatch is visible in every sample as
     * {@code offered} against {@code admitted} and {@code admissionRejected}.
     */
    static int oldQueueSize(SchedulerSwitchBenchmark.Profile profile) {
        return Math.max(1, profile.maxPending / Math.max(1, profile.functions.length));
    }

    // ==================================================================
    // One measured run
    // ==================================================================

    /**
     * One controlled run of one arm. Owns its registry, its arm and its accounting, so no repetition
     * inherits state from the one before it. The JVM is the only thing shared across runs, which is
     * what the warm-up exists to settle — and, unlike a cross-process comparison, it is shared
     * <em>identically</em> by both arms.
     */
    static final class MeasuredRun {
        final SchedulerSwitchBenchmark.Profile profile;
        final String armLabel;
        final int repetition;
        final long seed;
        final boolean newEngine;

        final FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        final Arm arm;

        // Driver-thread counters.
        long offered, admitted, admissionRejected, churnRefused, completed, useful, retries, bursts;
        long backlogSamples, backlogSum, maxBacklog;
        // Arm-thread counters.
        final AtomicLong expired = new AtomicLong();
        final AtomicLong removed = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();
        final AtomicIntegerArray perFunctionExpired;
        final AtomicIntegerArray perFunctionRemoved;
        final AtomicLong driverFailures = new AtomicLong();

        // Driver-thread per-function counters, read only after the driver has stopped.
        final int[] perFunctionAdmitted;
        final int[] perFunctionCompleted;
        final int[] perFunctionUseful;
        final int[] perFunctionSync;
        final int[] perFunctionAsync;

        volatile long windowStartNanos;
        volatile long windowEndNanos;
        /** When the measured window closes. Set by the driver before any completion is recorded. */
        private volatile long windowCloseAtNanos;
        volatile int pendingAtEnd = -1;
        volatile int claimedAtEnd = -1;
        volatile int submittingAtEnd = -1;
        volatile int inFlightAtEnd = -1;
        volatile int closedPending = -1;
        volatile int closedClaimed = -1;
        volatile int closedSubmitting = -1;
        volatile long windowStartCpuNanos = -1;
        volatile long windowEndCpuNanos = -1;
        volatile long windowStartThreadCpuNanos = -1;
        volatile long windowEndThreadCpuNanos = -1;
        final int[] depthSeries = new int[DEPTH_SAMPLES];
        volatile int depthCount;
        volatile long depthIntervalNanos = DEPTH_SAMPLE_MS * 1_000_000L;
        volatile long windowStartAllocBytes = -1;
        volatile long windowEndAllocBytes = -1;
        volatile long preRunHeapBytes = -1;
        volatile long postGcHeapBytes = -1;
        volatile boolean broken;

        MeasuredRun(SchedulerSwitchBenchmark.Profile profile, String armLabel, int repetition,
                    boolean newEngine) {
            this.profile = profile;
            this.armLabel = armLabel;
            this.repetition = repetition;
            this.newEngine = newEngine;
            // One seed per (workload, repetition), shared by both arms: the arrival script below is
            // built from it once, so the two arms are offered the *identical* sequence, not merely
            // the same rate. That is what makes the comparison paired.
            this.seed = 208_000L + profile.name.hashCode() * 31L + repetition;
            for (String function : profile.functions) {
                capacity.register(function, profile.capacity);
            }
            for (String function : profile.churnedFunctions()) {
                capacity.remove(function);
            }
            this.perFunctionExpired = new AtomicIntegerArray(profile.functions.length);
            this.perFunctionRemoved = new AtomicIntegerArray(profile.functions.length);
            this.perFunctionAdmitted = new int[profile.functions.length];
            this.perFunctionCompleted = new int[profile.functions.length];
            this.perFunctionUseful = new int[profile.functions.length];
            this.perFunctionSync = new int[profile.functions.length];
            this.perFunctionAsync = new int[profile.functions.length];
            this.arm = newEngine ? new NewArm(this) : new OldArm(this);
        }

        long contractNanos() {
            return profile.contractMs * 1_000_000L;
        }

        /** The declared service duration of one function: whole milliseconds plus a fixed tail. */
        long serviceNanos(String function) {
            return profile.serviceMillis(function) * 1_000_000L
                    + profile.serviceFractionNanos(function);
        }

        /** The readiness the engine is given: the same predicate the committed harness uses. */
        EngineReadiness readiness() {
            Set<String> churned = new HashSet<>(profile.churnedFunctions());
            return generation -> !churned.contains(generation.functionName());
        }

        /**
         * Runs the workload: fill, warm up, then measure. Identical for both arms — the arm is a
         * field, not a branch in the protocol.
         */
        void execute() throws Exception {
            resetSamples();
            Runtime.getRuntime().gc();
            park(20_000_000L);
            preRunHeapBytes = usedHeap();

            arm.start();
            Driver driver = new Driver();
            driver.budgetNanos = (WARMUP_MS + SPAN_MS + 8_000L) * 1_000_000L;
            Thread driverThread = new Thread(driver, "benchmark-driver");
            driverThread.start();

            long giveUp = System.nanoTime() + driver.budgetNanos;
            while (windowStartNanos == 0L && System.nanoTime() < giveUp && driverThread.isAlive()) {
                LockSupport.parkNanos(100_000L);
            }
            driverThread.join(driver.budgetNanos / 1_000_000L + 30_000L);
            if (driverThread.isAlive()) {
                broken = true;
                report("driver did not stop: " + profile.name + " " + armLabel);
                driver.running = false;
                driverThread.join(5_000L);
            }
            inFlightAtEnd = arm.inFlight.size();
            // Stop the arm's own thread before reading any population: the identity below is exact
            // only against a frozen loop, and a dispatch in progress moves a ticket between counts.
            arm.stop();
            freeze();

            postGcHeapBytes = postGcHeap();
            if (driver.failure != null) {
                broken = true;
                report("driver failed: " + driver.failure);
            }
        }

        /**
         * The accounting identity: every admitted ticket is in exactly one of these buckets. For the
         * engine the three queue populations are the store's; for the old loop there is one queue per
         * function and no claimed/submitting split, so its claimed and submitting counts are zero by
         * construction rather than unchecked. `workConserved` is the equality, and a single
         * duplicated or lost completion breaks it.
         */
        long closure() {
            return completed + expired.get() + removed.get() + rejected.get()
                    + Math.max(closedPending, 0) + Math.max(closedClaimed, 0)
                    + Math.max(closedSubmitting, 0) + Math.max(inFlightAtEnd, 0);
        }

        void freeze() {
            int[] populations = arm.frozenPopulations();
            closedPending = populations[0];
            closedClaimed = populations[1];
            closedSubmitting = populations[2];
            inFlightAtEnd = arm.inFlight.size();
        }

        void complete(Attempt attempt, long completionNanos) {
            long latency = completionNanos - attempt.admittedNanos;
            completed++;
            perFunctionCompleted[attempt.functionIndex]++;
            if (attempt.sync) {
                perFunctionSync[attempt.functionIndex]++;
            } else {
                perFunctionAsync[attempt.functionIndex]++;
            }
            if (latency <= contractNanos()) {
                useful++;
                perFunctionUseful[attempt.functionIndex]++;
            }
            if (attempt.admittedNanos >= windowStartNanos && completionNanos <= windowCloseAtNanos) {
                recordSample(attempt.functionIndex, attempt.admittedNanos, latency);
            }
        }

        /** Queue depth from the driver's own counters: never touches the arm's own gate. */
        void sampleBacklog() {
            long pending = admitted - delivered();
            if (pending < 0) {
                pending = 0;
            }
            backlogSamples++;
            backlogSum += pending;
            if (pending > maxBacklog) {
                maxBacklog = pending;
            }
        }

        /** Admitted work that has reached a terminal state: completed, expired or removed. */
        long delivered() {
            return completed + expired.get() + removed.get() + rejected.get();
        }

        long usedHeap() {
            Runtime runtime = Runtime.getRuntime();
            return runtime.totalMemory() - runtime.freeMemory();
        }

        long postGcHeap() {
            for (int i = 0; i < 3; i++) {
                Runtime.getRuntime().gc();
                park(50_000_000L);
            }
            return usedHeap();
        }

        // ---------------- the driver ----------------

        /**
         * The load generator and the service model's clock, shared by both arms. Single-threaded by
         * construction: one arrival source, one completion sweep, no per-completion thread. It never
         * touches the arm's gate on the measurement path.
         */
        final class Driver implements Runnable {
            volatile long budgetNanos = 120_000_000_000L;
            volatile boolean running = true;
            volatile long startedNanos;
            volatile Throwable failure;

            /** The arrival script: identical for both arms of one (workload, repetition). */
            private final Script script;
            /** Reactive stream for retries only — see {@link #retry()}. */
            private final Random retryRolls = new Random(seed * 31L + 7L);
            private int scriptIndex;
            private boolean churned;
            private long completionsSinceBacklogSample;
            private long nextDepthSampleNanos;
            private long sequenceCounter;

            Driver() {
                this.script = Script.build(profile, seed);
            }

            @Override
            public void run() {
                try {
                    startedNanos = System.nanoTime();
                    long warmupEnd = startedNanos + WARMUP_MS * 1_000_000L;
                    while (running) {
                        long now = System.nanoTime();
                        if (now - startedNanos >= budgetNanos) {
                            break;
                        }
                        events(now);
                        serviceDue(now);
                        if (windowStartNanos != 0L && windowEndNanos == 0L
                                && now >= nextDepthSampleNanos) {
                            sampleDepth();
                            nextDepthSampleNanos = now + DEPTH_SAMPLE_MS * 1_000_000L;
                        }
                        if (windowStartNanos == 0L && now >= warmupEnd) {
                            openWindow(now);
                        }
                        if (windowStartNanos != 0L
                                && now >= windowStartNanos + SPAN_MS * 1_000_000L) {
                            closeWindow(now);
                            return;
                        }
                        long wait = Math.min(nextScriptAt(), earliestCompletionDue()) - now;
                        if (wait > 20_000L) {
                            LockSupport.parkNanos(Math.min(wait, 2_000_000L));
                        } else {
                            Thread.onSpinWait();
                        }
                    }
                } catch (Throwable failure) {
                    this.failure = failure;
                    driverFailures.incrementAndGet();
                }
            }

            private void openWindow(long now) {
                windowStartCpuNanos = OS.getProcessCpuTime();
                windowStartThreadCpuNanos = threadCpuSum();
                windowStartAllocBytes = allocatedBytes();
                windowCloseAtNanos = now + SPAN_MS * 1_000_000L;
                windowEndNanos = 0L;
                depthCount = 0;
                nextDepthSampleNanos = now;
                sampleDepth();
                windowStartNanos = now;
            }

            /**
             * One point of the queue-depth trajectory, from the driver's own counters so it never
             * touches the arm's gate. This is what says whether an arm has settled, and therefore
             * whether the two arms are comparable at steady state.
             */
            void sampleDepth() {
                long pending = admitted - delivered();
                if (depthCount < DEPTH_SAMPLES) {
                    depthSeries[depthCount++] = (int) Math.max(pending, 0);
                }
            }

            private void closeWindow(long now) {
                windowEndCpuNanos = OS.getProcessCpuTime();
                windowEndThreadCpuNanos = threadCpuSum();
                windowEndAllocBytes = allocatedBytes();
                int[] populations = arm.livePopulations();
                pendingAtEnd = populations[0];
                claimedAtEnd = populations[1];
                submittingAtEnd = populations[2];
                windowEndNanos = now;
            }

            /** Time-triggered workload events: churn, then the scripted arrivals. */
            private void events(long now) {
                long spanStart = windowStartNanos;
                if (profile.churnAtPercent > 0 && !churned && spanStart != 0L
                        && now - spanStart
                        >= SPAN_MS * (long) profile.churnAtPercent / 100L * 1_000_000L) {
                    churned = true;
                    arm.churn(profile.churnedFunctions(), profile.replacementFunctions());
                    for (String function : profile.churnedFunctions()) {
                        report("churned out " + function);
                    }
                }
                long elapsed = now - startedNanos;
                while (scriptIndex < script.size && script.atNanos[scriptIndex] <= elapsed) {
                    offer(script.functionIndex[scriptIndex], script.sync[scriptIndex], 1);
                    scriptIndex++;
                }
            }

            private long nextScriptAt() {
                return scriptIndex < script.size
                        ? startedNanos + script.atNanos[scriptIndex] : Long.MAX_VALUE;
            }

            /** Releases the lease of every attempt whose declared service duration has elapsed. */
            private void serviceDue(long now) {
                int inFlight = arm.inFlight.size();
                for (int i = 0; i < inFlight; i++) {
                    Attempt attempt = arm.inFlight.poll();
                    if (attempt == null) {
                        break;
                    }
                    if (attempt.dueNanos > now) {
                        arm.inFlight.add(attempt);
                        continue;
                    }
                    if (attempt.lease != null) {
                        attempt.lease.release();
                    }
                    complete(attempt, System.nanoTime());
                    // A retry is generated by a completion, so it cannot be scripted: it is the one
                    // reactive draw in the load, and it is declared in the deliverable as the source
                    // of any residual offered-count difference between the arms.
                    if (profile.retryFraction > 0.0
                            && retryRolls.nextDouble() < profile.retryFraction) {
                        offer(attempt.functionIndex, retryRolls.nextBoolean(), 2);
                    }
                    if (++completionsSinceBacklogSample >= 64) {
                        completionsSinceBacklogSample = 0;
                        sampleBacklog();
                    }
                }
            }

            private long earliestCompletionDue() {
                long earliest = Long.MAX_VALUE;
                for (Attempt attempt : arm.inFlight) {
                    if (attempt.dueNanos < earliest) {
                        earliest = attempt.dueNanos;
                    }
                }
                return earliest;
            }

            /** One offered arrival, handed to the arm's own admission bound. */
            private void offer(int index, boolean sync, int attempt) {
                String function = profile.functions[index];
                FunctionGeneration generation = capacity.activeGeneration(function);
                if (generation == null) {
                    churnRefused++;
                    return;
                }
                long sequence = sequenceCounter++;
                String executionId = profile.name + '-' + index + '-' + sequence;
                long admittedNanos = System.nanoTime();
                Offered ticket = new Offered(executionId, attempt, function, index, sync,
                        admittedNanos, sequence);
                InvocationRequest request = profile.payloadBytes(index) == 0 ? null
                        : new InvocationRequest(new byte[profile.payloadBytes(index)], Map.of());
                InvocationTask task = new InvocationTask(executionId, function,
                        newSpec(function, profile), request, null, null, Instant.now(), attempt,
                        sync ? InvocationKind.SYNC : InvocationKind.ASYNC);
                offered++;
                if (attempt > 1) {
                    retries++;
                }
                if (arm.admit(ticket, task, generation)) {
                    admitted++;
                    perFunctionAdmitted[index]++;
                } else {
                    admissionRejected++;
                }
            }
        }
    }

    /** A function's declared shape, as the old loop's registry needs it. */
    static FunctionSpec newSpec(String function, SchedulerSwitchBenchmark.Profile profile) {
        return new FunctionSpec(function, "image", null, Map.of(), null, 1000, profile.capacity,
                oldQueueSize(profile), 3, null, ExecutionMode.LOCAL, null, null, null, null);
    }

    // ==================================================================
    // The arrival script
    // ==================================================================

    /**
     * The offered load of one (workload, repetition), fixed before either arm runs.
     *
     * <p>The committed harness drives each arm from its own {@link Random}, so the arms share an
     * offered <em>rate</em> but not an offered <em>sequence</em>; it says so and measures the
     * resulting spread. Here the sequence is materialised once and replayed to both arms, which is
     * what makes a per-repetition difference a paired quantity rather than a difference of two
     * independent samples. The shape is the committed one — exponential inter-arrivals at the
     * profile's own rate, a SYNC/ASYNC draw at its own {@code syncFraction}, and the function drawn
     * from the hot pool or the sporadic pool exactly as {@code Profile} defines them.
     */
    static final class Script {
        final long[] atNanos;
        final int[] functionIndex;
        final boolean[] sync;
        final int size;

        private Script(long[] atNanos, int[] functionIndex, boolean[] sync, int size) {
            this.atNanos = atNanos;
            this.functionIndex = functionIndex;
            this.sync = sync;
            this.size = size;
        }

        static Script build(SchedulerSwitchBenchmark.Profile profile, long seed) {
            Random draws = new Random(seed);
            double rate = profile.totalOfferedPerSecond();
            // The script covers the warm-up and the span; one extra second absorbs the loop's own
            // scheduling slack at the far end without changing any measured window.
            long horizon = (WARMUP_MS + SPAN_MS + 1_000L) * 1_000_000L;
            long stopAt = profile.stopAtPercent > 0
                    ? (WARMUP_MS + SPAN_MS * (long) profile.stopAtPercent / 100L) * 1_000_000L
                    : Long.MAX_VALUE;
            long churnAt = profile.churnAtPercent > 0
                    ? (WARMUP_MS + SPAN_MS * (long) profile.churnAtPercent / 100L) * 1_000_000L
                    : Long.MAX_VALUE;
            int capacity = 1 << 16;
            long[] at = new long[capacity];
            int[] index = new int[capacity];
            boolean[] sync = new boolean[capacity];
            int count = 0;
            long t = 0L;
            while (t < horizon && t < stopAt) {
                if (count == capacity) {
                    break;
                }
                boolean afterChurn = t >= churnAt;
                at[count] = t;
                index[count] = chooseFunction(profile, draws, afterChurn);
                sync[count] = draws.nextDouble() < profile.syncFraction;
                count++;
                t += (long) (-Math.log(1.0 - draws.nextDouble()) * 1_000_000_000.0 / rate);
            }
            return new Script(at, index, sync, count);
        }

        /**
         * The committed {@code Driver.chooseFunction}: the hot pool with the hot share, elsewhere the
         * sporadic pool — which grows by the replacement half after the churn, since the retired
         * names keep receiving arrivals the arm now refuses.
         */
        private static int chooseFunction(SchedulerSwitchBenchmark.Profile profile, Random draws,
                                          boolean afterChurn) {
            if (profile.sporadicFunctions == 0) {
                return draws.nextInt(profile.hotFunctions);
            }
            double hotShare = profile.hotRatePerSecond * profile.hotFunctions
                    / profile.totalOfferedPerSecond();
            if (draws.nextDouble() < hotShare) {
                return draws.nextInt(profile.hotFunctions);
            }
            int pool = profile.sporadicFunctions
                    + (afterChurn ? profile.sporadicFunctions / 2 : 0);
            return profile.hotFunctions + draws.nextInt(pool);
        }

    }

    // ==================================================================
    // The arms
    // ==================================================================

    /** One dispatched attempt: its lease, its admission time, and when its service ends. */
    static final class Attempt {
        final int functionIndex;
        final long admittedNanos;
        final long dueNanos;
        final DispatchOwnership lease;
        final boolean sync;

        Attempt(int functionIndex, long admittedNanos, long dueNanos, DispatchOwnership lease,
                boolean sync) {
            this.functionIndex = functionIndex;
            this.admittedNanos = admittedNanos;
            this.dueNanos = dueNanos;
            this.lease = lease;
            this.sync = sync;
        }
    }

    /** An offer in flight from the driver into an arm and back out as a completion. */
    static final class Offered {
        final String executionId;
        final int attempt;
        final String function;
        final int functionIndex;
        final boolean sync;
        final long admittedNanos;
        final long sequence;

        Offered(String executionId, int attempt, String function, int functionIndex, boolean sync,
                long admittedNanos, long sequence) {
            this.executionId = executionId;
            this.attempt = attempt;
            this.function = function;
            this.functionIndex = functionIndex;
            this.sync = sync;
            this.admittedNanos = admittedNanos;
            this.sequence = sequence;
        }

        String key() {
            return executionId + "/" + attempt;
        }
    }

    /**
     * The seat of one arm: its own admission bound, its own loop, its own dispatch path. The driver
     * knows nothing else about it, which is what keeps one driver serving both arms.
     */
    abstract static class Arm {
        final MeasuredRun run;
        final ConcurrentLinkedQueue<Attempt> inFlight = new ConcurrentLinkedQueue<>();
        /** Tickets admitted but not yet dispatched, keyed by executionId/attempt. */
        final ConcurrentHashMap<String, Offered> awaiting = new ConcurrentHashMap<>();

        Arm(MeasuredRun run) {
            this.run = run;
        }

        abstract void start();

        /** Offers one ticket. False means this arm's own admission bound refused it. */
        abstract boolean admit(Offered offered, InvocationTask task, FunctionGeneration generation);

        /** Retires these functions and re-registers their replacements, as the workload asks. */
        abstract void churn(List<String> retired, List<String> replacements);

        /** The populations the arm reports while it is running: pending, claimed, submitting. */
        abstract int[] livePopulations();

        /** The same populations, read after {@link #stop()}: nothing can move between them. */
        abstract int[] frozenPopulations();

        /** Stops the arm's own thread. Returns only once no dispatch can be in progress. */
        abstract void stop();

        /** Records one dispatch: publish a due time and return, holding the lease until then. */
        void onDispatched(Offered offered, DispatchOwnership lease) {
            long due = System.nanoTime() + run.serviceNanos(offered.function);
            inFlight.add(new Attempt(offered.functionIndex, offered.admittedNanos, due, lease,
                    offered.sync));
        }
    }

    /**
     * The old async loop, driven directly. Subject of its own arm, with no second dispatch path
     * beside it: {@link InvocationDispatch} is the seat the real transport occupies, and the only
     * thing added on the far side is a due time and a held lease — the service model, not a
     * scheduler.
     */
    static final class OldArm extends Arm implements InvocationDispatch, QueueLifecycle {
        private final QueueManager queueManager;
        private final Scheduler scheduler;
        private final FunctionSpec[] specs;

        OldArm(MeasuredRun run) {
            super(run);
            this.specs = new FunctionSpec[run.profile.functions.length];
            this.queueManager = newQueueManager(new SimpleMeterRegistry());
            for (int i = 0; i < run.profile.functions.length; i++) {
                specs[i] = newSpec(run.profile.functions[i], run.profile);
                queueManager.getOrCreate(specs[i]);
            }
            // Churned-out functions start retired, exactly as the engine's readiness predicate
            // refuses them until their replacement generation is registered.
            for (String function : run.profile.churnedFunctions()) {
                queueManager.remove(function);
            }
            this.scheduler = new Scheduler(queueManager, this, this);
        }

        @Override
        void start() {
            scheduler.init();
            scheduler.start();
        }

        @Override
        boolean admit(Offered offered, InvocationTask task, FunctionGeneration generation) {
            awaiting.put(offered.key(), offered);
            boolean accepted = queueManager.enqueue(task);
            if (!accepted) {
                awaiting.remove(offered.key());
            }
            return accepted;
        }

        @Override
        void churn(List<String> retired, List<String> replacements) {
            for (String function : retired) {
                int index = indexOf(function);
                List<InvocationTask> drained = queueManager.remove(function);
                run.removed.addAndGet(drained.size());
                if (index >= 0) {
                    run.perFunctionRemoved.addAndGet(index, drained.size());
                }
            }
            for (String function : replacements) {
                queueManager.getOrCreate(newSpec(function, run.profile));
            }
        }

        private int indexOf(String function) {
            for (int i = 0; i < run.profile.functions.length; i++) {
                if (run.profile.functions[i].equals(function)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        int[] livePopulations() {
            // No claimed/submitting split exists in this loop: a dispatched ticket has left the
            // queue and its reservation is released the moment the dispatch commits. The zeros are
            // structural, not unmeasured.
            int queued = 0;
            for (String function : run.profile.functions) {
                FunctionQueueState state = queueManager.get(function);
                if (state != null) {
                    queued += state.queued();
                }
            }
            return new int[] {queued, 0, 0};
        }

        @Override
        int[] frozenPopulations() {
            return livePopulations();
        }

        @Override
        void stop() {
            scheduler.stop();
        }

        // ---------------- InvocationDispatch: the transport seat ----------------

        @Override
        public void dispatch(InvocationTask task) {
            String key = task.executionId() + "/" + task.attempt();
            Offered offered = awaiting.remove(key);
            if (offered == null) {
                return;
            }
            // The lease rides on the task; holding it past this call is the service model. The
            // wakeup on release is NOT the driver's: QueueManager.tryAcquireLease owns that
            // callback in production code, and it fires on the driver's release below (M6).
            onDispatched(offered, task.dispatchLease());
        }

        // ---------------- QueueLifecycle ----------------

        @Override
        public void expired(InvocationTask task) {
            // Unreachable: this loop has no deadline. Emitted as a zero, which is the mismatch M3
            // names, rather than silently omitted.
            run.expired.incrementAndGet();
        }

        @Override
        public void removed(InvocationTask task) {
            awaiting.remove(task.executionId() + "/" + task.attempt());
            run.removed.incrementAndGet();
        }

        @Override
        public void rejected(InvocationTask task, Throwable failure) {
            awaiting.remove(task.executionId() + "/" + task.attempt());
            run.rejected.incrementAndGet();
        }

        @Override
        public Set<String> inFlightExecutionIds(String functionName) {
            return Set.of();
        }

        @Override
        public void onExecutionGone(BiConsumer<String, String> listener) {
            // Nothing in this loop consults this hook; the sync queue's removal fence does.
        }
    }

    /**
     * The new engine with the strategy that ports the old loop. Mirrors the committed harness's
     * controlled dispatcher: {@link #submit} publishes and returns, the lease it was handed is held
     * until the driver judges the service duration elapsed.
     */
    static final class NewArm extends Arm implements EngineDispatch {
        private final PendingWorkStore store;
        private final SchedulerEngine engine;
        private volatile DispatchOwnership lastLease;

        NewArm(MeasuredRun run) {
            super(run);
            this.store = new PendingWorkStore(run.profile.maxPending);
            List<SchedulingStrategy> strategies =
                    List.of(new PerFunctionSchedulingStrategy());
            this.engine = new SchedulerEngine(store, new StrategyRegistry(strategies),
                    NEW_STRATEGY, this, run.readiness(), Clock.systemUTC(), System::nanoTime);
        }

        @Override
        void start() {
            engine.start();
        }

        @Override
        boolean admit(Offered offered, InvocationTask task, FunctionGeneration generation) {
            TicketId id = new TicketId(offered.executionId, offered.attempt);
            Instant now = Instant.now();
            SchedulingTicket ticket = new SchedulingTicket(id, generation, offered.sequence, now,
                    now, now.plusMillis(run.profile.contractMs));
            awaiting.put(offered.key(), offered);
            boolean accepted = engine.enqueue(new PendingEntry(ticket, task));
            if (!accepted) {
                awaiting.remove(offered.key());
            }
            return accepted;
        }

        @Override
        void churn(List<String> retired, List<String> replacements) {
            for (String function : retired) {
                engine.removeAllFor(function);
                engine.markDraining(function);
                run.capacity.remove(function);
            }
            for (String function : replacements) {
                engine.clearDraining(function);
                run.capacity.register(function, run.profile.capacity);
            }
        }

        @Override
        int[] livePopulations() {
            EngineQueueSnapshot snapshot = engine.snapshotQueues();
            return new int[] {snapshot.pending(), snapshot.claimed(), snapshot.submitting()};
        }

        @Override
        int[] frozenPopulations() {
            EngineQueueSnapshot snapshot = engine.snapshotQueues();
            return new int[] {snapshot.pending(), snapshot.claimed(), snapshot.submitting()};
        }

        @Override
        void stop() {
            engine.close();
        }

        // ---------------- EngineDispatch: the transport seat ----------------

        @Override
        public DispatchOwnership tryAcquire(SchedulingTicket ticket) {
            DispatchOwnership lease = run.capacity.tryAcquireLease(ticket.generation(),
                    releasedNanos -> engine.signal());
            lastLease = lease;
            return lease;
        }

        @Override
        public boolean isCurrent(SchedulingTicket ticket) {
            return true;
        }

        @Override
        public void submit(InvocationTask task) {
            Offered offered = awaiting.remove(task.executionId() + "/" + task.attempt());
            if (offered == null) {
                return;
            }
            onDispatched(offered, lastLease);
        }

        @Override
        public void expired(InvocationTask task) {
            forget(task);
            run.expired.incrementAndGet();
        }

        @Override
        public void removed(InvocationTask task) {
            Integer index = forget(task);
            run.removed.incrementAndGet();
            if (index != null) {
                run.perFunctionRemoved.incrementAndGet(index);
            }
        }

        @Override
        public void rejected(InvocationTask task, Throwable failure) {
            forget(task);
            run.rejected.incrementAndGet();
        }

        private Integer forget(InvocationTask task) {
            Offered offered = awaiting.remove(task.executionId() + "/" + task.attempt());
            return offered == null ? null : offered.functionIndex;
        }
    }

    /**
     * {@link QueueManager}'s constructor is package-private in its own module, and this class must
     * share {@code SchedulerSwitchBenchmark}'s package to reuse the committed corpus. One reflected
     * constructor is the whole seam; nothing else in either loop is reached this way, and nothing in
     * the engine's API is reached at all.
     */
    private static QueueManager newQueueManager(MeterRegistry registry) {
        try {
            Constructor<QueueManager> constructor = QueueManager.class.getDeclaredConstructor(
                    MeterRegistry.class, DispatchCapacity.class);
            constructor.setAccessible(true);
            return constructor.newInstance(registry, new FunctionCapacityRegistry());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("QueueManager(MeterRegistry, DispatchCapacity) is no "
                    + "longer reachable by reflection; the old-loop arm cannot be driven", failure);
        }
    }

    // ==================================================================
    // Output — the committed schema
    // ==================================================================

    private static void emitSample(MeasuredRun run) {
        SchedulerSwitchBenchmark.Profile profile = run.profile;
        int n = sampleCount();
        long[] sorted = Arrays.copyOf(LATENCY, n);
        Arrays.sort(sorted);
        long windowNanos = run.windowEndNanos - run.windowStartNanos;
        double windowSeconds = windowNanos / 1e9;
        long cpuNanos = run.windowEndCpuNanos - run.windowStartCpuNanos;
        long threadCpuNanos = run.windowEndThreadCpuNanos - run.windowStartThreadCpuNanos;
        long allocated = run.windowEndAllocBytes - run.windowStartAllocBytes;
        long capacityTotal = (long) profile.capacity * profile.functions.length;

        StringBuilder json = new StringBuilder(8192);
        json.append("{\"kind\":\"sample\"")
                .append(",\"workload\":\"").append(profile.name).append('"')
                .append(",\"note\":\"").append(escape(profile.note)).append('"')
                .append(",\"arm\":\"").append(run.armLabel).append('"')
                .append(",\"initialStrategy\":\"")
                .append(run.newEngine ? NEW_STRATEGY : OLD_ARM_SUBJECT).append('"')
                .append(",\"finalStrategy\":\"")
                .append(run.newEngine ? NEW_STRATEGY : OLD_ARM_SUBJECT).append('"')
                .append(",\"repetition\":").append(run.repetition)
                .append(",\"seed\":").append(run.seed)
                .append(",\"windowMillis\":").append(windowNanos / 1_000_000L)
                .append(",\"offeredRatePerSecond\":").append(profile.totalOfferedPerSecond())
                .append(",\"offered\":").append(run.offered)
                .append(",\"admitted\":").append(run.admitted)
                .append(",\"admissionRejected\":").append(run.admissionRejected)
                .append(",\"churnRefused\":").append(run.churnRefused)
                .append(",\"admittedRatePerSecond\":").append(fmt(run.admitted / windowSeconds))
                .append(",\"completed\":").append(run.completed)
                .append(",\"useful\":").append(run.useful)
                .append(",\"usefulThroughputPerSecond\":").append(fmt(run.useful / windowSeconds))
                .append(",\"completionRatePerSecond\":").append(fmt(run.completed / windowSeconds))
                .append(",\"expired\":").append(run.expired.get())
                .append(",\"removed\":").append(run.removed.get())
                .append(",\"rejected\":").append(run.rejected.get())
                .append(",\"retries\":").append(run.retries)
                .append(",\"bursts\":").append(run.bursts)
                .append(",\"contractMillis\":").append(profile.contractMs)
                .append(",\"samplesRecorded\":").append(n)
                .append(",\"sampleCapReached\":").append(sampleOverflow())
                .append(",\"processCpuPerUsefulCompletionNanos\":")
                .append(run.useful == 0 ? -1L : cpuNanos / run.useful)
                .append(",\"threadCpuNanos\":").append(threadCpuNanos)
                .append(",\"threadCpuPerUsefulCompletionNanos\":")
                .append(run.useful == 0 || run.windowEndThreadCpuNanos < 0 ? -1L
                        : threadCpuNanos / run.useful)
                .append(",\"depthSampleIntervalNanos\":").append(run.depthIntervalNanos)
                .append(",\"depthSeries\":").append(depthSeries(run))
                .append(",\"trailing\":").append(trailingWindows(run, windowNanos))
                .append(",\"p50Nanos\":").append(SchedulerSwitchBenchmark.percentile(sorted, n, 0.50))
                .append(",\"p95Nanos\":").append(SchedulerSwitchBenchmark.percentile(sorted, n, 0.95))
                .append(",\"p99Nanos\":").append(SchedulerSwitchBenchmark.percentile(sorted, n, 0.99))
                .append(",\"maxLatencyNanos\":").append(n == 0 ? -1L : sorted[n - 1])
                .append(",\"pendingAtEnd\":").append(run.pendingAtEnd)
                .append(",\"claimedAtEnd\":").append(run.claimedAtEnd)
                .append(",\"submittingAtEnd\":").append(run.submittingAtEnd)
                .append(",\"pendingAtClose\":").append(run.closedPending)
                .append(",\"claimedAtClose\":").append(run.closedClaimed)
                .append(",\"submittingAtClose\":").append(run.closedSubmitting)
                .append(",\"inFlightAtClose\":").append(run.inFlightAtEnd)
                .append(",\"accountingClosure\":").append(run.closure())
                .append(",\"workConserved\":").append(run.admitted == run.closure())
                .append(",\"maxBacklog\":").append(run.maxBacklog)
                .append(",\"meanBacklog\":")
                .append(run.backlogSamples == 0 ? "-1"
                        : fmt((double) run.backlogSum / run.backlogSamples))
                .append(",\"pendingAtSwitch\":").append(-1)
                .append(",\"maxQueueDepth\":").append(profile.maxPending)
                // No index exists in the old arm; zeros here are "not applicable", and the driver's
                // own admitted-minus-delivered depth is the series both arms are read on.
                .append(",\"indexNodesAtEnd\":").append(0)
                .append(",\"maxLiveIndexes\":").append(run.newEngine ? 1 : 0)
                .append(",\"indexesCreated\":").append(run.newEngine ? 1 : 0)
                .append(",\"indexesCleared\":").append(0)
                .append(",\"liveIndexesAtEnd\":").append(run.newEngine ? 1 : 0)
                .append(",\"slotsInFlightAtEnd\":").append(inFlightSlots(run))
                .append(",\"slotCapacity\":").append(capacityTotal)
                .append(",\"processCpuNanos\":").append(cpuNanos)
                .append(",\"allocatedBytes\":").append(allocated)
                .append(",\"allocatedBytesPerUsefulCompletion\":")
                .append(run.useful == 0 ? -1L : allocated / run.useful)
                .append(",\"preRunHeapBytes\":").append(run.preRunHeapBytes)
                .append(",\"postGcHeapBytes\":").append(run.postGcHeapBytes)
                .append(",\"driverFailures\":").append(run.driverFailures.get())
                .append(",\"switch\":null");

        appendPerFunction(json, run);
        json.append('}');
        out.println(json);
    }

    /** The name the old loop is carried under, for the strategy slots the schema always has. */
    static final String OLD_ARM_SUBJECT = "old-async";

    private static long inFlightSlots(MeasuredRun run) {
        long total = 0;
        for (String function : run.profile.functions) {
            total += run.capacity.inFlight(function);
        }
        return total;
    }

    private static String depthSeries(MeasuredRun run) {
        int count = run.depthCount;
        StringBuilder json = new StringBuilder(count * 5 + 2);
        json.append('[');
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(run.depthSeries[i]);
        }
        return json.append(']').toString();
    }

    /**
     * The latency distribution over each trailing window of the span, keyed by length in
     * milliseconds, with the committed harness's admission-based membership rule: a sample belongs
     * to a window when it was <em>admitted</em> inside it.
     */
    private static String trailingWindows(MeasuredRun run, long spanNanos) {
        StringBuilder json = new StringBuilder(2048);
        json.append('{');
        boolean first = true;
        for (int lengthMs : TRAILING_WINDOWS_MS) {
            long lengthNanos = Math.min(lengthMs * 1_000_000L, spanNanos);
            long from = run.windowEndNanos - lengthNanos;
            int count = 0;
            long useful = 0;
            for (int i = 0; i < sampleCount(); i++) {
                if (LATENCY_ADMITTED[i] >= from && LATENCY_ADMITTED[i] <= run.windowEndNanos) {
                    SCRATCH[count++] = LATENCY[i];
                    if (LATENCY[i] <= run.contractNanos()) {
                        useful++;
                    }
                }
            }
            long[] sorted = Arrays.copyOf(SCRATCH, count);
            Arrays.sort(sorted);
            double seconds = lengthNanos / 1e9;
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(lengthMs).append("\":{")
                    .append("\"samples\":").append(count)
                    .append(",\"useful\":").append(useful)
                    .append(",\"usefulThroughputPerSecond\":")
                    .append(fmt(count == 0 ? 0.0 : useful / seconds))
                    .append(",\"p50Nanos\":").append(SchedulerSwitchBenchmark.percentile(sorted, count, 0.50))
                    .append(",\"p95Nanos\":").append(SchedulerSwitchBenchmark.percentile(sorted, count, 0.95))
                    .append(",\"p99Nanos\":").append(SchedulerSwitchBenchmark.percentile(sorted, count, 0.99))
                    .append(",\"maxNanos\":").append(count == 0 ? -1L : sorted[count - 1])
                    .append('}');
        }
        return json.append('}').toString();
    }

    private static void appendPerFunction(StringBuilder json, MeasuredRun run) {
        SchedulerSwitchBenchmark.Profile profile = run.profile;
        json.append(",\"perFunction\":{");
        boolean first = true;
        for (int i = 0; i < profile.functions.length; i++) {
            if (run.perFunctionAdmitted[i] == 0 && run.perFunctionExpired.get(i) == 0
                    && run.perFunctionRemoved.get(i) == 0) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            int n = extractFunctionSamples(i);
            long[] sorted = Arrays.copyOf(SCRATCH, n);
            Arrays.sort(sorted);
            json.append('"').append(profile.functions[i]).append("\":{")
                    .append("\"admitted\":").append(run.perFunctionAdmitted[i])
                    .append(",\"completed\":").append(run.perFunctionCompleted[i])
                    .append(",\"useful\":").append(run.perFunctionUseful[i])
                    .append(",\"expired\":").append(run.perFunctionExpired.get(i))
                    .append(",\"removed\":").append(run.perFunctionRemoved.get(i))
                    .append(",\"sync\":").append(run.perFunctionSync[i])
                    .append(",\"async\":").append(run.perFunctionAsync[i])
                    .append(",\"p50Nanos\":").append(SchedulerSwitchBenchmark.percentile(sorted, n, 0.50))
                    .append(",\"p95Nanos\":").append(SchedulerSwitchBenchmark.percentile(sorted, n, 0.95))
                    .append(",\"p99Nanos\":").append(SchedulerSwitchBenchmark.percentile(sorted, n, 0.99))
                    .append(",\"maxNanos\":").append(n == 0 ? -1L : sorted[n - 1])
                    .append('}');
        }
        json.append('}');
    }

    /** Scatters one function's samples out of the shared arrays into {@link #SCRATCH}. */
    private static int extractFunctionSamples(int functionIndex) {
        int n = 0;
        for (int i = 0; i < sampleCount(); i++) {
            if (LATENCY_FUNCTION[i] == functionIndex) {
                SCRATCH[n++] = LATENCY[i];
            }
        }
        return n;
    }

    static synchronized void recordSample(int functionIndex, long admittedNanos, long latency) {
        if (sampleCount == SAMPLE_CAPACITY) {
            sampleOverflow = true;
            return;
        }
        LATENCY[sampleCount] = latency;
        LATENCY_FUNCTION[sampleCount] = functionIndex;
        LATENCY_ADMITTED[sampleCount] = admittedNanos;
        sampleCount++;
    }

    static synchronized void resetSamples() {
        sampleCount = 0;
        sampleOverflow = false;
    }

    static synchronized int sampleCount() {
        return sampleCount;
    }

    static synchronized boolean sampleOverflow() {
        return sampleOverflow;
    }

    // ==================================================================
    // Small helpers
    // ==================================================================

    private static long threadCpuSum() {
        long total = 0;
        for (long id : THREADS.getAllThreadIds()) {
            long cpu = THREADS.getThreadCpuTime(id);
            if (cpu > 0) {
                total += cpu;
            }
        }
        return total;
    }

    private static long allocatedBytes() {
        if (!THREADS.isThreadAllocatedMemorySupported()
                || !THREADS.isThreadAllocatedMemoryEnabled()) {
            return -1L;
        }
        return THREADS.getTotalThreadAllocatedBytes();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String prop(String key, String fallback) {
        return escape(System.getProperty(key, fallback));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void report(String message) {
        System.err.println("[old-vs-new] " + message);
        System.err.flush();
    }

    private static void park(long nanos) {
        LockSupport.parkNanos(nanos);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("expected --key=value, got " + arg);
            }
            int eq = arg.indexOf('=');
            opts.put(eq < 0 ? arg.substring(2) : arg.substring(2, eq),
                    eq < 0 ? "true" : arg.substring(eq + 1));
        }
        return opts;
    }

    private static int intOpt(Map<String, String> opts, String key, int fallback) {
        return opts.containsKey(key) ? Integer.parseInt(opts.get(key)) : fallback;
    }
}
