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
    private final SyncQueueAdmissionController admissionController;
    private final Set<String> removedFunctions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> lifecycleLocks = new ConcurrentHashMap<>();
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
            queue.addLast(new SyncQueueItem(task, now));
            metrics.registerFunction(task.functionName());
            metrics.admitted(task.functionName());
        }
        if (diagnostics != null) {
            diagnostics.recordQueueOfferDuration(task.functionName(), System.nanoTime() - offerStarted);
        }
        synchronized (workSignal) {
            workSignal.notifyAll();
        }
    }

    /**
     * Waits for new work when the queue is empty to avoid busy polling.
     */
    public void awaitWork(long timeoutMs) {
        if (timeoutMs <= 0 || queuedItems() > 0) {
            return;
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (workSignal) {
            while (queuedItems() == 0) {
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

    public int queuedItems() {
        synchronized (queue) {
            return queue.size();
        }
    }

    public int queuedItems(String functionName) {
        synchronized (queue) {
            int count = 0;
            for (SyncQueueItem item : queue) {
                if (item.task().functionName().equals(functionName)) count++;
            }
            return count;
        }
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
                timedOut = item;
            } else {
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
        synchronized (lifecycleLocks.computeIfAbsent(functionName, ignored -> new Object())) {
            removedFunctions.add(functionName);
            drainRemovedFunction(functionName);
            estimator.removeFunctionState(functionName);
            metrics.removeFunctionState(functionName);
            capacityRegistry.remove(functionName);
        }
    }

    public void registerFunction(String functionName) {
        registerFunction(functionName, 1);
    }

    public void registerFunction(String functionName, int concurrency) {
        synchronized (lifecycleLocks.computeIfAbsent(functionName, ignored -> new Object())) {
            capacityRegistry.register(functionName, concurrency);
            removedFunctions.remove(functionName);
            metrics.registerFunction(functionName);
        }
    }

    private void drainRemovedFunction(String functionName) {
        List<SyncQueueItem> removed = new ArrayList<>();
        synchronized (queue) {
            Iterator<SyncQueueItem> iterator = queue.iterator();
            while (iterator.hasNext()) {
                SyncQueueItem item = iterator.next();
                if (item.task().functionName().equals(functionName)) {
                    iterator.remove();
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
