package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueItem;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

@Component
@ConditionalOnProperty(prefix = "sync-queue", name = "enabled", havingValue = "true")
public class SyncScheduler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);
    private static final String COMPONENT_NAME = "Sync scheduler";

    private final InvocationEnqueuer enqueuer;
    private final SyncQueueService queue;
    private final Consumer<InvocationTask> dispatch;
    private final LongConsumer pause;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lifecycleMonitor = new Object();
    private volatile long tickMs = 2;
    private volatile long blockedBackoffMs = tickMs;
    private volatile ExecutorService executor;

    @Autowired
    public SyncScheduler(InvocationEnqueuer enqueuer,
                         SyncQueueService queue,
                         it.unimib.datai.nanofaas.controlplane.service.InvocationService invocationService) {
        this(enqueuer, queue, invocationService::dispatch, null);
    }

    SyncScheduler(InvocationEnqueuer enqueuer, SyncQueueService queue, Consumer<InvocationTask> dispatch) {
        this(enqueuer, queue, dispatch, null);
    }

    SyncScheduler(InvocationEnqueuer enqueuer,
                  SyncQueueService queue,
                  Consumer<InvocationTask> dispatch,
                  LongConsumer pause) {
        this.enqueuer = enqueuer;
        this.queue = queue;
        this.dispatch = dispatch;
        this.pause = pause != null ? pause : this::sleep;
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            blockedBackoffMs = tickMs;
            ExecutorService newExecutor = SchedulerLifecycleSupport.newSingleThreadExecutor("nanofaas-sync-scheduler");
            executor = newExecutor;
            try {
                newExecutor.submit(this::loop);
            } catch (RuntimeException e) {
                executor = null;
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
            if (!running.getAndSet(false) && executor == null) {
                return;
            }
            executorToStop = executor;
            executor = null;
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
        Instant now = Instant.now();
        SyncQueueItem item = queue.pollReadyMatching(now, task -> enqueuer.tryAcquireSlot(task.functionName()));
        if (item == null) {
            if (queue.peekReady(now) == null) {
                blockedBackoffMs = tickMs;
                queue.awaitWork(tickMs);
            } else {
                pause.accept(currentBlockedBackoff());
            }
            return;
        }
        blockedBackoffMs = tickMs;
        queue.recordDispatched(item.task().functionName(), now);
        SchedulerDispatchSupport.dispatchWithFailureCleanup(
                item.task(),
                () -> dispatch.accept(item.task()),
                () -> enqueuer.releaseDispatchSlot(item.task().functionName()),
                log
        );
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
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
