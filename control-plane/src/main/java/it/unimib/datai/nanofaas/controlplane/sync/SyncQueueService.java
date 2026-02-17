package it.unimib.datai.nanofaas.controlplane.sync;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.controlplane.config.runtime.RuntimeConfigService;
import it.unimib.datai.nanofaas.controlplane.config.runtime.RuntimeConfigSnapshot;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class SyncQueueService {
    private final RuntimeConfigService runtimeConfigService;
    private final ExecutionStore executionStore;
    private final WaitEstimator estimator;
    private final SyncQueueMetrics metrics;
    private final Clock clock;
    private final BlockingQueue<SyncQueueItem> queue;
    private final Object workSignal = new Object();
    private final SyncQueueAdmissionController admissionController;

    @Autowired
    public SyncQueueService(SyncQueueProperties props,
                            ExecutionStore executionStore,
                            SyncQueueMetrics metrics,
                            RuntimeConfigService runtimeConfigService) {
        this(props,
                executionStore,
                new WaitEstimator(props.throughputWindow(), props.perFunctionMinSamples()),
                metrics,
                Clock.systemUTC(),
                runtimeConfigService);
    }

    SyncQueueService(SyncQueueProperties props,
                     ExecutionStore executionStore,
                     WaitEstimator estimator,
                     SyncQueueMetrics metrics,
                     Clock clock,
                     RuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
        this.executionStore = executionStore;
        this.estimator = estimator;
        this.metrics = metrics;
        this.clock = clock;
        this.queue = new LinkedBlockingQueue<>(props.maxDepth());
        this.admissionController = new SyncQueueAdmissionController(runtimeConfigService, props.maxDepth(), estimator);
    }

    public boolean enabled() {
        return runtimeConfigService.getSnapshot().syncQueueEnabled();
    }

    public int retryAfterSeconds() {
        return runtimeConfigService.getSnapshot().syncQueueRetryAfterSeconds();
    }

    public void enqueueOrThrow(InvocationTask task) {
        Instant now = clock.instant();
        SyncQueueAdmissionResult decision = admissionController.evaluate(task.functionName(), queue.size(), now);
        RuntimeConfigSnapshot config = runtimeConfigService.getSnapshot();
        if (!decision.accepted()) {
            metrics.rejected(task.functionName());
            throw new SyncQueueRejectedException(decision.reason(), config.syncQueueRetryAfterSeconds());
        }
        if (!queue.offer(new SyncQueueItem(task, now))) {
            metrics.rejected(task.functionName());
            throw new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, config.syncQueueRetryAfterSeconds());
        }
        synchronized (workSignal) {
            workSignal.notify();
        }
        metrics.registerFunction(task.functionName());
        metrics.admitted(task.functionName());
    }

    /**
     * Waits for new work when the queue is empty to avoid busy polling.
     */
    public void awaitWork(long timeoutMs) {
        if (timeoutMs <= 0 || !queue.isEmpty()) {
            return;
        }
        synchronized (workSignal) {
            if (queue.isEmpty()) {
                try {
                    workSignal.wait(timeoutMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public SyncQueueItem peekReady(Instant now) {
        while (true) {
            SyncQueueItem item = queue.peek();
            if (item == null) {
                return null;
            }
            if (isTimedOut(item, now)) {
                queue.poll();
                timeout(item);
                continue;
            }
            return item;
        }
    }

    public SyncQueueItem pollReady(Instant now) {
        SyncQueueItem item = queue.poll();
        if (item != null) {
            metrics.dequeued(item.task().functionName());
            long waitMillis = Duration.between(item.enqueuedAt(), now).toMillis();
            metrics.recordWait(item.task().functionName(), waitMillis);
        }
        return item;
    }

    public void recordDispatched(String functionName, Instant now) {
        estimator.recordDispatch(functionName, now);
    }

    private boolean isTimedOut(SyncQueueItem item, Instant now) {
        return item.enqueuedAt().plus(runtimeConfigService.getSnapshot().syncQueueMaxQueueWait()).isBefore(now);
    }

    private void timeout(SyncQueueItem item) {
        ExecutionRecord record = executionStore.getOrNull(item.task().executionId());
        if (record != null) {
            record.markTimeout();
            record.completion().complete(InvocationResult.error("QUEUE_TIMEOUT", "Queue wait exceeded"));
        }
        metrics.dequeued(item.task().functionName());
        metrics.timedOut(item.task().functionName());
    }
}
