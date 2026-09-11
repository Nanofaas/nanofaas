package it.unimib.datai.nanofaas.modules.syncqueue.scheduler;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueuedDispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerDispatchSupport;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueItem;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadDiagnostics;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public class SyncScheduler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);
    private static final String COMPONENT_NAME = "Sync scheduler";

    /**
     * How long the worker parks on the queue's notification monitor after finding the queue
     * empty. New work, a capacity release, a registration or a capacity increase wake it
     * immediately via the queue's notification sequence, so this timeout is a safety bound
     * (spurious wakeups, shutdown detection), not the dispatch-latency budget. It deliberately
     * is not a tight poll: the scheduler is created even while admission is disabled, and an
     * idle worker must stay dormant rather than wake the CPU on a short timer.
     */
    private static final long EMPTY_QUEUE_AWAIT_MS = 500L;

    /**
     * How long the worker parks when the queue has work but nothing in the current scan window
     * can dispatch (every scanned item's function is at its concurrency limit, or unregistered).
     * This too is a notifiable wait - a released slot, a capacity increase or a registration
     * wakes it at once - so the timeout is only a safety bound. It is kept short (the old
     * backoff cap was 50 ms) so that, in the absence of notifications, queue items that have
     * expired are reaped and the scan window keeps rotating toward functions queued beyond the
     * scan limit (head-of-line fairness). It is a timed park, not a poll: no CPU is consumed
     * while parked.
     */
    private static final long CAPACITY_BLOCKED_AWAIT_MS = 50L;

    private final QueuedDispatchCapacity enqueuer;
    private final SyncQueueService queue;
    private final InvocationDispatch dispatch;
    private final QueueLifecycle queueLifecycle;
    private final WorkloadDiagnostics diagnostics;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lifecycleMonitor = new Object();
    private final AtomicReference<ExecutorService> executor = new AtomicReference<>();

    public SyncScheduler(QueuedDispatchCapacity enqueuer, SyncQueueService queue, InvocationDispatch dispatch, QueueLifecycle queueLifecycle) {
        this(enqueuer, queue, dispatch, queueLifecycle, null);
    }

    public SyncScheduler(QueuedDispatchCapacity enqueuer, SyncQueueService queue,
                         InvocationDispatch dispatch, QueueLifecycle queueLifecycle, WorkloadDiagnostics diagnostics) {
        this.enqueuer = enqueuer;
        this.queue = queue;
        this.dispatch = dispatch;
        this.queueLifecycle = queueLifecycle;
        this.diagnostics = diagnostics;
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            ExecutorService newExecutor = SchedulerLifecycleSupport.newSingleThreadExecutor("nanofaas-sync-scheduler");
            executor.set(newExecutor);
            try {
                newExecutor.submit(this::loop);
            } catch (RuntimeException e) {
                executor.set(null);
                running.set(false);
                SchedulerLifecycleSupport.shutdownExecutor(newExecutor, log, COMPONENT_NAME);
                throw e;
            }
        }
    }

    @Override
    public void stop() {
        ExecutorService executorToStop;
        synchronized (lifecycleMonitor) {
            if (!running.getAndSet(false) && executor.get() == null) {
                return;
            }
            executorToStop = executor.getAndSet(null);
        }
        if (executorToStop != null) {
            // Interrupt the parked worker so stop() does not wait out a safety bound; the loop
            // exits because running is false (and the interrupt flag is set).
            executorToStop.shutdownNow();
            SchedulerLifecycleSupport.shutdownExecutor(executorToStop, log, COMPONENT_NAME);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    void tickOnce() {
        long visitStarted = System.nanoTime();
        try {
            tickOnceInternal();
        } finally {
            if (diagnostics != null) {
                diagnostics.recordSchedulerVisitDuration(System.nanoTime() - visitStarted);
            }
        }
    }

    private void tickOnceInternal() {
        // Record the notification sequence BEFORE scanning. If anything the scheduler cares
        // about fires while the scan runs (new work, a released slot, a capacity increase), the
        // sequence advances and the subsequent wait returns immediately instead of parking
        // through the change - the lost-wakeup window between the state check and the wait.
        long observedEpoch = queue.wakeupEpoch();
        Instant now = Instant.now();
        queue.maintainEstimator(now);
        SyncQueueItem item = queue.findReadyMatching(now, task -> {
            boolean available = enqueuer.hasAvailableSlot(task.functionName());
            if (!available && diagnostics != null) {
                diagnostics.recordSchedulerSlotBlocked(task.functionName());
            }
            return available;
        });
        if (item == null) {
            if (queue.peekReady(now) == null) {
                long idleStarted = System.nanoTime();
                queue.awaitWakeup(EMPTY_QUEUE_AWAIT_MS, observedEpoch);
                if (diagnostics != null) {
                    diagnostics.recordSchedulerIdleDuration(System.nanoTime() - idleStarted);
                }
            } else {
                // Work exists but nothing in the scan window can dispatch (its functions are at
                // their limits, or unregistered). Rotate the window so functions queued beyond
                // the scan limit are eventually considered, then park on capacity/work.
                queue.rotateReadyScanWindow(now);
                queue.awaitWakeup(CAPACITY_BLOCKED_AWAIT_MS, observedEpoch);
            }
            return;
        }
        String functionName = item.task().functionName();
        var lease = enqueuer.tryAcquireLease(item.task());
        if (lease == null) {
            // The slot went between the scan and the acquire (e.g. the effective limit was
            // lowered concurrently). Put the item back and park: the function is genuinely at
            // its limit, and only a capacity event or the safety timeout should re-try it.
            queue.rotateReadyItem(item, now);
            queue.awaitWakeup(CAPACITY_BLOCKED_AWAIT_MS, observedEpoch);
            return;
        }
        if (!queue.removeReadyForDispatch(item, now)) {
            // Another thread removed the item (e.g. a removeFunctionState drain) after the slot
            // was taken. Give the slot back and let the loop re-scan immediately: this is a
            // one-time race, not a condition to back off on.
            lease.release();
            return;
        }
        queue.recordDispatched(functionName, now);
        long submitStarted = System.nanoTime();
        InvocationTask acquiredTask = item.task().withDispatchLease(lease);
        SchedulerDispatchSupport.Result result = null;
        try {
            result = SchedulerDispatchSupport.dispatchWithFailureCleanup(
                    acquiredTask,
                    () -> dispatch.dispatch(acquiredTask),
                    lease::release,
                    failure -> queueLifecycle.rejected(acquiredTask, failure),
                    log
            );
            if (result == SchedulerDispatchSupport.Result.INPUT_BACKPRESSURED) {
                queue.requeueAfterInputBackpressure(item);
            }
        } finally {
            if (result != SchedulerDispatchSupport.Result.INPUT_BACKPRESSURED) {
                queue.completeDispatchReservation(item);
            }
        }
        if (diagnostics != null) {
            diagnostics.recordDispatchSubmitDuration(functionName, System.nanoTime() - submitStarted);
        }
    }

    private void loop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            tickOnce();
        }
    }
}
