package it.unimib.datai.nanofaas;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import it.unimib.datai.nanofaas.execution.EngineDispatch;
import it.unimib.datai.nanofaas.execution.EngineReadiness;
import it.unimib.datai.nanofaas.execution.EngineQueueSnapshot;
import it.unimib.datai.nanofaas.execution.PendingEntry;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.execution.StrategyRegistry;
import it.unimib.datai.nanofaas.modules.asyncqueue.PerFunctionSchedulingStrategy;
import it.unimib.datai.nanofaas.modules.syncqueue.SharedQueueSchedulingStrategy;

import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

/**
 * The scheduler-switching benchmark harness (issue #208, Task 12c).
 *
 * <h2>What it measures, and against what</h2>
 *
 * It drives the <em>real</em> {@link SchedulerEngine} with the two <em>real</em> preserved
 * strategies — {@link PerFunctionSchedulingStrategy} and {@link SharedQueueSchedulingStrategy} —
 * against a real {@link FunctionCapacityRegistry} and a real {@link PendingWorkStore}, and compares
 * each strategy against itself with and without a mid-run manual switch. Every threshold it
 * compares against is read from {@code budgets.json} at run time rather than restated here, so the
 * frozen numbers cannot drift from the ones being checked.
 *
 * <h2>Why a real clock, and why the engine's own pause figure</h2>
 *
 * The subject of the measurement is a pause — how long a manual switch stalls dispatch — and a
 * latency distribution. A simulated clock makes both structurally unmeasurable: it would return
 * whatever the harness chose to advance it by. So the harness reads {@link System#nanoTime}, and
 * {@code SchedulerEngine}'s own {@code nanoTime} supplier is bound to the same clock. The budget
 * comparison uses the engine's own {@code SwitchObserver} figure — the duration the engine itself
 * records around a switch — and the client-side elapsed time is emitted beside it as
 * corroboration, never substituted for it.
 *
 * <h2>Service duration is modelled, not skipped</h2>
 *
 * Spec §11: <em>distinguere simulazione a completamento istantaneo da servizio realistico — il
 * primo può nascondere il costo che interessa.</em> A dispatcher that completes instantly frees
 * every capacity slot in the same pass that took it, which makes both strategies look identical
 * and hides queueing entirely. Here {@link ControlledDispatch#submit} is non-blocking exactly as
 * {@link EngineDispatch} documents, but the capacity lease it was handed is held for the
 * function's declared service duration and released only once that duration has actually elapsed:
 * a slot is genuinely occupied, and work that cannot get one genuinely waits.
 *
 * <h2>Useful throughput, not dispatch rate</h2>
 *
 * Spec §11: <em>il dispatch rate da solo non basta; una strategia può avviare la stessa quantità di
 * lavoro e far scadere più chiamanti.</em> Every ticket carries a queue deadline and every
 * completion is classified against the workload's contract deadline, so the headline throughput is
 * <em>useful</em> completions — work that finished inside the contract — and a ticket the engine
 * expires before dispatch is counted as a lost caller, not as throughput.
 *
 * <h2>No shadow dispatch</h2>
 *
 * There is one dispatch path: the engine's. Nothing here re-implements selection, ordering or
 * admission, and no second index or worker is stood up beside the engine's. {@link
 * ControlledDispatch} takes the place of the transport/lifecycle that sits on the other side of
 * {@link EngineDispatch} in the real system — the same seat the real adapter occupies — rather
 * than beside it.
 *
 * <h2>Output</h2>
 *
 * One JSON object per line on stdout; human progress on stderr. Three kinds of line: {@code
 * header} (once), {@code switch} (every switch request), {@code sample} (one measured run) and
 * {@code baseline} (the 1000-switch return-to-baseline check). {@code run.sh} tees stdout into
 * {@code raw/}. No JSON library: the JDK is enough.
 */
public final class SchedulerSwitchBenchmark {

    static final String PER_FUNCTION = "per-function";
    static final String SHARED_QUEUE = "shared-queue";

    /**
     * The measured span per repetition unless a workload overrides it. Long enough that an arm
     * which inherits a queue at the switch can be observed after that queue has settled as well as
     * while it is settling: the span is reported in trailing-window slices, so the settling segment
     * and the steady segment are both in the artifact rather than one of them being chosen away.
     */
    static final int SPAN_MS = 8000;
    /**
     * Trailing windows, in milliseconds before the end of the span, that the artifact reports the
     * latency distribution over. The steady figure is read off whichever of these the backlog's own
     * convergence points at; the grid exists so that choice is a reading of the trajectory rather
     * than a re-run.
     */
    static final int[] TRAILING_WINDOWS_MS = {250, 500, 1000, 1500, 2000, 2500, 3000, 4000, 5000,
            6000, 8000};
    /** How often the driver samples the queue depth while the span is open. */

    /** How often the driver samples the queue depth, in milliseconds. */
    static final int DEPTH_SAMPLE_MS = 50;
    /** Upper bound on the sampled depth trajectory: 4x the longest supported span. */
    static final int DEPTH_SAMPLES = 4096;
    /** Warm-up before the measured window, identical for every arm. */
    static final int WARMUP_MS = 1500;

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    /** Completed-attempt end-to-end latencies, in nanos. Reused, never reallocated per run. */
    private static final int SAMPLE_CAPACITY = 1 << 20;
    private static final long[] LATENCY = new long[SAMPLE_CAPACITY];
    private static final int[] LATENCY_FUNCTION = new int[SAMPLE_CAPACITY];
    /** When each recorded sample was admitted: what puts it in one trailing window or another. */
    private static final long[] LATENCY_ADMITTED = new long[SAMPLE_CAPACITY];
    private static final long[] SCRATCH = new long[SAMPLE_CAPACITY];
    private static int sampleCount;
    private static boolean sampleOverflow;

    /** Every switch pause recorded in this process, for the process-wide pause max. */
    private static final List<Long> allSwitchPauses = new ArrayList<>();
    /** The 1000-switch run's own pauses: the only population large enough for a p99 to mean anything. */
    private static final List<Long> baselineSwitchPauses = new ArrayList<>();

    private static PrintStream out = System.out;

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        Path budgetsFile = Path.of(opts.getOrDefault("budgets", "budgets.json"));
        Budgets budgets = Budgets.read(budgetsFile);
        int repetitions = intOpt(opts, "repetitions", budgets.repetitions());
        int[] backlogs = opts.containsKey("backlogs")
                ? parseInts(opts.get("backlogs")) : budgets.switchBacklogSizes();
        // --window-ms is the name this option had before the span replaced the window; it still
        // works so the commands recorded in RESULTS.md for the earlier protocol remain runnable.
        int spanOverrideMs = intOpt(opts, "span-ms", intOpt(opts, "window-ms", 0));
        Set<String> parts = Set.of(opts.getOrDefault("parts", "backlog,profiles,switches").split(","));
        String selection = opts.getOrDefault("profiles", "all");

        if (opts.containsKey("out")) {
            out = new PrintStream(Files.newOutputStream(Path.of(opts.get("out"))),
                    false, StandardCharsets.UTF_8);
        }

        emitHeader(budgetsFile, budgets);

        if (parts.contains("backlog")) {
            new SwitchPauseSweep(backlogs, repetitions).run();
        }
        if (parts.contains("profiles")) {
            for (Profile profile : Profiles.select(selection)) {
                if (spanOverrideMs > 0) {
                    profile.spanMs = spanOverrideMs;
                }
                new WorkloadCampaign(profile, repetitions).run();
            }
        }
        if (parts.contains("switches")) {
            // The count is the frozen one, never an option: a default of zero would let the
            // documented command run everything except the switch-count check, and a silently
            // omitted part is indistinguishable from a passing one.
            new ReturnToBaseline(budgets.switchesInSoak()).run();
        }
        emitSummary(budgets);
        out.flush();
    }

    // ==================================================================
    // Header and summary
    // ==================================================================

    private static void emitHeader(Path budgetsFile, Budgets budgets) {
        StringBuilder json = new StringBuilder(512);
        json.append("{\"kind\":\"header\"")
                .append(",\"campaign\":\"scheduler-switching-2026-09\"")
                .append(",\"task\":\"12c\"")
                .append(",\"startedAt\":\"").append(Instant.now()).append('"')
                .append(",\"sha\":\"").append(prop("nanofaas.sha", "unknown")).append('"')
                .append(",\"artifact\":\"").append(prop("nanofaas.artifact", "jvm")).append('"')
                .append(",\"harnessSha256\":\"").append(prop("nanofaas.harnessSha", "unknown"))
                .append('"')
                .append(",\"jvm\":\"").append(escape(System.getProperty("java.vm.name", "?"))).append(' ')
                .append(escape(System.getProperty("java.vm.version", "?"))).append('"')
                .append(",\"javaVersion\":\"").append(escape(System.getProperty("java.version", "?")))
                .append('"')
                .append(",\"host\":\"").append(escape(host())).append('"')
                .append(",\"availableProcessors\":").append(Runtime.getRuntime().availableProcessors())
                .append(",\"maxHeapBytes\":").append(Runtime.getRuntime().maxMemory())
                .append(",\"threadAllocatedMemorySupported\":")
                .append(THREADS.isThreadAllocatedMemorySupported())
                .append(",\"threadAllocatedMemoryEnabled\":")
                .append(THREADS.isThreadAllocatedMemoryEnabled())
                .append(",\"budgetsFile\":\"").append(escape(budgetsFile.toString())).append('"')
                .append(",\"budgets\":").append(budgets.asJson())
                .append('}');
        out.println(json);
    }

    /** Process-wide roll-up: the pause population is every switch this process asked for. */
    private static void emitSummary(Budgets budgets) {
        double[] pauses = sortedMillis(allSwitchPauses);
        double[] soak = sortedMillis(baselineSwitchPauses);
        StringBuilder json = new StringBuilder(512);
        json.append("{\"kind\":\"summary\"")
                .append(",\"switchPausesRecorded\":").append(pauses.length)
                .append(",\"pauseP50Ms\":").append(pauses.length == 0 ? "-1" : fmt(pauses[pauses.length / 2]))
                .append(",\"pauseP99Ms\":").append(pauses.length == 0 ? "-1" : fmt(percentileMillisSorted(pauses, 0.99)))
                .append(",\"pauseMaxMs\":").append(pauses.length == 0 ? "-1" : fmt(pauses[pauses.length - 1]))
                .append(",\"soakSwitchPauses\":").append(soak.length)
                .append(",\"soakPauseP50Ms\":").append(soak.length == 0 ? "-1" : fmt(soak[soak.length / 2]))
                .append(",\"soakPauseP99Ms\":").append(soak.length == 0 ? "-1" : fmt(percentileMillisSorted(soak, 0.99)))
                .append(",\"soakPauseMaxMs\":").append(soak.length == 0 ? "-1" : fmt(soak[soak.length - 1]))
                .append(",\"budgetMaxSwitchPauseMs\":").append(budgets.maxSwitchPauseMs)
                .append(",\"budgetMaxSwitchPauseP99Ms\":").append(budgets.maxSwitchPauseP99Ms)
                .append('}');
        out.println(json);
    }

    private static String host() {
        return System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "?")
                + " " + System.getProperty("os.arch", "?");
    }

    // ==================================================================
    // Arms
    // ==================================================================

    /** The four arms: each strategy without a switch, and each direction of a manual switch. */
    enum Arm {
        PER_FUNCTION_NO_CHANGE(PER_FUNCTION, PER_FUNCTION),
        SHARED_QUEUE_NO_CHANGE(SHARED_QUEUE, SHARED_QUEUE),
        PER_FUNCTION_TO_SHARED_QUEUE(PER_FUNCTION, SHARED_QUEUE),
        SHARED_QUEUE_TO_PER_FUNCTION(SHARED_QUEUE, PER_FUNCTION);

        final String initial;
        final String target;

        Arm(String initial, String target) {
            this.initial = initial;
            this.target = target;
        }

        String label() {
            return initial.equals(target) ? initial + " (no change)" : initial + " -> " + target;
        }

        boolean switched() {
            return !initial.equals(target);
        }
    }

    // ==================================================================
    // One measured run
    // ==================================================================

    /**
     * One controlled run. Owns its engine, its capacity registry, its pending store and its
     * accounting, so no repetition inherits state from the one before it. The only thing shared
     * across arms and repetitions is the JVM itself, which is what the warm-up exists to settle.
     */
    static final class Run {
        final Profile profile;
        final Arm arm;
        final int repetition;
        final long seed;

        final FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        final PendingWorkStore store;
        final IndexAccounting accounting = new IndexAccounting();
        final ControlledDispatch dispatch;
        final SchedulerEngine engine;
        final List<SwitchEvent> switchEvents = new ArrayList<>();

        // Driver-thread counters.
        long offered, admitted, admissionRejected, churnRefused, completed, useful, retries, bursts;
        long backlogSamples, backlogSum, maxBacklog;
        // Worker-thread counters.
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

        /** True when this run's arm performs a manual switch; the window waits for it. */
        volatile boolean switchPending;
        volatile boolean switchArmed;
        volatile boolean switchDone;

        volatile long windowStartNanos;
        volatile long windowEndNanos;
        /** When the measured window closes. Set by the driver before any completion is recorded. */
        private volatile long windowCloseAtNanos;
        volatile int pendingAtEnd = -1;
        volatile int claimedAtEnd = -1;
        volatile int submittingAtEnd = -1;
        volatile int inFlightAtEnd = -1;
        /** The same four counts, taken after the driver stopped and the engine closed. */
        volatile int closedPending = -1;
        volatile int closedClaimed = -1;
        volatile int closedSubmitting = -1;
        volatile int pendingAtSwitch = -1;
        volatile long windowStartCpuNanos = -1;
        volatile long windowEndCpuNanos = -1;
        /** Sum of every live thread's CPU time: process CPU at the thread clock's resolution. */
        volatile long windowStartThreadCpuNanos = -1;
        volatile long windowEndThreadCpuNanos = -1;
        /** Queue depth sampled every DEPTH_SAMPLE_MS across the span, from the harness's counters. */
        final int[] depthSeries = new int[DEPTH_SAMPLES];
        volatile int depthCount;
        /** The nominal depth-sampling cadence; the sample count shows whether it held. */
        volatile long depthIntervalNanos = DEPTH_SAMPLE_MS * 1_000_000L;
        volatile long windowStartAllocBytes = -1;
        volatile long windowEndAllocBytes = -1;
        volatile long preRunHeapBytes = -1;
        volatile long postGcHeapBytes = -1;
        volatile boolean broken;

        Run(Profile profile, Arm arm, int repetition) {
            this.profile = profile;
            this.arm = arm;
            this.repetition = repetition;
            // Identical across the four arms of one (workload, repetition): the same corpus and the
            // same offered rate, so an arm comparison is paired rather than merely similar.
            this.seed = 208_000L + profile.name.hashCode() * 31L + repetition;

            this.switchPending = arm.switched();
            this.store = new PendingWorkStore(profile.maxPending);
            for (String function : profile.functions) {
                capacity.register(function, profile.capacity);
            }
            // The churned-out functions start retired and are re-registered mid-run; they are
            // registered above only so their index into the per-function arrays exists from the
            // start.
            for (String function : profile.churnedFunctions()) {
                capacity.remove(function);
            }
            this.dispatch = new ControlledDispatch(this);
            this.perFunctionExpired = new AtomicIntegerArray(profile.functions.length);
            this.perFunctionRemoved = new AtomicIntegerArray(profile.functions.length);
            this.perFunctionAdmitted = new int[profile.functions.length];
            this.perFunctionCompleted = new int[profile.functions.length];
            this.perFunctionUseful = new int[profile.functions.length];
            this.perFunctionSync = new int[profile.functions.length];
            this.perFunctionAsync = new int[profile.functions.length];

            List<SchedulingStrategy> strategies = List.of(
                    accounting.wrap(new PerFunctionSchedulingStrategy()),
                    accounting.wrap(new SharedQueueSchedulingStrategy()));
            this.engine = new SchedulerEngine(store, new StrategyRegistry(strategies), arm.initial,
                    dispatch, readiness(), Clock.systemUTC(), System::nanoTime);
            engine.setSwitchObserver(this::recordSwitch);
        }

        private EngineReadiness readiness() {
            Set<String> notReady = profile.notReadyFunctions();
            Set<String> churned = new HashSet<>(profile.churnedFunctions());
            return generation -> {
                String function = generation.functionName();
                return !notReady.contains(function) && !churned.contains(function);
            };
        }

        long contractNanos() {
            return profile.contractMs * 1_000_000L;
        }

        /** The declared service duration of one function: whole milliseconds plus a fixed tail. */
        long serviceNanos(String function) {
            return profile.serviceMillis(function) * 1_000_000L
                    + profile.serviceFractionNanos(function);
        }

        void recordSwitch(String target, SchedulerEngine.SwitchOutcome outcome, long durationNanos) {
            SwitchEvent event = new SwitchEvent();
            event.enginePauseNanos = durationNanos;
            event.observerOutcome = outcome.name();
            event.liveIndexes = accounting.live();
            event.indexNodes = accounting.indexNodes();
            switchEvents.add(event);
        }

        // ---------------- the measured window ----------------

        /** The single driver factory; the campaign and the baseline check use the same one. */
        Driver newDriver() {
            return new Driver();
        }

        /**
         * Runs the workload: fill, warm up, then measure. The fill and warm-up phases exist so the
         * measured window opens on a steady state rather than on the transient of an empty engine.
         */
        void execute() throws Exception {
            resetSamples();
            Runtime.getRuntime().gc();
            park(20_000_000L);
            preRunHeapBytes = usedHeap();

            engine.start();
            Driver driver = newDriver();
            // A run with a window is bounded by it; one without is bounded by its caller.
            driver.budgetNanos = (profile.warmupMs + profile.spanMs + 8_000L) * 1_000_000L;
            Thread driverThread = new Thread(driver, "benchmark-driver");
            driverThread.start();

            if (profile.spanMs > 0) {
                if (arm.switched()) {
                    // The switch lands on a fully loaded engine (the warm-up load is running and
                    // the driver keeps servicing it), and the measured window opens only once the
                    // switch has committed. That is what makes the comparison a same-strategy one:
                    // the whole measured window then runs on the target, so the delta against the
                    // target's no-change arm is the switch's own residue and not the other
                    // strategy's steady state leaking into the average.
                    long giveUp = System.nanoTime() + driver.budgetNanos;
                    while (!switchArmed && System.nanoTime() < giveUp && driverThread.isAlive()) {
                        LockSupport.parkNanos(100_000L);
                    }
                    if (switchArmed) {
                        performSwitch(arm.target);
                    } else {
                        broken = true;
                        report("window never armed for the switch: " + profile.name);
                    }
                    switchDone = true;
                }
                long giveUp = System.nanoTime() + driver.budgetNanos;
                while (windowStartNanos == 0L && System.nanoTime() < giveUp && driverThread.isAlive()) {
                    LockSupport.parkNanos(100_000L);
                }
            }

            driverThread.join(driver.budgetNanos / 1_000_000L + 30_000L);
            if (driverThread.isAlive()) {
                broken = true;
                report("driver did not stop: " + profile.name + " " + arm.label());
                driver.running = false;
                driver.deadlineNanos = 0L;
                driverThread.join(5_000L);
            }
            inFlightAtEnd = dispatch.inFlight.size();
            engine.close();
            freeze();
            postGcHeapBytes = postGcHeap();
            if (driver.failure != null) {
                broken = true;
                report("driver failed: " + driver.failure);
            }
        }

        /**
         * The engine's own figure is the one the budgets are compared against; the client-side
         * elapsed time is recorded beside it as corroboration.
         */
        void performSwitch(String target) {
            long clientStart = System.nanoTime();
            String outcome;
            try {
                engine.switchTo(target);
                outcome = "COMMITTED";
            } catch (RuntimeException failure) {
                outcome = "REFUSED:" + failure.getClass().getSimpleName();
            }
            long clientElapsed = System.nanoTime() - clientStart;
            EngineQueueSnapshot snapshot = engine.snapshotQueues();
            pendingAtSwitch = snapshot.pending();
            SwitchEvent event = switchEvents.get(switchEvents.size() - 1);
            event.clientElapsedNanos = clientElapsed;
            event.pending = snapshot.pending();
            event.target = target;
            event.outcome = outcome;
            emitSwitch(event, profile.name, arm.label(), repetition);
        }

        /**
         * The accounting identity: every admitted ticket is in exactly one of these buckets.
         *
         * <p>It is exact only against a frozen engine, which is why it reads the post-close counts
         * and not the window-boundary ones: the engine's worker is the only thing that could still
         * move a ticket from pending into flight, and it is stopped by then. A single duplicated
         * completion breaks this equality, so it doubles as the "no work lost, none duplicated"
         * check for every switch.
         */
        long closure() {
            return completed + expired.get() + removed.get() + rejected.get()
                    + Math.max(closedPending, 0) + Math.max(closedClaimed, 0)
                    + Math.max(closedSubmitting, 0) + Math.max(inFlightAtEnd, 0);
        }

        /**
         * Freezes the ticket populations. Must be called once the driver has stopped and the engine
         * has been closed, so nothing can move a ticket between the four counts.
         */
        void freeze() {
            EngineQueueSnapshot snapshot = engine.snapshotQueues();
            closedPending = snapshot.pending();
            closedClaimed = snapshot.claimed();
            closedSubmitting = snapshot.submitting();
            inFlightAtEnd = dispatch.inFlight.size();
        }

        // ---------------- the driver ----------------

        /**
         * The load generator and the service model's clock. Single-threaded by construction: one
         * arrival process, one completion sweep, no per-completion thread, so the harness's own
         * cost is a constant the arms share rather than a variable between them. It never touches
         * the engine's gate on the measurement path — queue depth is derived from its own
         * counters, not from {@code snapshotQueues()}, which would perturb what it measures.
         */
        final class Driver implements Runnable {
            /** How long the run may last after the driver thread starts; the run's own bound. */
            volatile long budgetNanos = 120_000_000_000L;
            volatile long deadlineNanos = Long.MAX_VALUE;
            volatile boolean running = true;
            volatile long startedNanos;
            volatile Throwable failure;

            private final Random arrivals = new Random(seed);
            private final Random retryRolls = new Random(seed * 31L + 7L);
            private long nextArrivalNanos;
            private boolean burstFired;
            private boolean capacityChanged;
            private boolean capacityRestored;
            private boolean churned;
            private long completionsSinceBacklogSample;
            private long nextDepthSampleNanos;

            @Override
            public void run() {
                try {
                    startedNanos = System.nanoTime();
                    // Set here rather than by the caller: the caller cannot know when this thread
                    // actually starts, and a deadline computed from a not-yet-set clock is a
                    // deadline in the past.
                    deadlineNanos = startedNanos + budgetNanos;
                    nextArrivalNanos = startedNanos;
                    long warmupEnd = startedNanos + profile.warmupMs * 1_000_000L;
                    prefill();
                    if (profile.spanMs <= 0) {
                        openWindow(startedNanos);
                    }
                    while (running) {
                        long now = System.nanoTime();
                        if (now >= deadlineNanos) {
                            break;
                        }
                        events(now);
                        serviceDue(now);
                        if (windowStartNanos != 0L && windowEndNanos == 0L
                                && now >= nextDepthSampleNanos) {
                            sampleDepth();
                            nextDepthSampleNanos = now + DEPTH_SAMPLE_MS * 1_000_000L;
                        }
                        if (profile.spanMs > 0) {
                            if (windowStartNanos == 0L && now >= warmupEnd) {
                                if (switchPending && !switchDone) {
                                    // Hand the switch to the caller and keep servicing the load:
                                    // the switch must land on a running engine, and the backlog it
                                    // rebuilds has to be a real one.
                                    switchArmed = true;
                                } else {
                                    openWindow(now);
                                }
                            }
                            // A profile with no measured window is bounded by its caller, not by
                            // a clock: this is how the return-to-baseline phase keeps the load
                            // running for exactly as long as its caller wants it to.
                            if (windowStartNanos != 0L
                                    && now >= windowStartNanos + profile.spanMs * 1_000_000L) {
                                closeWindow(now);
                                return;
                            }
                        }
                        long wait = Math.min(nextArrivalNanos, earliestCompletionDue()) - now;
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
                windowCloseAtNanos = now + profile.spanMs * 1_000_000L;
                windowEndNanos = 0L;
                depthCount = 0;
                nextDepthSampleNanos = now;
                sampleDepth();
                // Published last: the measuring thread keys the switch off this field.
                windowStartNanos = now;
            }

            /**
             * One point of the queue-depth trajectory, taken from the harness's own counters so it
             * never touches the engine's gate. This series is what says whether an arm has settled
             * and whether two arms are comparable at steady state — a maximum over the span cannot
             * tell either of those apart from a peak that has already decayed.
             */
            void sampleDepth() {
                long pending = admitted - completed - expired.get() - removed.get()
                        - rejected.get();
                if (depthCount < DEPTH_SAMPLES) {
                    depthSeries[depthCount++] = (int) Math.max(pending, 0);
                }
            }

            private void closeWindow(long now) {
                windowEndCpuNanos = OS.getProcessCpuTime();
                windowEndThreadCpuNanos = threadCpuSum();
                windowEndAllocBytes = allocatedBytes();
                EngineQueueSnapshot snapshot = engine.snapshotQueues();
                pendingAtEnd = snapshot.pending();
                claimedAtEnd = snapshot.claimed();
                submittingAtEnd = snapshot.submitting();
                windowEndNanos = now;
            }

            /** Pre-fills the profile's steady-state backlog, before the window opens. */
            private void prefill() {
                for (int i = 0; i < profile.prefill; i++) {
                    offer(false, null);
                }
            }

            /** Time-triggered workload events: capacity change, churn, burst, arrivals. */
            private void events(long now) {
                long spanElapsedMs = spanStartNanos() == 0L ? 0L
                        : (now - spanStartNanos()) / 1_000_000L;
                if (profile.capacityChangeAtPercent > 0 && !capacityChanged
                        && spanElapsedMs >= percentOfSpan(profile.capacityChangeAtPercent)) {
                    capacityChanged = true;
                    for (String function : profile.functions) {
                        capacity.setEffectiveConcurrency(function, profile.capacityChangeTo);
                    }
                    engine.signal();
                    // Read back what the registry now reports: a capacity change that did not take
                    // effect would silently turn this workload into a different one.
                    StringBuilder observed = new StringBuilder();
                    for (String function : profile.functions) {
                        observed.append(function).append('=')
                                .append(capacity.effectiveConcurrency(function)).append(' ');
                    }
                    report("capacity change requested " + profile.capacityChangeTo
                            + ", effective now: " + observed);
                }
                if (profile.capacityRestoreAtPercent > 0 && capacityChanged && !capacityRestored
                        && spanElapsedMs >= percentOfSpan(profile.capacityRestoreAtPercent)) {
                    capacityRestored = true;
                    for (String function : profile.functions) {
                        capacity.setEffectiveConcurrency(function, profile.capacity);
                    }
                    engine.signal();
                    StringBuilder restored = new StringBuilder();
                    for (String function : profile.functions) {
                        restored.append(function).append('=')
                                .append(capacity.effectiveConcurrency(function)).append(' ');
                    }
                    report("capacity restored to " + profile.capacity + ", effective now: " + restored);
                }
                if (profile.churnAtPercent > 0 && !churned
                        && spanElapsedMs >= percentOfSpan(profile.churnAtPercent)) {
                    churned = true;
                    churn();
                }
                if (profile.burstCount > 0 && !burstFired
                        && spanElapsedMs >= percentOfSpan(profile.burstAtPercent)) {
                    burstFired = true;
                    for (int i = 0; i < profile.burstCount; i++) {
                        offer(false, null);
                    }
                    bursts += profile.burstCount;
                }
                double rate = profile.totalOfferedPerSecond();
                if (rate > 0.0 && now >= nextArrivalNanos) {
                    offer(false, null);
                    nextArrivalNanos = now + (long) (-Math.log(1.0 - arrivals.nextDouble())
                            * 1_000_000_000.0 / rate);
                }
            }

            /**
             * Function removal: the queued callers of the removed functions are terminated rather
             * than stranded, and their replacements are registered under a fresh generation.
             */
            private void churn() {
                for (String function : profile.churnedFunctions()) {
                    engine.removeAllFor(function);
                    engine.markDraining(function);
                    capacity.remove(function);
                    report("churned out " + function);
                }
                for (String function : profile.replacementFunctions()) {
                    engine.clearDraining(function);
                    capacity.register(function, profile.capacity);
                    report("registered " + function);
                }
            }

            /** Releases the lease of every attempt whose declared service duration has elapsed. */
            private void serviceDue(long now) {
                int inFlight = dispatch.inFlight.size();
                for (int i = 0; i < inFlight; i++) {
                    Attempt attempt = dispatch.inFlight.poll();
                    if (attempt == null) {
                        break;
                    }
                    if (attempt.dueNanos > now) {
                        dispatch.inFlight.add(attempt);
                        continue;
                    }
                    complete(attempt, System.nanoTime());
                }
            }

            private long earliestCompletionDue() {
                long earliest = Long.MAX_VALUE;
                for (Attempt attempt : dispatch.inFlight) {
                    if (attempt.dueNanos < earliest) {
                        earliest = attempt.dueNanos;
                    }
                }
                return earliest;
            }

            private void complete(Attempt attempt, long completionNanos) {
                if (attempt.lease != null) {
                    attempt.lease.release();
                }
                Run.this.complete(attempt, completionNanos);
                // Backlog workloads ask for a standing backlog: the refill is what holds the
                // pending population at the size the profile declares instead of letting it drain
                // away before the switch lands.
                if (profile.prefill > 0 && admitted - delivered() < profile.prefill) {
                    offer(false, null);
                }
                if (profile.retryFraction > 0.0 && retryRolls.nextDouble() < profile.retryFraction) {
                    offer(true, attempt.functionIndex);
                }
                if (++completionsSinceBacklogSample >= 64) {
                    completionsSinceBacklogSample = 0;
                    sampleBacklog();
                }
            }

            /** Admitted work that has reached a terminal state: completed, expired or removed. */
            private long delivered() {
                return completed + expired.get() + removed.get() + rejected.get();
            }

            /** One offered arrival: exponential inter-arrivals, i.e. a Poisson offered load. */
            private void offer(boolean retry, Integer functionIndexOverride) {
                if (profile.stopAtPercent > 0 && spanStartNanos() != 0L
                        && (System.nanoTime() - spanStartNanos()) / 1_000_000L
                        >= percentOfSpan(profile.stopAtPercent)) {
                    return;
                }
                int index = functionIndexOverride != null ? functionIndexOverride
                        : chooseFunction();
                String function = profile.functions[index];
                FunctionGeneration generation = capacity.activeGeneration(function);
                if (generation == null) {
                    churnRefused++;
                    return;
                }
                long sequence = sequenceCounter++;
                String executionId = profile.name + '-' + index + '-' + sequence;
                int attempt = retry ? 2 : 1;
                offered++;
                if (retry) {
                    retries++;
                }
                InvocationKind kind = arrivals.nextDouble() < profile.syncFraction
                        ? InvocationKind.SYNC : InvocationKind.ASYNC;
                TicketId ticketId = new TicketId(executionId, attempt);
                Instant now = Instant.now();
                SchedulingTicket ticket = new SchedulingTicket(ticketId, generation, sequence, now,
                        now, now.plusMillis(profile.contractMs));
                InvocationRequest request = profile.payloadBytes(index) == 0 ? null
                        : new InvocationRequest(new byte[profile.payloadBytes(index)], Map.of());
                InvocationTask task = new InvocationTask(executionId, function, null, request, null,
                        null, now, attempt, kind);
                dispatch.admittedTimes.put(ticketId, System.nanoTime());
                dispatch.sync.put(ticketId, kind == InvocationKind.SYNC);
                dispatch.functionIndex.put(ticketId, index);
                if (engine.enqueue(new PendingEntry(ticket, task))) {
                    admitted++;
                    perFunctionAdmitted[index]++;
                } else {
                    admissionRejected++;
                    dispatch.forget(ticketId);
                }
            }

            private int chooseFunction() {
                if (profile.sporadicFunctions == 0) {
                    return arrivals.nextInt(profile.hotFunctions);
                }
                double hotShare = profile.hotRatePerSecond * profile.hotFunctions
                        / profile.totalOfferedPerSecond();
                if (arrivals.nextDouble() < hotShare) {
                    return arrivals.nextInt(profile.hotFunctions);
                }
                // Before the churn the sporadic pool is the registered set; afterwards the
                // replacements join it and the retired functions keep receiving arrivals that the
                // engine now refuses (counted as churnRefused, not as offered work). A pick of
                // `sporadicFunctions + j` is therefore the replacement appended at that offset.
                int pool = profile.sporadicFunctions + (churned ? profile.sporadicFunctions / 2 : 0);
                return profile.hotFunctions + arrivals.nextInt(pool);
            }

            private long sequenceCounter;
        }

        /** The instant the measured span opened, or 0 while it has not. */
        long spanStartNanos() {
            return windowStartNanos;
        }

        /** One workload-event offset, as a millisecond position inside the span. */
        long percentOfSpan(int percent) {
            return profile.spanMs * (long) percent / 100L;
        }

        /** Queue depth from the harness's own counters, without touching the engine's gate. */
        void sampleBacklog() {
            long pending = admitted - completed - expired.get() - removed.get() - rejected.get();
            if (pending < 0) {
                pending = 0;
            }
            backlogSamples++;
            backlogSum += pending;
            if (pending > maxBacklog) {
                maxBacklog = pending;
            }
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
            if (attempt.admittedNanos >= windowStartNanos
                    && completionNanos <= windowCloseAtNanos) {
                recordSample(attempt.functionIndex, attempt.admittedNanos, latency);
            }
        }

        private static synchronized void recordSample(int functionIndex, long admittedNanos,
                                                     long latency) {
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

        /** Sum of the active generations' in-flight slots: slot occupancy at the window's end. */
        long inFlightSlots() {
            long total = 0;
            for (String function : profile.functions) {
                total += capacity.inFlight(function);
            }
            return total;
        }
    }

    // ==================================================================
    // The controlled dispatcher
    // ==================================================================

    /** One dispatched attempt: its lease, its admission time, and when its service ends. */
    static final class Attempt {
        final String executionId;
        final int functionIndex;
        final long admittedNanos;
        final long dueNanos;
        final DispatchOwnership lease;
        final boolean sync;

        Attempt(String executionId, int functionIndex, long admittedNanos, long dueNanos,
                DispatchOwnership lease, boolean sync) {
            this.executionId = executionId;
            this.functionIndex = functionIndex;
            this.admittedNanos = admittedNanos;
            this.dueNanos = dueNanos;
            this.lease = lease;
            this.sync = sync;
        }
    }

    /**
     * The transport/lifecycle seat of the real system, modelled. {@code submit} publishes and
     * returns, exactly as {@link EngineDispatch} documents; the lease it was handed is held until
     * the driver judges the service duration elapsed. Capacity is the real {@link
     * FunctionCapacityRegistry}, so a held slot is genuinely unavailable and the engine's own
     * backpressure path is the one that runs.
     */
    static final class ControlledDispatch implements EngineDispatch {
        private final Run run;
        final ConcurrentLinkedQueue<Attempt> inFlight = new ConcurrentLinkedQueue<>();
        final ConcurrentHashMap<TicketId, Long> admittedTimes = new ConcurrentHashMap<>();
        final ConcurrentHashMap<TicketId, Boolean> sync = new ConcurrentHashMap<>();
        final ConcurrentHashMap<TicketId, Integer> functionIndex = new ConcurrentHashMap<>();
        private volatile DispatchOwnership lastLease;

        ControlledDispatch(Run run) {
            this.run = run;
        }

        @Override
        public DispatchOwnership tryAcquire(SchedulingTicket ticket) {
            DispatchOwnership lease = run.capacity.tryAcquireLease(ticket.generation(),
                    releasedNanos -> run.engine.signal());
            lastLease = lease;
            return lease;
        }

        @Override
        public boolean isCurrent(SchedulingTicket ticket) {
            return true;
        }

        @Override
        public void submit(InvocationTask task) {
            TicketId id = new TicketId(task.executionId(), task.attempt());
            Long admitted = admittedTimes.remove(id);
            Integer index = functionIndex.remove(id);
            Boolean kind = sync.remove(id);
            if (admitted == null || index == null) {
                return;
            }
            long due = System.nanoTime() + run.serviceNanos(task.functionName());
            inFlight.add(new Attempt(task.executionId(), index, admitted, due, lastLease,
                    kind != null && kind));
        }

        @Override
        public void expired(InvocationTask task) {
            run.expired.incrementAndGet();
            Integer index = forget(new TicketId(task.executionId(), task.attempt()));
            if (index != null) {
                run.perFunctionExpired.incrementAndGet(index);
            }
        }

        @Override
        public void removed(InvocationTask task) {
            run.removed.incrementAndGet();
            Integer index = forget(new TicketId(task.executionId(), task.attempt()));
            if (index != null) {
                run.perFunctionRemoved.incrementAndGet(index);
            }
        }

        @Override
        public void rejected(InvocationTask task, Throwable failure) {
            run.rejected.incrementAndGet();
            forget(new TicketId(task.executionId(), task.attempt()));
        }

        Integer forget(TicketId id) {
            admittedTimes.remove(id);
            sync.remove(id);
            return functionIndex.remove(id);
        }
    }

    // ==================================================================
    // Index accounting
    // ==================================================================

    /**
     * Wraps a strategy's {@link SchedulingIndex} so the population of live indexes and the size of
     * the active one are observable without reaching into the engine. An index counts as live from
     * the moment the strategy builds it until the engine clears it — the two events that bound the
     * engine's own retained population, since there is nowhere else an index is released — so this
     * is the engine's population, not a second bookkeeping of the harness's own.
     */
    static final class IndexAccounting {
        private final AtomicInteger live = new AtomicInteger();
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger cleared = new AtomicInteger();
        private final AtomicInteger maxLive = new AtomicInteger();
        private volatile SchedulingIndex newest;

        SchedulingStrategy wrap(SchedulingStrategy delegate) {
            return new SchedulingStrategy() {
                @Override
                public String id() {
                    return delegate.id();
                }

                @Override
                public SchedulingIndex newIndex() {
                    SchedulingIndex real = delegate.newIndex();
                    int now = live.incrementAndGet();
                    created.incrementAndGet();
                    maxLive.accumulateAndGet(now, Math::max);
                    SchedulingIndex wrapped = new SchedulingIndex() {
                        private boolean alive = true;

                        @Override
                        public void add(SchedulingTicket ticket) {
                            real.add(ticket);
                        }

                        @Override
                        public void remove(TicketId id) {
                            real.remove(id);
                        }

                        @Override
                        public SchedulingTicket select(Instant instant,
                                                      Predicate<FunctionGeneration> runnable) {
                            return real.select(instant, runnable);
                        }

                        @Override
                        public void defer(TicketId id) {
                            real.defer(id);
                        }

                        @Override
                        public int size() {
                            return real.size();
                        }

                        @Override
                        public void clear() {
                            real.clear();
                            if (alive) {
                                alive = false;
                                live.decrementAndGet();
                                cleared.incrementAndGet();
                            }
                        }
                    };
                    newest = wrapped;
                    return wrapped;
                }
            };
        }

        int live() {
            return live.get();
        }

        int maxLive() {
            return maxLive.get();
        }

        int created() {
            return created.get();
        }

        int cleared() {
            return cleared.get();
        }

        int indexNodes() {
            SchedulingIndex index = newest;
            return index == null ? 0 : index.size();
        }
    }

    // ==================================================================
    // Part A: switch pause against backlog size
    // ==================================================================

    /**
     * For each backlog size in {@code budgets.json}: build a saturated function with that many
     * tickets waiting, keep it saturated with a real completion/refill churn so the switch lands
     * under continuous load, and switch. This is the sweep the frozen pause budgets are read
     * against, and it is the only place a second index exists at all — so it is also where {@code
     * maxLiveStrategyIndexes} is observable.
     */
    static final class SwitchPauseSweep {

        private final int[] backlogs;
        private final int repetitions;

        SwitchPauseSweep(int[] backlogs, int repetitions) {
            this.backlogs = backlogs;
            this.repetitions = repetitions;
        }

        void run() throws Exception {
            for (int backlog : backlogs) {
                for (int repetition = 1; repetition <= repetitions; repetition++) {
                    // Alternate the direction: a pause measured in one direction is never read as a
                    // property of the pair.
                    Arm arm = ((repetition & 1) == 1)
                            ? Arm.PER_FUNCTION_TO_SHARED_QUEUE : Arm.SHARED_QUEUE_TO_PER_FUNCTION;
                    Profile profile = Profiles.switchPause(backlog);
                    Run run = new Run(profile, arm, repetition);
                    Run.resetSamples();
                    run.execute();
                    emitSwitchPauseSample(profile, arm, repetition, run);
                    report("backlog=" + backlog + " rep=" + repetition + " " + lastSwitchSummary(run));
                }
            }
        }
    }

    private static String lastSwitchSummary(Run run) {
        if (run.switchEvents.isEmpty()) {
            return "no switch event";
        }
        SwitchEvent event = run.switchEvents.get(run.switchEvents.size() - 1);
        return String.format(Locale.ROOT, "pause=%.3fms pending=%d liveIndexes=%d outcome=%s",
                event.enginePauseNanos / 1e6, event.pending, event.liveIndexes, event.outcome);
    }

    // ==================================================================
    // Part B: the workload campaign
    // ==================================================================

    /**
     * One workload across the four arms, {@code repetitions} times each in alternating order, with
     * an identical warm-up run before the first measured repetition of every arm.
     */
    static final class WorkloadCampaign {

        private final Profile profile;
        private final int repetitions;

        WorkloadCampaign(Profile profile, int repetitions) {
            this.profile = profile;
            this.repetitions = repetitions;
        }

        void run() throws Exception {
            Arm[] arms = Arm.values();
            for (Arm arm : arms) {
                Run warmup = new Run(profile, arm, 0);
                warmup.execute();
                report("warm-up " + profile.name + " " + arm.label());
            }
            for (int repetition = 1; repetition <= repetitions; repetition++) {
                Arm[] order = ((repetition & 1) == 1) ? arms : reversed(arms);
                for (Arm arm : order) {
                    Run run = new Run(profile, arm, repetition);
                    Run.resetSamples();
                    run.execute();
                    emitSample(profile, arm, repetition, run);
                    report(String.format(Locale.ROOT,
                            "%s %s rep=%d useful=%d p99=%.3fms backlogMax=%d",
                            profile.name, arm.label(), repetition, run.useful,
                            percentileOfSamples() / 1e6, run.maxBacklog));
                }
            }
        }

        private static Arm[] reversed(Arm[] arms) {
            Arm[] copy = arms.clone();
            for (int i = 0; i < copy.length / 2; i++) {
                Arm swap = copy[i];
                copy[i] = copy[copy.length - 1 - i];
                copy[copy.length - 1 - i] = swap;
            }
            return copy;
        }
    }

    // ==================================================================
    // Part C: 1000 switches and the return to baseline
    // ==================================================================

    /**
     * Drives {@code switchesInSoak} manual switches — that count, not a duration — across a
     * continuous load and checks the two things that make repeated switching acceptable: the engine
     * returns to the state it started from, and no work was lost, duplicated or stranded on the way
     * (the last is the accounting identity {@link Run#closure}, which a single duplicated
     * completion breaks).
     *
     * <p>This is the switch-count half of the plan's soak item and nothing more: the ≥60 minute
     * soak, with two duration classes and function churn, is Task 13's and is not run here.
     */
    static final class ReturnToBaseline {

        /** Wall clock per phase. The switched phase issues its switches spread across it. */
        private static final long PHASE_MS = 5000L;

        private final int switches;

        ReturnToBaseline(int switches) {
            this.switches = switches;
        }

        /**
         * Runs the same load twice — once with {@code switchesInSoak} manual switches spread across
         * it, once with none at all — and compares the two end states. That comparison is what
         * "returns to baseline" is measurable as: the switched engine must end the phase in the
         * same state as an engine that was never switched, under the same offered load and the same
         * corpus.
         */
        void run() throws Exception {
            Profile profile = Profiles.returnToBaseline();
            Phase switched = phase(profile, switches, Arm.PER_FUNCTION_TO_SHARED_QUEUE);
            Phase plain = phase(profile, 0, Arm.PER_FUNCTION_NO_CHANGE);

            StringBuilder json = new StringBuilder(1024);
            json.append("{\"kind\":\"baseline\"")
                    .append(",\"workload\":\"").append(profile.name).append('"')
                    .append(",\"phaseMillis\":").append(PHASE_MS)
                    .append(",\"switchesRequested\":").append(switches)
                    .append(",\"switchesCommitted\":").append(switched.committed)
                    .append(",\"switchesRefused\":").append(switched.refused)
                    .append(",\"pauseP50Ms\":").append(fmt(switched.pauseP50Ms))
                    .append(",\"pauseP99Ms\":").append(fmt(switched.pauseP99Ms))
                    .append(",\"pauseMaxMs\":").append(fmt(switched.pauseMaxMs))
                    .append(",\"switchedPending\":").append(switched.pending)
                    .append(",\"plainPending\":").append(plain.pending)
                    .append(",\"pendingDelta\":").append(switched.pending - plain.pending)
                    .append(",\"pendingDeltaPercent\":")
                    .append(percentDelta(switched.pending, plain.pending))
                    .append(",\"switchedReserved\":").append(Arrays.toString(switched.reserved))
                    .append(",\"plainReserved\":").append(Arrays.toString(plain.reserved))
                    .append(",\"reservationDeltaMax\":")
                    .append(maxDelta(switched.reserved, plain.reserved))
                    .append(",\"reservationDeltaMaxPercent\":")
                    .append(maxDeltaPercent(switched.reserved, plain.reserved))
                    .append(",\"switchedLiveIndexes\":").append(switched.liveIndexes)
                    .append(",\"plainLiveIndexes\":").append(plain.liveIndexes)
                    .append(",\"liveIndexesReturnedToBaseline\":")
                    .append(switched.liveIndexes == 1 && plain.liveIndexes == 1)
                    .append(",\"maxLiveIndexesSwitched\":").append(switched.maxLiveIndexes)
                    .append(",\"indexesCreated\":").append(switched.indexesCreated)
                    .append(",\"indexesCleared\":").append(switched.indexesCleared)
                    .append(",\"switchedAdmitted\":").append(switched.admitted)
                    .append(",\"switchedCompleted\":").append(switched.completed)
                    .append(",\"switchedExpired\":").append(switched.expired)
                    .append(",\"switchedUseful\":").append(switched.useful)
                    .append(",\"switchedClosure\":").append(switched.closure)
                    .append(",\"workConservedSwitched\":").append(switched.conserved())
                    .append(",\"plainAdmitted\":").append(plain.admitted)
                    .append(",\"plainCompleted\":").append(plain.completed)
                    .append(",\"plainExpired\":").append(plain.expired)
                    .append(",\"plainUseful\":").append(plain.useful)
                    .append(",\"plainClosure\":").append(plain.closure)
                    .append(",\"workConservedPlain\":").append(plain.conserved())
                    .append(",\"switchedHeapBytes\":").append(switched.postGcHeapBytes)
                    .append(",\"plainHeapBytes\":").append(plain.postGcHeapBytes)
                    .append('}');
            out.println(json);
            report("return-to-baseline: committed=" + switched.committed + " refused="
                    + switched.refused
                    + " pending switched=" + switched.pending + " vs plain=" + plain.pending
                    + " live switched=" + switched.liveIndexes + " vs plain=" + plain.liveIndexes
                    + " maxLive=" + switched.maxLiveIndexes
                    + " conserved switched=" + switched.conserved()
                    + " plain=" + plain.conserved());
        }

        /** One phase: the same load for PHASE_MS, with {@code count} switches spread across it. */
        private Phase phase(Profile profile, int count, Arm arm) throws Exception {
            Run run = new Run(profile, arm, count > 0 ? 1 : 0);
            Runtime.getRuntime().gc();
            park(20_000_000L);
            Phase phase = new Phase();
            phase.preRunHeapBytes = run.usedHeap();

            run.engine.start();
            Run.Driver driver = run.newDriver();
            driver.budgetNanos = PHASE_MS * 1_000_000L;
            Thread driverThread = new Thread(driver, "baseline-driver");
            driverThread.start();
            // Settle the load before the switches start.
            park(500_000_000L);

            List<Long> pauses = new ArrayList<>(count);
            if (count > 0) {
                long gapNanos = Math.max(1L, (PHASE_MS - 500L) * 1_000_000L / count);
                for (int i = 0; i < count; i++) {
                    String target = (i % 2 == 0) ? SHARED_QUEUE : PER_FUNCTION;
                    long start = System.nanoTime();
                    String outcome;
                    try {
                        run.engine.switchTo(target);
                        phase.committed++;
                        outcome = "COMMITTED";
                    } catch (RuntimeException failure) {
                        phase.refused++;
                        outcome = "REFUSED:" + failure.getClass().getSimpleName();
                        report("switch " + i + " refused: " + failure);
                    }
                    long elapsed = System.nanoTime() - start;
                    SwitchEvent event = run.switchEvents.get(run.switchEvents.size() - 1);
                    pauses.add(event.enginePauseNanos);
                    baselineSwitchPauses.add(event.enginePauseNanos);
                    allSwitchPauses.add(event.enginePauseNanos);
                    event.target = target;
                    event.outcome = outcome;
                    event.pending = run.engine.snapshotQueues().pending();
                    event.clientElapsedNanos = elapsed;
                    event.liveIndexes = run.accounting.live();
                    event.indexNodes = run.accounting.indexNodes();
                    // Spread the switches across the phase: a switch under load, not a burst of
                    // switches against a momentarily idle engine.
                    long wait = gapNanos - (System.nanoTime() - start);
                    if (wait > 0) {
                        park(wait);
                    }
                }
            } else {
                park((PHASE_MS - 500L) * 1_000_000L);
            }

            driver.running = false;
            driverThread.join(10_000L);
            run.engine.close();
            // Frozen with the worker stopped: the accounting identity is exact only here.
            run.freeze();

            phase.pending = run.closedPending;
            phase.reserved = reservedSnapshot(run, profile);
            phase.liveIndexes = run.accounting.live();
            phase.maxLiveIndexes = run.accounting.maxLive();
            phase.indexesCreated = run.accounting.created();
            phase.indexesCleared = run.accounting.cleared();
            phase.admitted = run.admitted;
            phase.completed = run.completed;
            phase.expired = run.expired.get();
            phase.useful = run.useful;
            phase.closure = run.closure();
            phase.postGcHeapBytes = run.postGcHeap();
            double[] sorted = sortedMillis(pauses);
            phase.pauseP50Ms = sorted.length == 0 ? -1.0 : sorted[sorted.length / 2];
            phase.pauseP99Ms = percentileMillisSorted(sorted, 0.99);
            phase.pauseMaxMs = sorted.length == 0 ? -1.0 : sorted[sorted.length - 1];
            return phase;
        }

        /** One phase's end state. */
        private static final class Phase {
            int committed;
            int refused;
            int pending;
            int liveIndexes;
            int maxLiveIndexes;
            int indexesCreated;
            int indexesCleared;
            long admitted;
            long completed;
            long expired;
            long useful;
            long closure;
            long preRunHeapBytes;
            long postGcHeapBytes;
            double pauseP50Ms;
            double pauseP99Ms;
            double pauseMaxMs;
            long[] reserved;

            boolean conserved() {
                return admitted == closure;
            }
        }

        private static long[] reservedSnapshot(Run run, Profile profile) {
            long[] reserved = new long[profile.functions.length];
            for (int i = 0; i < reserved.length; i++) {
                reserved[i] = run.engine.reservedCount(profile.functions[i]);
            }
            return reserved;
        }
    }

    // ==================================================================
    // Sample emission
    // ==================================================================

    // ==================================================================
    // Switch events
    // ==================================================================

    /**
     * One switch request and what it cost. {@code enginePauseNanos} is the engine's own figure,
     * taken from its {@code SwitchObserver}; {@code clientElapsedNanos} is what the caller waited,
     * which necessarily includes the observer's own callout. {@code pending} is the backlog the
     * rebuild had to carry, and {@code liveIndexes} is how many indexes the engine held when the
     * switch finished.
     */
    static final class SwitchEvent {
        String target = "";
        String outcome = "";
        String observerOutcome = "";
        long enginePauseNanos;
        long clientElapsedNanos;
        int pending = -1;
        int liveIndexes = -1;
        int indexNodes = -1;
    }

    /**
     * One line per switch request. The engine's own pause figure leads, the client-side elapsed
     * time follows it, and the pending population is the backlog the rebuild actually carried.
     * Warm-up switches are emitted too — they are real switches — but are excluded from the pause
     * population the budgets are read against, which is why {@code repetition} gates the roll-up.
     */
    private static void emitSwitch(SwitchEvent event, String workload, String arm, int repetition) {
        StringBuilder json = new StringBuilder(384);
        json.append("{\"kind\":\"switch\"")
                .append(",\"workload\":\"").append(workload).append('"')
                .append(",\"arm\":\"").append(arm).append('"')
                .append(",\"repetition\":").append(repetition)
                .append(",\"target\":\"").append(event.target).append('"')
                .append(",\"outcome\":\"").append(event.outcome).append('"')
                .append(",\"observerOutcome\":\"").append(event.observerOutcome).append('"')
                .append(",\"enginePauseNanos\":").append(event.enginePauseNanos)
                .append(",\"enginePauseMs\":").append(fmt(event.enginePauseNanos / 1e6))
                .append(",\"clientElapsedNanos\":").append(event.clientElapsedNanos)
                .append(",\"clientElapsedMs\":").append(fmt(event.clientElapsedNanos / 1e6))
                .append(",\"pending\":").append(event.pending)
                .append(",\"liveIndexes\":").append(event.liveIndexes)
                .append(",\"indexNodes\":").append(event.indexNodes)
                .append('}');
        synchronized (SchedulerSwitchBenchmark.class) {
            out.println(json);
            if (repetition > 0) {
                allSwitchPauses.add(event.enginePauseNanos);
            }
        }
    }

    private static void emitSwitchPauseSample(Profile profile, Arm arm, int repetition, Run run) {
        SwitchEvent event = run.switchEvents.isEmpty() ? new SwitchEvent()
                : run.switchEvents.get(run.switchEvents.size() - 1);
        StringBuilder json = new StringBuilder(512);
        json.append("{\"kind\":\"sample\"")
                .append(",\"workload\":\"").append(profile.name).append('"')
                .append(",\"arm\":\"").append(arm.label()).append('"')
                .append(",\"repetition\":").append(repetition)
                .append(",\"requestedBacklog\":").append(profile.prefill)
                .append(",\"switchPauseNanos\":").append(event.enginePauseNanos)
                .append(",\"switchPauseMs\":").append(fmt(event.enginePauseNanos / 1e6))
                .append(",\"switchClientElapsedMs\":").append(fmt(event.clientElapsedNanos / 1e6))
                .append(",\"switchOutcome\":\"").append(event.outcome).append('"')
                .append(",\"pendingAtSwitch\":").append(event.pending)
                .append(",\"liveIndexes\":").append(event.liveIndexes)
                .append(",\"indexNodes\":").append(event.indexNodes)
                .append(",\"maxLiveIndexes\":").append(run.accounting.maxLive())
                .append(",\"indexesCreated\":").append(run.accounting.created())
                .append(",\"indexesCleared\":").append(run.accounting.cleared())
                .append(",\"completed\":").append(run.completed)
                .append(",\"useful\":").append(run.useful)
                .append(",\"pendingAtEnd\":").append(run.pendingAtEnd)
                .append(",\"driverFailures\":").append(run.driverFailures.get())
                .append('}');
        out.println(json);
    }

    private static void emitSample(Profile profile, Arm arm, int repetition, Run run) {
        int n = Run.sampleCount();
        long[] sorted = Arrays.copyOf(LATENCY, n);
        Arrays.sort(sorted);
        long windowNanos = run.windowEndNanos - run.windowStartNanos;
        double windowSeconds = windowNanos / 1e9;
        long cpuNanos = run.windowEndCpuNanos - run.windowStartCpuNanos;
        long allocated = run.windowEndAllocBytes - run.windowStartAllocBytes;
        long slots = run.inFlightSlots();
        long capacityTotal = (long) profile.capacity * profile.functions.length;

        StringBuilder json = new StringBuilder(8192);
        json.append("{\"kind\":\"sample\"")
                .append(",\"workload\":\"").append(profile.name).append('"')
                .append(",\"note\":\"").append(escape(profile.note)).append('"')
                .append(",\"arm\":\"").append(arm.label()).append('"')
                .append(",\"initialStrategy\":\"").append(arm.initial).append('"')
                .append(",\"finalStrategy\":\"").append(arm.target).append('"')
                .append(",\"repetition\":").append(repetition)
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
                .append(",\"sampleCapReached\":").append(Run.sampleOverflow())
                .append(",\"processCpuPerUsefulCompletionNanos\":")
                .append(run.useful == 0 ? -1L : cpuNanos / run.useful)
                .append(",\"threadCpuNanos\":")
                .append(run.windowEndThreadCpuNanos - run.windowStartThreadCpuNanos)
                .append(",\"threadCpuPerUsefulCompletionNanos\":")
                .append(run.useful == 0 || run.windowEndThreadCpuNanos < 0 ? -1L
                        : (run.windowEndThreadCpuNanos - run.windowStartThreadCpuNanos) / run.useful)
                .append(",\"depthSampleIntervalNanos\":").append(run.depthIntervalNanos)
                .append(",\"depthSeries\":").append(depthSeries(run))
                .append(",\"trailing\":").append(trailingWindows(run, windowNanos))
                .append(",\"p50Nanos\":").append(percentile(sorted, n, 0.50))
                .append(",\"p95Nanos\":").append(percentile(sorted, n, 0.95))
                .append(",\"p99Nanos\":").append(percentile(sorted, n, 0.99))
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
                .append(run.backlogSamples == 0 ? "-1" : fmt((double) run.backlogSum / run.backlogSamples))
                .append(",\"pendingAtSwitch\":").append(run.pendingAtSwitch)
                .append(",\"maxQueueDepth\":").append(profile.maxPending)
                .append(",\"indexNodesAtEnd\":").append(run.accounting.indexNodes())
                .append(",\"maxLiveIndexes\":").append(run.accounting.maxLive())
                .append(",\"indexesCreated\":").append(run.accounting.created())
                .append(",\"indexesCleared\":").append(run.accounting.cleared())
                .append(",\"liveIndexesAtEnd\":").append(run.accounting.live())
                .append(",\"slotsInFlightAtEnd\":").append(slots)
                .append(",\"slotCapacity\":").append(capacityTotal)
                .append(",\"processCpuNanos\":").append(cpuNanos)
                .append(",\"allocatedBytes\":").append(allocated)
                .append(",\"allocatedBytesPerUsefulCompletion\":")
                .append(run.useful == 0 ? -1L : allocated / run.useful)
                .append(",\"preRunHeapBytes\":").append(run.preRunHeapBytes)
                .append(",\"postGcHeapBytes\":").append(run.postGcHeapBytes)
                .append(",\"driverFailures\":").append(run.driverFailures.get());

        json.append(",\"switch\":");
        if (run.switchEvents.isEmpty()) {
            json.append("null");
        } else {
            SwitchEvent event = run.switchEvents.get(run.switchEvents.size() - 1);
            json.append("{\"target\":\"").append(event.target).append('"')
                    .append(",\"outcome\":\"").append(event.outcome).append('"')
                    .append(",\"enginePauseNanos\":").append(event.enginePauseNanos)
                    .append(",\"clientElapsedNanos\":").append(event.clientElapsedNanos)
                    .append(",\"pending\":").append(event.pending)
                    .append(",\"liveIndexes\":").append(event.liveIndexes)
                    .append('}');
        }

        appendPerFunction(json, profile, run);
        json.append('}');
        out.println(json);
    }

    /** The sampled queue-depth trajectory, as a JSON array, and how it was sampled. */
    private static String depthSeries(Run run) {
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
     * The latency distribution over each trailing window of the span, a JSON object keyed by the
     * window's length in milliseconds. A sample belongs to a window when it was <em>admitted</em>
     * inside it, so a window answers "how did work that arrived in this segment fare" rather than
     * "how did work that finished in it fare" — the settling work admitted earlier cannot be
     * counted into a late window's arrivals.
     *
     * <p>Reporting a grid rather than one chosen window is the point: the steady figure is then a
     * reading of the backlog trajectory, and both the settling segment and the steady segment stay
     * in the artifact for a reader who wants either.
     */
    private static String trailingWindows(Run run, long spanNanos) {
        StringBuilder json = new StringBuilder(2048);
        json.append('{');
        boolean first = true;
        for (int lengthMs : TRAILING_WINDOWS_MS) {
            long lengthNanos = Math.min(lengthMs * 1_000_000L, spanNanos);
            long from = run.windowEndNanos - lengthNanos;
            int count = 0;
            long useful = 0;
            for (int i = 0; i < Run.sampleCount(); i++) {
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
                    .append(",\"usefulThroughputPerSecond\":").append(fmt(count == 0 ? 0.0
                            : useful / seconds))
                    .append(",\"p50Nanos\":").append(percentile(sorted, count, 0.50))
                    .append(",\"p95Nanos\":").append(percentile(sorted, count, 0.95))
                    .append(",\"p99Nanos\":").append(percentile(sorted, count, 0.99))
                    .append(",\"maxNanos\":").append(count == 0 ? -1L : sorted[count - 1])
                    .append('}');
        }
        return json.append('}').toString();
    }

    /**
     * Per-function detail — the unit the fairness workloads are read at. A strategy that keeps
     * aggregate throughput identical while starving the least active function is exactly the
     * failure this partition exists to expose.
     */
    private static void appendPerFunction(StringBuilder json, Profile profile, Run run) {
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
                    .append(",\"p50Nanos\":").append(percentile(sorted, n, 0.50))
                    .append(",\"p95Nanos\":").append(percentile(sorted, n, 0.95))
                    .append(",\"p99Nanos\":").append(percentile(sorted, n, 0.99))
                    .append(",\"maxNanos\":").append(n == 0 ? -1L : sorted[n - 1])
                    .append('}');
        }
        json.append('}');
    }

    /** Scatters one function's samples out of the shared arrays into {@link #SCRATCH}. */
    private static synchronized int extractFunctionSamples(int functionIndex) {
        int n = 0;
        for (int i = 0; i < sampleCount; i++) {
            if (LATENCY_FUNCTION[i] == functionIndex) {
                SCRATCH[n++] = LATENCY[i];
            }
        }
        return n;
    }

    // ==================================================================
    // Percentiles, formatting, small helpers
    // ==================================================================

    static long percentile(long[] sorted, int n, double p) {
        if (n == 0) {
            return -1L;
        }
        int index = (int) Math.ceil(p * n) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= n) {
            index = n - 1;
        }
        return sorted[index];
    }

    private static double percentileMillisSorted(double[] sorted, double p) {
        if (sorted.length == 0) {
            return -1.0;
        }
        int index = (int) Math.ceil(p * sorted.length) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= sorted.length) {
            index = sorted.length - 1;
        }
        return sorted[index];
    }

    static double[] sortedMillis(List<Long> nanos) {
        double[] millis = new double[nanos.size()];
        for (int i = 0; i < millis.length; i++) {
            millis[i] = nanos.get(i) / 1e6;
        }
        Arrays.sort(millis);
        return millis;
    }

    private static long percentileOfSamples() {
        int n = Run.sampleCount();
        long[] sorted = Arrays.copyOf(LATENCY, n);
        Arrays.sort(sorted);
        return percentile(sorted, n, 0.99);
    }

    /** Signed percentage difference of {@code value} from the control run's {@code control}. */
    private static double percentDelta(long value, long control) {
        return control == 0 ? 0.0 : (value - control) * 100.0 / control;
    }

    private static long maxDelta(long[] values, long[] control) {
        long max = 0;
        for (int i = 0; i < values.length; i++) {
            max = Math.max(max, Math.abs(values[i] - control[i]));
        }
        return max;
    }

    private static double maxDeltaPercent(long[] values, long[] control) {
        double max = 0.0;
        for (int i = 0; i < values.length; i++) {
            if (control[i] != 0) {
                max = Math.max(max, Math.abs(values[i] - control[i]) * 100.0 / control[i]);
            }
        }
        return max;
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
        System.err.println("[bench] " + message);
        System.err.flush();
    }

    private static void park(long nanos) {
        LockSupport.parkNanos(nanos);
    }

    /**
     * CPU time of every live thread, summed. The process figure ({@code getProcessCpuTime}) is
     * quantised to 10 ms on this platform, which cannot resolve a 10 % budget on the
     * low-throughput workloads; summing {@code getThreadCpuTime} over the live threads gives the
     * same quantity at the thread clock's own resolution (microseconds here). Both are emitted, so
     * the agreement between them is checkable in the artifact rather than asserted.
     */
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
        if (!THREADS.isThreadAllocatedMemorySupported() || !THREADS.isThreadAllocatedMemoryEnabled()) {
            return -1L;
        }
        return THREADS.getTotalThreadAllocatedBytes();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new java.util.LinkedHashMap<>();
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

    private static int[] parseInts(String value) {
        String[] parts = value.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i].trim());
        }
        return values;
    }

    // ==================================================================
    // Workload shapes
    // ==================================================================

    /**
     * One workload. Plain mutable fields rather than a builder: a workload is data, and a benchmark
     * harness that grew a configuration framework would be measuring its own machinery.
     */
    static final class Profile {
        final String name;
        String note = "";
        String[] functions = {"fn-0"};
        int capacity = 2;
        int hotFunctions = 1;
        double hotRatePerSecond = 20.0;
        int sporadicFunctions = 0;
        double sporadicRatePerSecond = 0.0;
        int hotServiceMs = 2;
        int sporadicServiceMs = 2;
        /** Sub-millisecond service tail, so not every service is a whole number of milliseconds. */
        int serviceJitterMicros = 0;
        int payloadBytes = 0;
        int sporadicPayloadBytes = 0;
        int maxPending = 4096;
        int spanMs = SPAN_MS;
        int warmupMs = WARMUP_MS;
        int contractMs = 100;
        double retryFraction = 0.0;
        double syncFraction = 0.0;
        int burstCount = 0;
        /**
         * Workload events are placed as percentages of the measured span, not as absolute
         * milliseconds from the run's start: a span that is three times longer must still contain
         * the burst, the churn and the capacity change at the same point of the arm's own history,
         * or the arms would be compared over different phases of their load.
         */
        int burstAtPercent = 0;
        int capacityChangeAtPercent = 0;
        /**
         * The effective concurrency requested at {@link #capacityChangeAtPercent}, and the point at
         * which the configured one is restored. {@code FunctionCapacityState.setEffectiveConcurrency}
         * clamps to {@code [1, configuredConcurrency]}, so a capacity change this harness can make
         * is a <em>reduction</em> followed by a restore — an increase needs a re-registration, which
         * retires the generation and would confound the measurement rather than exercise it.
         */
        int capacityChangeTo = 0;
        int capacityRestoreAtPercent = 0;
        /** Function churn: retire the second half of the sporadic functions and re-register them. */
        int churnAtPercent = 0;
        /** Stop offering this far into the span; 0 means never. */
        int stopAtPercent = 0;
        int notReadyCount = 0;
        int prefill = 0;

        Profile(String name) {
            this.name = name;
        }

        /** Materialises the function names and validates that the shape is self-consistent. */
        Profile build() {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < hotFunctions; i++) {
                names.add("hot-" + i);
            }
            for (int i = 0; i < sporadicFunctions; i++) {
                names.add("sporadic-" + i);
            }
            // Replacements are appended only for the churn workload: the functions the churn
            // retires are re-registered under these names, so they need array slots (and therefore
            // per-function counters) from the start.
            if (churnAtPercent > 0) {
                for (int i = 0; i < sporadicFunctions / 2; i++) {
                    names.add("sporadic-r" + i);
                }
            }
            if (names.isEmpty()) {
                names.add("fn-0");
            }
            functions = names.toArray(new String[0]);
            if (notReadyCount > functions.length) {
                throw new IllegalStateException(name + ": notReadyCount exceeds the function count");
            }
            return this;
        }

        double totalOfferedPerSecond() {
            return hotFunctions * hotRatePerSecond + sporadicFunctions * sporadicRatePerSecond;
        }

        int serviceMillis(String function) {
            return function.startsWith("sporadic") ? sporadicServiceMs : hotServiceMs;
        }

        int payloadBytes(int functionIndex) {
            return functionIndex < hotFunctions ? payloadBytes : sporadicPayloadBytes;
        }

        long serviceFractionNanos(String function) {
            if (serviceJitterMicros == 0) {
                return 0L;
            }
            return Math.floorMod(function.hashCode(), serviceJitterMicros) * 1_000L;
        }

        /** The last functions of the array are the readiness-blocked ones, so few stay ready. */
        Set<String> notReadyFunctions() {
            if (notReadyCount == 0) {
                return Set.of();
            }
            Set<String> notReady = new HashSet<>();
            for (int i = functions.length - notReadyCount; i < functions.length; i++) {
                notReady.add(functions[i]);
            }
            return notReady;
        }

        List<String> churnedFunctions() {
            if (churnAtPercent == 0) {
                return List.of();
            }
            List<String> churned = new ArrayList<>();
            for (int i = 0; i < sporadicFunctions / 2; i++) {
                churned.add("sporadic-" + i);
            }
            return churned;
        }

        List<String> replacementFunctions() {
            if (churnAtPercent == 0) {
                return List.of();
            }
            List<String> replacements = new ArrayList<>();
            for (int i = 0; i < sporadicFunctions / 2; i++) {
                replacements.add("sporadic-r" + i);
            }
            return replacements;
        }
    }

    /** The corpus: one factory per workload in spec §11. */
    static final class Profiles {

        static List<Profile> select(String selection) {
            List<Profile> all = all();
            if (selection.equals("all")) {
                return all;
            }
            List<Profile> chosen = new ArrayList<>();
            for (String wanted : selection.split(",")) {
                for (Profile profile : all) {
                    if (profile.name.equals(wanted)) {
                        chosen.add(profile);
                    }
                }
            }
            if (chosen.isEmpty()) {
                throw new IllegalArgumentException("no workload matched: " + selection);
            }
            return chosen;
        }

        static List<Profile> all() {
            List<Profile> profiles = new ArrayList<>(List.of(
                    lowLoad(),
                    saturated(),
                    hotPlusSporadic(50),
                    hotPlusSporadic(500),
                    heterogeneousAndBurst(),
                    headOfLineBlocking(),
                    mixedKindAndRetry(),
                    churnAndDrain(),
                    capacityChangeUnderLoad(),
                    switchUnderLoad(),
                    unqueued(),
                    queued()));
            for (Profile profile : profiles) {
                profile.build();
            }
            return profiles;
        }

        /** §11.1 — una funzione, servizio breve, basso carico: costo del percorso normale. */
        static Profile lowLoad() {
            Profile p = new Profile("low-load");
            p.note = "una funzione, servizio breve, basso carico: costo del percorso normale";
            p.hotFunctions = 1;
            p.hotRatePerSecond = 20.0;
            p.capacity = 2;
            p.hotServiceMs = 2;
            p.contractMs = 100;
            return p;
        }

        /** §11.2 — una funzione satura: capacità utile, rifiuto rapido, contesa produttori/scheduler. */
        static Profile saturated() {
            Profile p = new Profile("saturated");
            p.note = "una funzione satura: capacita' utile, rifiuto rapido, contesa produttori/scheduler";
            p.hotFunctions = 1;
            p.hotRatePerSecond = 2000.0;
            p.capacity = 1;
            p.hotServiceMs = 2;
            p.maxPending = 512;
            p.contractMs = 60;
            return p;
        }

        /** §11.3 — una funzione molto attiva e N sporadiche: fairness e latenza delle meno attive. */
        static Profile hotPlusSporadic(int sporadicCount) {
            Profile p = new Profile("hot-plus-" + sporadicCount + "-sporadic");
            p.note = "una funzione molto attiva e " + sporadicCount
                    + " funzioni sporadiche: fairness e latenza delle meno attive";
            p.hotFunctions = 1;
            p.hotRatePerSecond = 1500.0;
            p.sporadicFunctions = sporadicCount;
            p.sporadicRatePerSecond = 2.0;
            p.capacity = 4;
            p.hotServiceMs = 2;
            p.sporadicServiceMs = 2;
            p.maxPending = 2048;
            p.contractMs = 200;
            return p;
        }

        /** §11.4 — durate eterogenee e burst: avvii, occupazione e goodput entro deadline. */
        static Profile heterogeneousAndBurst() {
            Profile p = new Profile("heterogeneous-burst");
            p.note = "durate eterogenee e burst: distinguere avvii, occupazione e goodput entro deadline";
            p.hotFunctions = 3;
            p.hotRatePerSecond = 150.0;
            p.capacity = 4;
            p.hotServiceMs = 3;
            p.serviceJitterMicros = 900;
            p.maxPending = 2048;
            p.contractMs = 300;
            p.burstCount = 1500;
            p.burstAtPercent = 12;
            return p;
        }

        /** §11.5 — backlog senza readiness con poche funzioni pronte: head-of-line blocking. */
        static Profile headOfLineBlocking() {
            Profile p = new Profile("head-of-line-blocking");
            p.note = "backlog di funzioni senza readiness con poche funzioni pronte: head-of-line blocking";
            p.hotFunctions = 6;
            p.hotRatePerSecond = 120.0;
            p.capacity = 2;
            p.hotServiceMs = 2;
            p.maxPending = 4096;
            p.contractMs = 200;
            p.notReadyCount = 5;
            return p;
        }

        /**
         * §11.6 — carico misto SYNC/ASYNC e retry: isolamento e condivisione. The offload classes
         * the section also names are a control-plane lifecycle path and are not reachable from
         * this harness; see RESULTS.md.
         */
        static Profile mixedKindAndRetry() {
            Profile p = new Profile("mixed-kind-retry");
            p.note = "carico misto SYNC/ASYNC e retry: isolamento e condivisione (classi offload non "
                    + "raggiungibili dall'harness controllato: vedi RESULTS.md)";
            p.hotFunctions = 2;
            p.hotRatePerSecond = 400.0;
            p.capacity = 4;
            p.hotServiceMs = 3;
            p.maxPending = 2048;
            p.contractMs = 120;
            p.syncFraction = 0.5;
            p.retryFraction = 0.2;
            return p;
        }

        /** §11.7 — payload diversi, molte funzioni, churn e stop del traffico: memoria, indici, drain. */
        static Profile churnAndDrain() {
            Profile p = new Profile("churn-drain");
            p.note = "payload di dimensioni diverse, molte funzioni, churn e stop del traffico: "
                    + "memoria, indici e drain";
            p.hotFunctions = 4;
            p.sporadicFunctions = 16;
            p.hotRatePerSecond = 200.0;
            p.sporadicRatePerSecond = 20.0;
            p.capacity = 4;
            p.hotServiceMs = 2;
            p.sporadicServiceMs = 2;
            p.payloadBytes = 4096;
            p.sporadicPayloadBytes = 64;
            p.maxPending = 4096;
            p.contractMs = 200;
            p.churnAtPercent = 25;
            p.stopAtPercent = 60;
            return p;
        }

        /**
         * §11.8 — cambi di capacità durante il carico: progressi e correttezza del protocollo.
         *
         * <p>The change is a <em>reduction</em> (2 → 1 per function) followed by a restore, because
         * that is the direction the registry's API can actually make: an increase is clamped to the
         * configured value and silently does nothing, which is exactly the defect this workload had
         * while this task was being measured — it asked for 8 against a configured 2 and produced a
         * workload that never changed capacity at all. The read-back in {@code Driver.events} is
         * what caught that and is kept so it cannot recur unnoticed.
         */
        static Profile capacityChangeUnderLoad() {
            Profile p = new Profile("capacity-change");
            p.note = "cambi di capacita' durante il carico (riduzione effettiva 2->1 e ripristino): "
                    + "progressi e correttezza del protocollo";
            p.hotFunctions = 2;
            p.hotRatePerSecond = 600.0;
            p.capacity = 2;
            p.hotServiceMs = 3;
            p.maxPending = 2048;
            p.contractMs = 150;
            p.capacityChangeAtPercent = 25;
            p.capacityChangeTo = 1;
            p.capacityRestoreAtPercent = 60;
            return p;
        }

        /**
         * §11.9 — cambi manuali di scheduler in entrambe le direzioni durante carico continuo,
         * burst e backlog con retry e readiness bloccata: continuità delle invocazioni, pausa del
         * dispatch, latenza, memoria temporanea e cleanup dopo cambi ripetuti.
         */
        static Profile switchUnderLoad() {
            Profile p = new Profile("switch-under-load");
            p.note = "cambi manuali di scheduler durante carico continuo, burst e backlog con retry/"
                    + "readiness bloccata: continuita', pausa del dispatch, latenza, cleanup";
            p.hotFunctions = 1;
            p.sporadicFunctions = 8;
            p.hotRatePerSecond = 800.0;
            p.sporadicRatePerSecond = 30.0;
            p.capacity = 2;
            p.hotServiceMs = 4;
            p.sporadicServiceMs = 4;
            p.maxPending = 4096;
            p.contractMs = 250;
            p.retryFraction = 0.1;
            p.burstCount = 1000;
            p.burstAtPercent = 12;
            p.notReadyCount = 4;
            return p;
        }

        /** §11.10, first half — modalità senza coda: the backlog never forms. */
        static Profile unqueued() {
            Profile p = new Profile("unqueued");
            p.note = "modalita' senza coda a carico supportato (approssimazione a livello di motore: "
                    + "il percorso di ammissione diretto vive nel control-plane)";
            p.hotFunctions = 1;
            p.hotRatePerSecond = 300.0;
            p.capacity = 2;
            p.hotServiceMs = 2;
            p.contractMs = 100;
            return p;
        }

        /**
         * §11.10, second half — the queued profile at the same offered rate and a supported load,
         * with the capacity reduced so a queue actually forms instead of being bypassed.
         */
        static Profile queued() {
            Profile p = new Profile("queued");
            p.note = "modalita' queued a carico supportato: stesso rate offerto di 'unqueued', "
                    + "capacita' ridotta al punto in cui la coda esiste: costo dell'ownership comune";
            p.hotFunctions = 1;
            p.hotRatePerSecond = 300.0;
            p.capacity = 1;
            p.hotServiceMs = 2;
            p.serviceJitterMicros = 1500;
            p.contractMs = 100;
            return p;
        }

        /** One backlog size of the pause sweep: a saturated function with N tickets waiting. */
        static Profile switchPause(int backlog) {
            Profile p = new Profile("switch-pause-backlog-" + backlog);
            p.note = "pausa del cambio contro N ticket pendenti (budgets.json switchBacklogSizes)";
            p.hotFunctions = 1;
            p.hotRatePerSecond = 0.0;
            p.capacity = 1;
            p.hotServiceMs = 2;
            p.maxPending = Math.max(1024, backlog + 512);
            // The pending work must survive long enough to be the backlog the switch rebuilds.
            p.contractMs = 300_000;
            p.spanMs = 600;
            p.warmupMs = 300;
            p.prefill = backlog;
            return p.build();
        }

        /** The 1000-switch run: a continuously loaded engine with a standing backlog. */
        static Profile returnToBaseline() {
            Profile p = new Profile("return-to-baseline");
            p.note = "1000 cambi manuali sotto carico continuo (budgets.json switchesInSoak)";
            p.hotFunctions = 1;
            p.sporadicFunctions = 4;
            p.hotRatePerSecond = 400.0;
            p.sporadicRatePerSecond = 20.0;
            p.capacity = 2;
            p.hotServiceMs = 3;
            p.sporadicServiceMs = 3;
            p.maxPending = 4096;
            // Above the standing queue wait by design: this phase measures the switch and its
            // cleanup, not the contract, and a backlog that expires away is not a standing backlog.
            p.contractMs = 30_000;
            p.prefill = 400;
            // No measured window: the run is bounded by the caller's 1000 switches, not by a clock.
            p.spanMs = 0;
            p.warmupMs = 0;
            return p.build();
        }
    }

    // ==================================================================
    // budgets.json — read, never restated
    // ==================================================================

    /**
     * The frozen thresholds, read from the artifact rather than copied into the harness. A harness
     * that restated them could drift from the very file it claims to be checking; one that reads
     * them cannot. Every value read here is echoed back in the header line, so a result can be
     * traced to the thresholds it was compared against.
     */
    record Budgets(int repetitions, int[] switchBacklogSizes, long maxSwitchPauseMs,
                   long maxSwitchPauseP99Ms, long maxSwitchPreparationMs, int maxLiveStrategyIndexes,
                   int switchesInSoak) {

        static Budgets read(Path file) throws Exception {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return new Budgets(intAfter(text, "\"repetitions\""),
                    intsAfter(text, "\"switchBacklogSizes\""),
                    intAfter(text, "\"maxSwitchPauseMs\""),
                    intAfter(text, "\"maxSwitchPauseP99Ms\""),
                    intAfter(text, "\"maxSwitchPreparationMs\""),
                    intAfter(text, "\"maxLiveStrategyIndexes\""),
                    intAfter(text, "\"switchesInSoak\""));
        }

        static int intAfter(String text, String key) {
            int at = text.indexOf(key);
            if (at < 0) {
                throw new IllegalArgumentException("budgets.json has no " + key);
            }
            int cursor = text.indexOf(':', at) + 1;
            while (cursor < text.length() && !Character.isDigit(text.charAt(cursor))) {
                cursor++;
            }
            int start = cursor;
            while (cursor < text.length() && Character.isDigit(text.charAt(cursor))) {
                cursor++;
            }
            return Integer.parseInt(text.substring(start, cursor));
        }

        static int[] intsAfter(String text, String key) {
            int at = text.indexOf(key);
            if (at < 0) {
                throw new IllegalArgumentException("budgets.json has no " + key);
            }
            int open = text.indexOf('[', at);
            int close = text.indexOf(']', open);
            String[] parts = text.substring(open + 1, close).split(",");
            int[] values = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                values[i] = Integer.parseInt(parts[i].trim());
            }
            return values;
        }

        String asJson() {
            return "{\"repetitions\":" + repetitions
                    + ",\"switchBacklogSizes\":" + Arrays.toString(switchBacklogSizes).replace(" ", "")
                    + ",\"maxSwitchPauseMs\":" + maxSwitchPauseMs
                    + ",\"maxSwitchPauseP99Ms\":" + maxSwitchPauseP99Ms
                    + ",\"maxSwitchPreparationMs\":" + maxSwitchPreparationMs
                    + ",\"maxLiveStrategyIndexes\":" + maxLiveStrategyIndexes
                    + ",\"switchesInSoak\":" + switchesInSoak + "}";
        }
    }
}
