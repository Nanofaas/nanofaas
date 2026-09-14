package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerDispatchSupport;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

@SuppressWarnings("FutureReturnValueIgnored") // The executor owns the loop until lifecycle shutdown.
public class Scheduler implements SmartLifecycle, WorkSignaler {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private static final String COMPONENT_NAME = "Scheduler";
    /**
     * How many consecutive dispatches one function gets before the loop moves on.
     *
     * <p>A trade-off between scheduling cost and fairness: a wider batch amortises the loop
     * pass over more dispatches, but holds one function's turn for longer. 2, 4, 8 and 16 were
     * compared — see docs/experiments/control-plane-tuning-2026-09/RESULTS.md for the
     * measurement and the decision.
     */
    static final int DEFAULT_MAX_BATCH_PER_FUNCTION = 2;

    private final QueueManager queueManager;
    private final InvocationDispatch invocationService;
    private final QueueLifecycle queueLifecycle;
    private final LongSupplier nanoTime;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lifecycleMonitor = new Object();
    private final AtomicReference<ExecutorService> executor = new AtomicReference<>();
    private final int maxBatchPerFunction;

    private final BlockingQueue<String> activeFunctions = new LinkedBlockingQueue<>();
    private final Set<String> enqueuedFunctions = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> signalTimes = new ConcurrentHashMap<>();

    public Scheduler(QueueManager queueManager,
                     InvocationDispatch invocationService, QueueLifecycle queueLifecycle) {
        this(queueManager, invocationService, queueLifecycle, System::nanoTime);
    }

    Scheduler(QueueManager queueManager,
              InvocationDispatch invocationService,
              QueueLifecycle queueLifecycle,
              LongSupplier nanoTime) {
        this(queueManager, invocationService, queueLifecycle, nanoTime, DEFAULT_MAX_BATCH_PER_FUNCTION);
    }

    /** The batch is injectable so it can be compared; production uses the default. */
    Scheduler(QueueManager queueManager,
              InvocationDispatch invocationService,
              QueueLifecycle queueLifecycle,
              LongSupplier nanoTime,
              int maxBatchPerFunction) {
        this.queueManager = queueManager;
        this.invocationService = invocationService;
        this.queueLifecycle = queueLifecycle;
        this.nanoTime = nanoTime;
        this.maxBatchPerFunction = Math.max(1, maxBatchPerFunction);
    }

    @PostConstruct
    public void init() {
        queueManager.setWorkSignaler(this);
    }

    @Override
    public void signalWork(String functionName) {
        if (enqueuedFunctions.add(functionName)) {
            signalTimes.put(functionName, nanoTime.getAsLong());
            long enqueueStarted = nanoTime.getAsLong();
            activeFunctions.add(functionName);
            queueManager.recordSchedulerSignalEnqueueDuration(functionName, nanoTime.getAsLong() - enqueueStarted);
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
                long idleStarted = nanoTime.getAsLong();
                String functionName = activeFunctions.poll(500, TimeUnit.MILLISECONDS);
                long polledAt = nanoTime.getAsLong();
                long visitStarted = polledAt;
                queueManager.recordSchedulerIdleDuration(polledAt - idleStarted);
                if (functionName != null) {
                    long bookkeepingStarted = nanoTime.getAsLong();
                    Long signalTime = signalTimes.remove(functionName);
                    enqueuedFunctions.remove(functionName);
                    if (signalTime != null) {
                        queueManager.recordSchedulerPollDelay(functionName, polledAt - signalTime);
                        queueManager.recordSchedulerWakeupDelay(
                                functionName,
                                nanoTime.getAsLong() - signalTime
                        );
                        queueManager.recordSchedulerActivationBookkeepingDuration(
                                functionName,
                                nanoTime.getAsLong() - bookkeepingStarted
                        );
                    }
                    processFunction(functionName);
                    // Recording the timer itself is intentionally outside the measured visit.
                    queueManager.recordSchedulerVisitDuration(nanoTime.getAsLong() - visitStarted);
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

    /**
     * The next task to dispatch, with its slot already taken, or null when there is
     * nothing to dispatch.
     *
     * <p>Split from the loop because the two ways of coming up empty are not the
     * same: no slot was taken at all, or a slot was taken and has to go back. Inside
     * the loop they were two breaks whose cleanup differed; here the caller sees one
     * answer and the cleanup stays next to what it undoes.</p>
     */
    private InvocationTask acquireNext(String functionName, FunctionQueueState state) {
        var lease = queueManager.tryAcquireLease(functionName, state);
        if (lease == null) {
            queueManager.recordSchedulerSlotBlocked(functionName);
            return null;
        }
        long pollStarted = nanoTime.getAsLong();
        InvocationTask task = state.pollForDispatch();
        queueManager.recordQueuePollDuration(
                functionName,
                nanoTime.getAsLong() - pollStarted
        );
        if (task == null) {
            lease.release();
            return null;
        }
        return task.withDispatchLease(lease);
    }

    private void processFunction(String functionName) {
        FunctionQueueState state = queueManager.get(functionName);
        if (state == null) {
            return;
        }

        int dispatched = 0;
        while (running.get() && dispatched < maxBatchPerFunction) {
            InvocationTask task = acquireNext(functionName, state);
            if (task == null) {
                break;
            }
            dispatched++;
            long dispatchStarted = nanoTime.getAsLong();
            SchedulerDispatchSupport.Result result = null;
            try {
                result = SchedulerDispatchSupport.dispatchWithFailureCleanup(
                        task,
                        () -> invocationService.dispatch(task),
                        () -> task.dispatchLease().release(),
                        failure -> queueLifecycle.rejected(task, failure),
                        log
                );
                if (result == SchedulerDispatchSupport.Result.INPUT_BACKPRESSURED) {
                    InvocationTask queuedTask = task.withDispatchLease(null);
                    if (!state.requeueAfterInputBackpressure(queuedTask)) {
                        queueLifecycle.removed(queuedTask);
                    }
                }
            } finally {
                if (result != SchedulerDispatchSupport.Result.INPUT_BACKPRESSURED) {
                    state.completeDispatchReservation(task);
                }
            }
            queueManager.recordSchedulerDispatchSubmitDuration(
                    functionName,
                    nanoTime.getAsLong() - dispatchStarted
            );
        }

        int queued = state.queued();
        if (queued > 0 && dispatched == maxBatchPerFunction) {
            queueManager.recordSchedulerBatchLimit(functionName);
        }
        if (queued > 0 && dispatched > 0 && state.canDispatch()) {
            signalWork(functionName);
        }
    }

}
