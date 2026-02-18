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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(prefix = "sync-queue", name = "enabled", havingValue = "true")
public class SyncScheduler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final InvocationEnqueuer enqueuer;
    private final SyncQueueService queue;
    private final Consumer<InvocationTask> dispatch;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nanofaas-sync-scheduler");
        t.setDaemon(false);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long tickMs = 2;

    @Autowired
    public SyncScheduler(InvocationEnqueuer enqueuer,
                         SyncQueueService queue,
                         it.unimib.datai.nanofaas.controlplane.service.InvocationService invocationService) {
        this(enqueuer, queue, invocationService::dispatch);
    }

    SyncScheduler(InvocationEnqueuer enqueuer, SyncQueueService queue, Consumer<InvocationTask> dispatch) {
        this.enqueuer = enqueuer;
        this.queue = queue;
        this.dispatch = dispatch;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this::loop);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
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
        Instant now = Instant.now();
        SyncQueueItem item = queue.peekReady(now);
        if (item == null) {
            queue.awaitWork(tickMs);
            return;
        }
        if (!enqueuer.tryAcquireSlot(item.task().functionName())) {
            sleep(tickMs);
            return;
        }
        SyncQueueItem polled = queue.pollReady(now);
        if (polled == null) {
            enqueuer.releaseDispatchSlot(item.task().functionName());
            return;
        }
        queue.recordDispatched(polled.task().functionName(), now);
        try {
            dispatch.accept(polled.task());
        } catch (Exception ex) {
            enqueuer.releaseDispatchSlot(polled.task().functionName());
            log.error("Dispatch failed for execution {}: {}",
                    polled.task().executionId(), ex.getMessage(), ex);
        }
    }

    private void loop() {
        while (running.get()) {
            tickOnce();
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
