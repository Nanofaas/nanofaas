package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.controlplane.queue.QueueManager;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class Scheduler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final QueueManager queueManager;
    private final InvocationService invocationService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nanofaas-scheduler");
        t.setDaemon(false);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long tickMs = 2;

    public Scheduler(QueueManager queueManager,
                     InvocationService invocationService) {
        this.queueManager = queueManager;
        this.invocationService = invocationService;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Scheduler starting");
            executor.submit(this::loop);
        }
    }

    @Override
    public void stop() {
        log.info("Scheduler stopping...");
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate in time, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            log.warn("Scheduler shutdown interrupted");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Scheduler stopped");
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

    private void loop() {
        log.debug("Scheduler loop started");
        while (running.get()) {
            java.util.concurrent.atomic.AtomicBoolean didWork = new java.util.concurrent.atomic.AtomicBoolean(false);
            queueManager.forEachQueue(state -> {
                if (!running.get()) {
                    return;
                }
                // Atomically acquire a dispatch slot
                if (state.tryAcquireSlot()) {
                    // Slot acquired - try to get a task
                    InvocationTask task = state.poll();
                    if (task == null) {
                        // No task in queue - release the slot
                        state.releaseSlot();
                    } else {
                        didWork.set(true);
                        invocationService.dispatch(task);
                    }
                }
            });
            if (!didWork.get()) {
                sleep(tickMs);
            }
        }
        log.debug("Scheduler loop exited");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
