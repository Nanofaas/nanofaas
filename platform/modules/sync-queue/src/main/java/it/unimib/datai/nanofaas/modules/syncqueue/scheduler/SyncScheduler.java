package it.unimib.datai.nanofaas.modules.syncqueue.scheduler;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerDispatchSupport;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueItem;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadDiagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

public class SyncScheduler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);
    private static final String COMPONENT_NAME = "Sync scheduler";

    private final InvocationEnqueuer enqueuer;
    private final SyncQueueService queue;
    private final Consumer<InvocationTask> dispatch;
    private final LongConsumer pause;
    private final WorkloadDiagnostics diagnostics;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lifecycleMonitor = new Object();
    private volatile long tickMs = 2;
    private volatile long blockedBackoffMs = tickMs;
    private final AtomicReference<ExecutorService> executor = new AtomicReference<>();

    public SyncScheduler(InvocationEnqueuer enqueuer,
                         SyncQueueService queue,
                         it.unimib.datai.nanofaas.controlplane.service.InvocationService invocationService) {
        this(enqueuer, queue, invocationService::dispatch, null, null);
    }

    public SyncScheduler(InvocationEnqueuer enqueuer,
                         SyncQueueService queue,
                         it.unimib.datai.nanofaas.controlplane.service.InvocationService invocationService,
                         WorkloadDiagnostics diagnostics) {
        this(enqueuer, queue, invocationService::dispatch, null, diagnostics);
    }

    SyncScheduler(InvocationEnqueuer enqueuer, SyncQueueService queue, Consumer<InvocationTask> dispatch) {
        this(enqueuer, queue, dispatch, null, null);
    }

    SyncScheduler(InvocationEnqueuer enqueuer,
                  SyncQueueService queue,
                  Consumer<InvocationTask> dispatch,
                  LongConsumer pause) {
        this(enqueuer, queue, dispatch, pause, null);
    }

    SyncScheduler(InvocationEnqueuer enqueuer,
                  SyncQueueService queue,
                  Consumer<InvocationTask> dispatch,
                  LongConsumer pause,
                  WorkloadDiagnostics diagnostics) {
        this.enqueuer = enqueuer;
        this.queue = queue;
        this.dispatch = dispatch;
        this.pause = pause != null ? pause : this::sleep;
        this.diagnostics = diagnostics;
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            blockedBackoffMs = tickMs;
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
        SchedulerLifecycleSupport.shutdownExecutor(executorToStop, log, COMPONENT_NAME);
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
        Instant now = Instant.now();
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
                blockedBackoffMs = tickMs;
                queue.awaitWork(tickMs);
                if (diagnostics != null) {
                    diagnostics.recordSchedulerIdleDuration(System.nanoTime() - idleStarted);
                }
            } else {
                queue.rotateReadyScanWindow(now);
                pause.accept(currentBlockedBackoff());
            }
            return;
        }
        String functionName = item.task().functionName();
        if (!enqueuer.tryAcquireSlot(functionName)) {
            queue.rotateReadyItem(item, now);
            pause.accept(currentBlockedBackoff());
            return;
        }
        if (!queue.removeReady(item, now)) {
            enqueuer.releaseDispatchSlot(functionName);
            pause.accept(currentBlockedBackoff());
            return;
        }
        blockedBackoffMs = tickMs;
        queue.recordDispatched(functionName, now);
        long submitStarted = System.nanoTime();
        SchedulerDispatchSupport.dispatchWithFailureCleanup(
                item.task(),
                () -> dispatch.accept(item.task()),
                () -> enqueuer.releaseDispatchSlot(functionName),
                log
        );
        if (diagnostics != null) {
            diagnostics.recordDispatchSubmitDuration(functionName, System.nanoTime() - submitStarted);
        }
    }

    private void loop() {
        while (running.get()) {
            tickOnce();
        }
    }

    private long currentBlockedBackoff() {
        long current = blockedBackoffMs;
        blockedBackoffMs = Math.min(current * 2, 50);
        return current;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
