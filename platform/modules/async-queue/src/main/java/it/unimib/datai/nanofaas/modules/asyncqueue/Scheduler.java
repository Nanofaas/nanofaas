package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerDispatchSupport;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Scheduler implements SmartLifecycle, WorkSignaler {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private static final String COMPONENT_NAME = "Scheduler";
    private static final int MAX_BATCH_PER_FUNCTION = 2;

    private final QueueManager queueManager;
    private final InvocationService invocationService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lifecycleMonitor = new Object();
    private final AtomicReference<ExecutorService> executor = new AtomicReference<>();

    private final BlockingQueue<String> activeFunctions = new LinkedBlockingQueue<>();
    private final Set<String> enqueuedFunctions = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> signalTimes = new ConcurrentHashMap<>();

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
            signalTimes.put(functionName, System.nanoTime());
            long enqueueStarted = System.nanoTime();
            activeFunctions.add(functionName);
            queueManager.recordSchedulerSignalEnqueueDuration(functionName, System.nanoTime() - enqueueStarted);
        } else {
            queueManager.recordSchedulerSignalCoalesced(functionName);
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

    private void loop() {
        log.info("Scheduler loop started");
        while (running.get()) {
            try {
                String functionName = activeFunctions.poll(500, TimeUnit.MILLISECONDS);
                if (functionName != null) {
                    long bookkeepingStarted = System.nanoTime();
                    Long signalTime = signalTimes.remove(functionName);
                    enqueuedFunctions.remove(functionName);
                    if (signalTime != null) {
                        long polledAt = bookkeepingStarted;
                        queueManager.recordSchedulerPollDelay(functionName, polledAt - signalTime);
                        queueManager.recordSchedulerWakeupDelay(
                                functionName,
                                System.nanoTime() - signalTime
                        );
                        queueManager.recordSchedulerActivationBookkeepingDuration(
                                functionName,
                                System.nanoTime() - bookkeepingStarted
                        );
                    }
                    processFunction(functionName, System.nanoTime());
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in scheduler loop", e);
            }
        }
        log.info("Scheduler loop exited");
    }

    private void processFunction(String functionName, long schedulerActivatedAt) {
        FunctionQueueState state = queueManager.get(functionName);
        if (state == null) {
            return;
        }

        int dispatched = 0;
        while (running.get() && dispatched < MAX_BATCH_PER_FUNCTION) {
            if (!state.tryAcquireSlot()) {
                queueManager.recordSchedulerSlotBlocked(functionName);
                break;
            }
            queueManager.recordSlotReacquisitionDelay(functionName, schedulerActivatedAt);
            long pollStarted = System.nanoTime();
            InvocationTask task = state.poll();
            queueManager.recordQueuePollDuration(
                    functionName,
                    System.nanoTime() - pollStarted
            );
            if (task == null) {
                state.releaseSlot();
                break;
            }
            dispatched++;
            long dispatchStarted = System.nanoTime();
            SchedulerDispatchSupport.dispatchWithFailureCleanup(
                    task,
                    () -> invocationService.dispatch(task),
                    state::releaseSlot,
                    log
            );
            queueManager.recordSchedulerDispatchSubmitDuration(
                    functionName,
                    System.nanoTime() - dispatchStarted
            );
        }

        int queued = state.queued();
        if (queued > 0 && dispatched == MAX_BATCH_PER_FUNCTION) {
            queueManager.recordSchedulerBatchLimit(functionName);
        }
        if (queued > 0 && dispatched > 0 && state.canDispatch()) {
            signalWork(functionName);
        }
    }

}
