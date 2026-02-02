package it.unimib.datai.mcfaas.controlplane.sync;

import it.unimib.datai.mcfaas.common.model.InvocationResult;
import it.unimib.datai.mcfaas.controlplane.config.SyncQueueProperties;
import it.unimib.datai.mcfaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.mcfaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.mcfaas.controlplane.scheduler.InvocationTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class SyncQueueService {
    private final SyncQueueProperties props;
    private final ExecutionStore executionStore;
    private final WaitEstimator estimator;
    private final SyncQueueMetrics metrics;
    private final Clock clock;
    private final BlockingQueue<SyncQueueItem> queue;
    private final SyncQueueAdmissionController admissionController;

    @Autowired
    public SyncQueueService(SyncQueueProperties props,
                            ExecutionStore executionStore,
                            SyncQueueMetrics metrics) {
        this(props,
                executionStore,
                new WaitEstimator(props.throughputWindow(), props.perFunctionMinSamples()),
                metrics,
                Clock.systemUTC());
    }

    SyncQueueService(SyncQueueProperties props,
                     ExecutionStore executionStore,
                     WaitEstimator estimator,
                     SyncQueueMetrics metrics,
                     Clock clock) {
        this.props = props;
        this.executionStore = executionStore;
        this.estimator = estimator;
        this.metrics = metrics;
        this.clock = clock;
        this.queue = new LinkedBlockingQueue<>(props.maxDepth());
        this.admissionController = new SyncQueueAdmissionController(props, estimator);
    }

    public boolean enabled() {
        return props.enabled();
    }

    public int retryAfterSeconds() {
        return props.retryAfterSeconds();
    }

    public void enqueueOrThrow(InvocationTask task) {
        Instant now = clock.instant();
        SyncQueueAdmissionResult decision = admissionController.evaluate(task.functionName(), queue.size(), now);
        if (!decision.accepted()) {
            metrics.rejected(task.functionName());
            throw new SyncQueueRejectedException(decision.reason(), props.retryAfterSeconds());
        }
        if (!queue.offer(new SyncQueueItem(task, now))) {
            metrics.rejected(task.functionName());
            throw new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, props.retryAfterSeconds());
        }
        metrics.registerFunction(task.functionName());
        metrics.admitted(task.functionName());
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
        return item.enqueuedAt().plus(props.maxQueueWait()).isBefore(now);
    }

    private void timeout(SyncQueueItem item) {
        ExecutionRecord record = executionStore.get(item.task().executionId()).orElse(null);
        if (record != null) {
            record.markTimeout();
            record.completion().complete(InvocationResult.error("QUEUE_TIMEOUT", "Queue wait exceeded"));
        }
        metrics.dequeued(item.task().functionName());
        metrics.timedOut(item.task().functionName());
    }
}
