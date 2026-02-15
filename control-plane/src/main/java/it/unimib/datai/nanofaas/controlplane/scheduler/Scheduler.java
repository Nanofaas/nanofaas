package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.controlplane.queue.FunctionQueueState;
import it.unimib.datai.nanofaas.controlplane.queue.QueueManager;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class Scheduler implements SmartLifecycle, WorkSignaler {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final QueueManager queueManager;
    private final InvocationService invocationService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nanofaas-scheduler");
        t.setDaemon(false);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final BlockingQueue<String> activeFunctions = new LinkedBlockingQueue<>();
    private final Set<String> enqueuedFunctions = ConcurrentHashMap.newKeySet();

    public Scheduler(QueueManager queueManager,
                     InvocationService invocationService) {
        this.queueManager = queueManager;
        this.invocationService = invocationService;
    }

    @PostConstruct
    public void init() {
        queueManager.setWorkSignaler(this);
    }

    @Override
    public void signalWork(String functionName) {
        if (enqueuedFunctions.add(functionName)) {
            activeFunctions.offer(functionName);
        }
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
        log.info("Scheduler loop started");
        while (running.get()) {
            try {
                String functionName = activeFunctions.poll(500, TimeUnit.MILLISECONDS);
                if (functionName == null) {
                    continue;
                }
                enqueuedFunctions.remove(functionName);
                processFunction(functionName);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in scheduler loop", e);
            }
        }
        log.info("Scheduler loop exited");
    }

    private void processFunction(String functionName) {
        FunctionQueueState state = queueManager.get(functionName);
        if (state == null) {
            return;
        }

        while (running.get() && state.tryAcquireSlot()) {
            InvocationTask task = state.poll();
            if (task == null) {
                state.releaseSlot();
                break;
            }
            try {
                invocationService.dispatch(task);
            } catch (Exception ex) {
                state.releaseSlot();
                log.error("Dispatch failed for execution {}: {}",
                        task.executionId(), ex.getMessage(), ex);
            }
        }
    }

}
