package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadDiagnostics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;

public class SyncQueueService implements SyncQueueGateway {
    public static final int POLL_READY_MATCHING_SCAN_LIMIT = 64;
    private static final String FUNCTION_REMOVED = "FUNCTION_REMOVED";

    private final SyncQueueConfigSource configSource;
    private final ExecutionStore executionStore;
    private final WaitEstimator estimator;
    private final SyncQueueMetrics metrics;
    private final Clock clock;
    private final Deque<SyncQueueItem> queue;
    private final int maxDepth;
    private final Object workSignal = new Object();
    /**
     * Monotonic notification sequence guarded by {@link #workSignal}. Every event that can
     * make a queued item newly dispatchable - new work, a released slot, a capacity increase,
     * a (re)registration, a removal drain - increments it and notifies the monitor. A waiter
     * records the sequence before scanning and re-checks it under the same monitor before
     * parking, so no signal is lost between the state check and the wait (the classic
     * missed-wakeup race): if the sequence moved, the waiter re-scans instead of sleeping.
     */
    private long wakeSeq;
    private final SyncQueueAdmissionController admissionController;
    /**
     * Profondita' per funzione, mantenuta ai punti di mutazione della coda.
     *
     * <p>Prima era una scansione O(depth) sotto il monitor della coda, e il metrics source la
     * chiama UNA VOLTA PER FUNZIONE a ogni scrape: con 51 funzioni e profondita' 200 un enqueue
     * concorrente passava da 288 ns a 8.739 ns, 30 volte tanto, perche' l'ammissione aspettava
     * un monitor preso da una lettura di metriche
     * (docs/experiments/control-plane-tuning-2026-09/RISULTATI.md).
     *
     * <p>Le scritture restano dentro i blocchi {@code synchronized (queue)} gia' esistenti, cosi'
     * la relazione fra chiusura, offer e contatori resta atomica; le letture non prendono il
     * monitor, ed e' esattamente quello il guadagno.
     */
    private final ConcurrentHashMap<String, Integer> depthByFunction = new ConcurrentHashMap<>();

    private final Set<String> removedFunctions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, LifecycleLock> lifecycleLocks = new ConcurrentHashMap<>();
    private final FunctionCapacityRegistry capacityRegistry;
    private final WorkloadDiagnostics diagnostics;

    public SyncQueueService(SyncQueueProperties props,
                            ExecutionStore executionStore,
                            SyncQueueMetrics metrics,
                            SyncQueueConfigSource configSource) {
        this(props,
                executionStore,
                new WaitEstimator(props.throughputWindow(), props.perFunctionMinSamples()),
                metrics,
                Clock.systemUTC(),
                configSource,
                new FunctionCapacityRegistry(),
                null);
    }

    public SyncQueueService(SyncQueueProperties props,
                            ExecutionStore executionStore,
                            SyncQueueMetrics metrics,
                            SyncQueueConfigSource configSource,
                            FunctionCapacityRegistry capacityRegistry,
                            WorkloadDiagnostics diagnostics) {
        this(props, executionStore,
                new WaitEstimator(props.throughputWindow(), props.perFunctionMinSamples()),
                metrics, Clock.systemUTC(), configSource, capacityRegistry, diagnostics);
    }

    SyncQueueService(SyncQueueProperties props,
                     ExecutionStore executionStore,
                     WaitEstimator estimator,
                     SyncQueueMetrics metrics,
                     Clock clock,
                     SyncQueueConfigSource configSource) {
        this(props, executionStore, estimator, metrics, clock, configSource,
                new FunctionCapacityRegistry(), null);
    }

    // Spring wiring: eight distinct collaborators, each read on its own. The shorter
    // constructors above are the ones tests use; this one is the full graph.
    @SuppressWarnings("java:S107")
    public SyncQueueService(SyncQueueProperties props,
                            ExecutionStore executionStore,
                            WaitEstimator estimator,
                            SyncQueueMetrics metrics,
                            Clock clock,
                            SyncQueueConfigSource configSource,
                            FunctionCapacityRegistry capacityRegistry,
                            WorkloadDiagnostics diagnostics) {
        this.configSource = configSource;
        this.executionStore = executionStore;
        this.estimator = estimator;
        this.metrics = metrics;
        this.clock = clock;
        this.queue = new ArrayDeque<>(props.maxDepth());
        this.maxDepth = props.maxDepth();
        this.admissionController = new SyncQueueAdmissionController(configSource, props.maxDepth(), estimator);
        this.capacityRegistry = capacityRegistry;
        this.diagnostics = diagnostics;
        // Capacity opens are announced by the shared registry (the concurrency governor raises
        // the effective limit through it, outside the enqueuer's release path). This queue is
        // the only subscriber in the sync profile; a registry with no listener is unchanged.
        if (capacityRegistry != null) {
            capacityRegistry.addCapacityListener(this::onCapacityOpened);
        }
    }

    private void onCapacityOpened(String functionName) {
        signalIfQueueHasWork();
    }

    private LifecycleLock acquireLifecycleLock(String functionName) {
        synchronized (lifecycleLocks) {
            LifecycleLock lock = lifecycleLocks.computeIfAbsent(functionName, ignored -> new LifecycleLock());
            lock.users++;
            return lock;
        }
    }

    private void releaseLifecycleLock(String functionName, LifecycleLock lock) {
        boolean unused;
        synchronized (lifecycleLocks) {
            unused = --lock.users == 0;
        }
        if (unused) {
            cleanupLifecycleLock(functionName, lock);
        }
    }

    public void onDispatchSlotReleased(String functionName) {
        // Fires on every completed execution (a slot was just released by the enqueuer, so the
        // state change is already visible). A queued item for a function that was at its limit
        // may now be dispatchable - wake a parked scheduler. Gated on a non-empty queue so an
        // idle worker is not woken by completions of in-flight work it cannot use.
        signalIfQueueHasWork();
        // Only a drained generation needs cleaning up,
        // and that is exactly when the registry stops carrying the function.
        if (capacityRegistry.hasGeneration(functionName)) return;
        LifecycleLock lock;
        synchronized (lifecycleLocks) {
            lock = lifecycleLocks.get(functionName);
            if (lock == null || lock.users != 0) return;
        }
        cleanupLifecycleLock(functionName, lock);
    }

    private void cleanupLifecycleLock(String functionName, LifecycleLock lock) {
        if (capacityRegistry.hasGeneration(functionName)) return;
        synchronized (lifecycleLocks) {
            if (lock.users == 0) lifecycleLocks.remove(functionName, lock);
        }
    }

    int lifecycleLockCount() {
        return lifecycleLocks.size();
    }

    public boolean enabled() {
        return configSource.syncQueueEnabled();
    }

    public int retryAfterSeconds() {
        return configSource.syncQueueRetryAfterSeconds();
    }

    @Override
    public void enqueueOrThrow(InvocationTask task) {
        if (removedFunctions.contains(task.functionName())) {
            markFunctionRemoved(task.functionName(), new SyncQueueItem(task, clock.instant()), false);
            throw new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, configSource.syncQueueRetryAfterSeconds());
        }
        Instant now = clock.instant();
        long offerStarted = System.nanoTime();
        SyncQueueAdmissionResult decision = admissionController.evaluate(task.functionName(), queuedItems(), now);
        if (!decision.accepted()) {
            metrics.rejected(task.functionName());
            throw new SyncQueueRejectedException(decision.reason(), configSource.syncQueueRetryAfterSeconds());
        }
        synchronized (queue) {
            if (removedFunctions.contains(task.functionName())) {
                markFunctionRemoved(task.functionName(), new SyncQueueItem(task, now), false);
                throw new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, configSource.syncQueueRetryAfterSeconds());
            }
            if (queue.size() >= maxDepth) {
                metrics.rejected(task.functionName());
                throw new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, configSource.syncQueueRetryAfterSeconds());
            }
            SyncQueueItem queued = new SyncQueueItem(task, now);
            queue.addLast(queued);
            countAdded(queued);
            metrics.registerFunction(task.functionName());
            metrics.admitted(task.functionName());
        }
        if (diagnostics != null) {
            diagnostics.recordQueueOfferDuration(task.functionName(), System.nanoTime() - offerStarted);
        }
        // New work: wake a scheduler parked on an empty queue (unconditional - a worker parked
        // because the queue was empty must always see the new item). Unnecessary wakeups for an
        // already-running worker are harmless: it re-scans and parks again.
        signalWakeup();
    }

    /**
     * The current notification sequence. A scheduler records it before scanning the queue and
     * passes it to {@link #awaitWakeup}; if any relevant event fires while it scans, the
     * sequence moves and the wait returns immediately instead of sleeping through the change.
     */
    public long wakeupEpoch() {
        synchronized (workSignal) {
            return wakeSeq;
        }
    }

    /**
     * Parks until either the notification sequence advances past {@code observedEpoch} (new
     * work, a capacity release/increase, a registration or a removal made something newly
     * dispatchable) or {@code timeoutMs} elapses. The timeout is a safety bound - for queue-item
     * expiry checks, scan-window rotation and shutdown detection - not the dispatch-latency
     * budget: real events wake the worker through the monitor, so this is not a poll. An
     * interrupt (e.g. scheduler stop) returns immediately with the flag preserved.
     */
    public void awaitWakeup(long timeoutMs, long observedEpoch) {
        if (timeoutMs <= 0) {
            return;
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (workSignal) {
            while (wakeSeq == observedEpoch) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(workSignal, remainingNanos);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Bumps the notification sequence and wakes any parked scheduler thread.
     */
    private void signalWakeup() {
        synchronized (workSignal) {
            wakeSeq++;
            workSignal.notifyAll();
        }
    }

    /**
     * Signals that capacity may have opened for a queued function. When the queue is empty no
     * queued item can benefit, so the notification is skipped - an idle worker stays dormant
     * rather than waking on every completion of in-flight work.
     */
    private void signalIfQueueHasWork() {
        if (queuedItems() == 0) {
            return;
        }
        signalWakeup();
    }

    public int queuedItems() {
        synchronized (queue) {
            return queue.size();
        }
    }

    /**
     * Profondita' della coda per una funzione. Lettura senza monitor: e' una metrica, e farle
     * bloccare l'ammissione per essere esatta al nanosecondo sarebbe un pessimo scambio.
     */
    public int queuedItems(String functionName) {
        return depthByFunction.getOrDefault(functionName, 0);
    }

    /** Da chiamare SOLO sotto {@code synchronized (queue)}, insieme alla mutazione del deque. */
    private void countAdded(SyncQueueItem item) {
        depthByFunction.merge(item.task().functionName(), 1, Integer::sum);
    }

    /** Da chiamare SOLO sotto {@code synchronized (queue)}, insieme alla mutazione del deque. */
    private void countRemoved(SyncQueueItem item) {
        depthByFunction.computeIfPresent(item.task().functionName(),
                (name, count) -> count <= 1 ? null : count - 1);
    }

    public SyncQueueItem peekReady(Instant now) {
        while (true) {
            SyncQueueItem timedOut = null;
            synchronized (queue) {
                SyncQueueItem item = queue.peekFirst();
                if (item == null) {
                    return null;
                }
                if (!isTimedOut(item, now)) {
                    return item;
                }
                queue.pollFirst();
                countRemoved(item);
                timedOut = item;
            }
            timeout(timedOut);
        }
    }

    public SyncQueueItem pollReady(Instant now) {
        long pollStarted = System.nanoTime();
        SyncQueueItem item;
        synchronized (queue) {
            item = queue.pollFirst();
            if (item != null) {
                countRemoved(item);
            }
        }
        if (item != null) {
            recordDequeued(item, now);
            recordQueuePoll(item.task().functionName(), pollStarted);
        }
        return item;
    }

    public SyncQueueItem pollReadyMatching(Instant now, Predicate<InvocationTask> selector) {
        SyncQueueItem item = findReadyMatching(now, selector);
        if (item != null && removeReady(item, now)) {
            return item;
        }
        return null;
    }

    public SyncQueueItem findReadyMatching(Instant now, Predicate<InvocationTask> selector) {
        List<SyncQueueItem> timedOut = new ArrayList<>();
        SyncQueueItem selected = null;
        synchronized (queue) {
            int remaining = Math.min(queue.size(), POLL_READY_MATCHING_SCAN_LIMIT);
            Iterator<SyncQueueItem> iterator = queue.iterator();
            while (remaining-- > 0 && iterator.hasNext()) {
                SyncQueueItem item = iterator.next();
                if (isTimedOut(item, now)) {
                    iterator.remove();
                    countRemoved(item);
                    timedOut.add(item);
                } else if (selector.test(item.task())) {
                    selected = item;
                    break;
                }
            }
        }
        timedOut.forEach(this::timeout);
        return selected;
    }

    public boolean removeReady(SyncQueueItem item, Instant now) {
        long pollStarted = System.nanoTime();
        boolean removed;
        synchronized (queue) {
            removed = queue.remove(item);
            if (removed) {
                countRemoved(item);
            }
        }
        if (!removed) {
            return false;
        }
        recordDequeued(item, now);
        recordQueuePoll(item.task().functionName(), pollStarted);
        return true;
    }

    public boolean rotateReadyHead(Instant now) {
        SyncQueueItem timedOut = null;
        synchronized (queue) {
            SyncQueueItem item = queue.pollFirst();
            if (item == null) {
                return false;
            }
            if (isTimedOut(item, now)) {
                countRemoved(item);
                timedOut = item;
            } else {
                // Testa -> coda: la profondita' non cambia, quindi il contatore neppure.
                queue.addLast(item);
            }
        }
        if (timedOut != null) {
            timeout(timedOut);
        }
        return true;
    }

    public boolean rotateReadyItem(SyncQueueItem item, Instant now) {
        SyncQueueItem timedOut = null;
        boolean rotated = false;
        synchronized (queue) {
            if (!queue.remove(item)) {
                return false;
            }
            if (isTimedOut(item, now)) {
                countRemoved(item);
                timedOut = item;
            } else {
                queue.addLast(item);
                rotated = true;
            }
        }
        if (timedOut != null) {
            timeout(timedOut);
        }
        return rotated;
    }

    /**
     * Ruota la finestra di scansione un elemento alla volta, prendendo il monitor a ogni
     * passo. Sembra sprecato — 1 + fino a 64 prese invece di una — e accorparle in un'unica
     * sezione critica rende la rotazione 7,5 volte piu' veloce in isolamento (1105 -> 148 ns).
     *
     * <p>Misurato, pero', quell'accorpamento affama l'ammissione: un enqueue concorrente passa
     * da ~1 us a 4-20 us, perche' invece di infilarsi fra due sezioni critiche corte deve
     * aspettare che tutti i 64 elementi siano stati ruotati. La rotazione e' lavoro di
     * manutenzione, l'ammissione e' il percorso del chiamante: le prese brevi sono la scelta
     * giusta, non una svista. Vedi docs/experiments/control-plane-tuning-2026-09/RISULTATI.md.
     */
    public boolean rotateReadyScanWindow(Instant now) {
        boolean changed = false;
        int remaining = Math.min(queuedItems(), POLL_READY_MATCHING_SCAN_LIMIT);
        while (remaining-- > 0) {
            changed |= rotateReadyHead(now);
        }
        return changed;
    }

    public void recordDispatched(String functionName, Instant now) {
        estimator.recordDispatch(functionName, now);
    }

    public void removeFunctionState(String functionName) {
        LifecycleLock lifecycleLock = acquireLifecycleLock(functionName);
        try {
            synchronized (lifecycleLock) {
            removedFunctions.add(functionName);
            drainRemovedFunction(functionName);
            estimator.removeFunctionState(functionName);
            metrics.removeFunctionState(functionName);
            capacityRegistry.remove(functionName);
            }
        } finally {
            releaseLifecycleLock(functionName, lifecycleLock);
        }
        // Draining the removed function's queued items can expose dispatchable work that was
        // behind them in the scan window; a parked scheduler should re-examine the queue.
        signalIfQueueHasWork();
    }

    public void registerFunction(String functionName, int concurrency) {
        LifecycleLock lifecycleLock = acquireLifecycleLock(functionName);
        try {
            synchronized (lifecycleLock) {
            // Mirror of removeFunctionState, which raises the flag first: enqueueOrThrow reads
            // removedFunctions without this lock, so anything that runs while the flag is still
            // up terminates a live invocation as FUNCTION_REMOVED. Clear it before the register,
            // which takes the registry entry lock and can reactivate a draining generation. A
            // task queued in between simply waits for a slot.
            removedFunctions.remove(functionName);
            capacityRegistry.register(functionName, concurrency);
            metrics.registerFunction(functionName);
            }
        } finally {
            releaseLifecycleLock(functionName, lifecycleLock);
        }
        // A registration gives the function capacity (a queued item admitted while the function
        // was unregistered, or re-registered under load, may now be dispatchable).
        signalIfQueueHasWork();
    }

    private static final class LifecycleLock {
        private int users;
    }

    private void drainRemovedFunction(String functionName) {
        List<SyncQueueItem> removed = new ArrayList<>();
        synchronized (queue) {
            Iterator<SyncQueueItem> iterator = queue.iterator();
            while (iterator.hasNext()) {
                SyncQueueItem item = iterator.next();
                if (item.task().functionName().equals(functionName)) {
                    iterator.remove();
                    countRemoved(item);
                    removed.add(item);
                }
            }
        }
        removed.forEach(item -> markFunctionRemoved(functionName, item, true));
    }

    private void markFunctionRemoved(String functionName, SyncQueueItem item, boolean wasQueued) {
        ExecutionRecord executionRecord = executionStore.getOrNull(item.task().executionId());
        if (executionRecord == null) {
            if (wasQueued) {
                metrics.dequeued(functionName);
            }
            return;
        }
        InvocationResult result = InvocationResult.error(
                FUNCTION_REMOVED,
                "Function '%s' was removed before queued execution could run".formatted(functionName)
        );
        synchronized (executionRecord) {
            if (!executionRecord.isTerminal()) {
                executionRecord.markError(result.error());
                executionRecord.completion().complete(result);
            }
        }
        executionStore.settle(executionRecord);
        if (wasQueued) {
            metrics.dequeued(functionName);
        }
    }

    private boolean isTimedOut(SyncQueueItem item, Instant now) {
        return item.enqueuedAt().plus(configSource.syncQueueMaxQueueWait()).isBefore(now);
    }

    private void timeout(SyncQueueItem item) {
        ExecutionRecord executionRecord = executionStore.getOrNull(item.task().executionId());
        if (executionRecord != null) {
            // Guard: completeExecution publishes the future outside the record monitor; only complete if not already finalized.
            synchronized (executionRecord) {
                if (!executionRecord.isTerminal()) {
                    executionRecord.markTimeout();
                    executionRecord.completion().complete(InvocationResult.error("QUEUE_TIMEOUT", "Queue wait exceeded"));
                }
            }
            executionStore.settle(executionRecord);
        }
        metrics.dequeued(item.task().functionName());
        metrics.timedOut(item.task().functionName());
    }

    private void recordDequeued(SyncQueueItem item, Instant now) {
        metrics.dequeued(item.task().functionName());
        long waitMillis = Duration.between(item.enqueuedAt(), now).toMillis();
        metrics.recordWait(item.task().functionName(), waitMillis);
    }

    private void recordQueuePoll(String functionName, long started) {
        if (diagnostics != null) {
            diagnostics.recordQueuePollDuration(functionName, System.nanoTime() - started);
        }
    }
}
