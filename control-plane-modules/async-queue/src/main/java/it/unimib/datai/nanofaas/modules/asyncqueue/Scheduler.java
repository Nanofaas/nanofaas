package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerDispatchSupport;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Scheduler implements SmartLifecycle, WorkSignaler {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private static final String COMPONENT_NAME = "Scheduler";
    private static final int MAX_BATCH_PER_FUNCTION = 2;

    private final QueueManager queueManager;
    private final InvocationService invocationService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lifecycleMonitor = new Object();
    private volatile ExecutorService executor;

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
        synchronized (lifecycleMonitor) {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            log.info("Scheduler starting");
            ExecutorService newExecutor = SchedulerLifecycleSupport.newSingleThreadExecutor("nanofaas-scheduler");
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

        int dispatched = 0;
        while (running.get() && dispatched < MAX_BATCH_PER_FUNCTION && state.tryAcquireSlot()) {
            InvocationTask task = state.poll();
            if (task == null) {
                state.releaseSlot();
                break;
            }
            dispatched++;
            SchedulerDispatchSupport.dispatchWithFailureCleanup(
                    task,
                    () -> invocationService.dispatch(task),
                    state::releaseSlot,
                    log
            );
        }

        if (state.queued() > 0) {
            signalWork(functionName);
        }
    }

}
