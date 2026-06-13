package it.unimib.datai.nanofaas.controlplane.sync;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;

@Component
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

    @Autowired
    public SyncQueueService(SyncQueueProperties props,
                            ExecutionStore executionStore,
                            SyncQueueMetrics metrics,
                            SyncQueueConfigSource configSource) {
        this(props,
                executionStore,
                new WaitEstimator(props.throughputWindow(), props.perFunctionMinSamples()),
                metrics,
                Clock.systemUTC(),
                configSource);
    }

    SyncQueueService(SyncQueueProperties props,
                     ExecutionStore executionStore,
                     WaitEstimator estimator,
                     SyncQueueMetrics metrics,
                     Clock clock,
                     SyncQueueConfigSource configSource) {
        this.configSource = configSource;
        this.executionStore = executionStore;
        this.estimator = estimator;
        this.metrics = metrics;
        this.clock = clock;
        this.queue = new ArrayDeque<>(props.maxDepth());
        this.maxDepth = props.maxDepth();
        this.admissionController = new SyncQueueAdmissionController(configSource, props.maxDepth(), estimator);
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
        synchronized (workSignal) {
            workSignal.notify();
        }
    }

    /**
     * Waits for new work when the queue is empty to avoid busy polling.
     */
    public void awaitWork(long timeoutMs) {
        if (timeoutMs <= 0 || queuedItems() > 0) {
            return;
        }
        synchronized (workSignal) {
            if (queuedItems() == 0) {
                try {
                    workSignal.wait(timeoutMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public int queuedItems() {
        synchronized (queue) {
            return queue.size();
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
        SyncQueueItem item;
        synchronized (queue) {
            item = queue.pollFirst();
        }
        if (item != null) {
            recordDequeued(item, now);
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
                    continue;
                }
                if (selector.test(item.task())) {
                    selected = item;
                    break;
                }
            }
        }
        timedOut.forEach(this::timeout);
        return selected;
    }

    public boolean removeReady(SyncQueueItem item, Instant now) {
        boolean removed;
        synchronized (queue) {
            removed = queue.remove(item);
        }
        if (!removed) {
            return false;
        }
        recordDequeued(item, now);
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
        removedFunctions.add(functionName);
        drainRemovedFunction(functionName);
        estimator.removeFunctionState(functionName);
        metrics.removeFunctionState(functionName);
    }

    public void registerFunction(String functionName) {
        removedFunctions.remove(functionName);
        metrics.registerFunction(functionName);
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
        ExecutionRecord record = executionStore.getOrNull(item.task().executionId());
        if (record == null) {
            if (wasQueued) {
                metrics.dequeued(functionName);
            }
            return;
        }
        InvocationResult result = InvocationResult.error(
                FUNCTION_REMOVED,
                "Function '%s' was removed before queued execution could run".formatted(functionName)
        );
        synchronized (record) {
            if (!record.isTerminal()) {
                record.markError(result.error());
                record.completion().complete(result);
            }
        }
        if (wasQueued) {
            metrics.dequeued(functionName);
        }
    }

    private boolean isTimedOut(SyncQueueItem item, Instant now) {
        return item.enqueuedAt().plus(configSource.syncQueueMaxQueueWait()).isBefore(now);
    }

    private void timeout(SyncQueueItem item) {
        ExecutionRecord record = executionStore.getOrNull(item.task().executionId());
        if (record != null) {
            // Guard: completeExecution publishes the future outside the record monitor; only complete if not already finalized.
            synchronized (record) {
                if (!record.isTerminal()) {
                    record.markTimeout();
                    record.completion().complete(InvocationResult.error("QUEUE_TIMEOUT", "Queue wait exceeded"));
                }
            }
        }
        metrics.dequeued(item.task().functionName());
        metrics.timedOut(item.task().functionName());
    }

    private void recordDequeued(SyncQueueItem item, Instant now) {
        metrics.dequeued(item.task().functionName());
        long waitMillis = Duration.between(item.enqueuedAt(), now).toMillis();
        metrics.recordWait(item.task().functionName(), waitMillis);
    }
}
