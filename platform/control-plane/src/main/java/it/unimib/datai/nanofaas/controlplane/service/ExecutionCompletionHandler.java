package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentReadiness;
import it.unimib.datai.nanofaas.controlplane.dispatch.AttemptTransportAdapter;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.TimeSource;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.execution.AttemptCoordinator;
import it.unimib.datai.nanofaas.execution.AttemptObserver;
import it.unimib.datai.nanofaas.execution.AttemptTransport;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Spring facade over {@link AttemptCoordinator} (issue #208, Task 10): the attempt state
 * machine — dispatch, retry and completion — moved into the runtime library so it is available
 * regardless of which scheduler strategy is active. This class now owns two things: the existing
 * constructor/overload surface (~177 upstream callers, per the pre-move impact census), and
 * translating {@link AttemptObserver} notifications into the exact same {@link Metrics} calls
 * this class used to make directly.
 *
 * <p>Mode-based transport selection (LOCAL/EXTERNAL/DEPLOYMENT dispatch, deployment wake-up) is
 * step 4's {@link AttemptTransportAdapter} — the only place {@link DispatcherRouter} and {@link
 * DeploymentReadiness} are called from the attempt path. {@link AttemptCoordinator} itself never
 * imports either type.
 */
@Service
public class ExecutionCompletionHandler implements InvocationDispatch, AttemptObserver {
    private static final Logger log = LoggerFactory.getLogger(ExecutionCompletionHandler.class);

    /**
     * Marks an outcome the administrative-expiry path fabricated because the real
     * one never arrived, as opposed to a genuine runtime error.
     */
    static final String EXECUTION_EXPIRED_CODE = AttemptCoordinator.EXECUTION_EXPIRED_CODE;

    private final Metrics metrics;
    private final AttemptCoordinator coordinator;

    /**
     * Production constructor: deployment invocations wait for a scaled-to-zero
     * managed deployment before their external dispatch.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ExecutionCompletionHandler(ExecutionStore executionStore,
                                      @Nullable RetryScheduler enqueuer,
                                      DispatcherRouter dispatcherRouter,
                                      Metrics metrics,
                                      @Nullable DeploymentReadiness readiness,
                                      FunctionCapacityRegistry capacityRegistry) {
        this.metrics = metrics;
        RetryScheduler retry = enqueuer == null ? RetryScheduler.unavailable() : enqueuer;
        // A handler built without one (a bare unit test) has no managed deployment to wake.
        DeploymentReadiness effectiveReadiness = readiness == null ? DeploymentReadiness.immediate() : readiness;
        // A handler built without a shared registry (a bare unit test) still bounds direct
        // admission: it owns a private registry rather than admitting unbounded work.
        FunctionCapacityRegistry capacity = capacityRegistry == null ? new FunctionCapacityRegistry() : capacityRegistry;
        AttemptTransport transport = new AttemptTransportAdapter(dispatcherRouter, effectiveReadiness);
        this.coordinator = new AttemptCoordinator(executionStore, capacity,
                new MeteredRetryScheduler(retry), transport, TimeSource.system(), this);
    }

    /**
     * Compatibility constructor for direct unit-test construction. Production
     * uses the Spring constructor above.
     */
    public ExecutionCompletionHandler(ExecutionStore executionStore,
                                      @Nullable RetryScheduler enqueuer,
                                      DispatcherRouter dispatcherRouter,
                                      Metrics metrics) {
        this(executionStore, enqueuer, dispatcherRouter, metrics,
                null, null);
    }

    /** Compatibility constructor that also passes a readiness port, without a shared registry. */
    public ExecutionCompletionHandler(ExecutionStore executionStore,
                                      @Nullable RetryScheduler enqueuer,
                                      DispatcherRouter dispatcherRouter,
                                      Metrics metrics,
                                      @Nullable DeploymentReadiness readiness) {
        this(executionStore, enqueuer, dispatcherRouter, metrics, readiness, null);
    }

    public void completeOffloadedExecution(String executionId, InvocationResult result) {
        coordinator.completeOffloadedExecution(executionId, result);
    }

    public void failOffloadedExecution(String executionId, OffloadFailedException failure) {
        coordinator.failOffloadedExecution(executionId, failure);
    }

    @Override
    public void dispatch(InvocationTask task) {
        coordinator.dispatch(task);
    }

    /**
     * Direct (no-queue) admission: the core applies the configured concurrency itself.
     */
    public void dispatchDirect(InvocationTask task) {
        coordinator.dispatchDirect(task);
    }

    public void completeExecution(String executionId, DispatchResult dispatchResult) {
        coordinator.completeExecution(executionId, dispatchResult, null);
    }

    public void completeExecution(String executionId, DispatchResult dispatchResult, Integer completedAttempt) {
        coordinator.completeExecution(executionId, dispatchResult, completedAttempt);
    }

    /**
     * Overload for backward compatibility (e.g., callback completions without cold start info).
     */
    public void completeExecution(String executionId, InvocationResult result) {
        completeExecution(executionId, DispatchResult.warm(result));
    }

    public void completeExecution(String executionId, InvocationResult result, Integer completedAttempt) {
        completeExecution(executionId, DispatchResult.warm(result), completedAttempt);
    }

    // ---- AttemptObserver: translates the coordinator's notifications into the exact same
    // Metrics calls this class used to make directly. Every call already arrives outside any
    // lock and past the coordinator's own generation fencing (see AttemptCoordinator).

    @Override
    public void submitted(InvocationTask task) {
        bestEffort(() -> metrics.dispatch(task.functionName()));
    }

    @Override
    public void retried(InvocationTask task) {
        bestEffort(() -> metrics.retry(task.functionName()));
    }

    /**
     * Success/error counters live here, keyed off {@code result}: {@code completed} fires
     * exactly once per invocation, from whichever of {@link AttemptCoordinator}'s own concluding
     * actions decided it (normal completion, a retry that could never be scheduled, an offloaded
     * call's conclusion, or an administrative expiry) — matching the four separate calls {@code
     * completeOffloadedExecution}/{@code failOffloadedExecution}/{@code
     * handleAdministrativeExpiry}/{@code publishFinalCompletion} used to make individually
     * before this move. {@link AttemptObserver#NO_ATTEMPT} marks a conclusion that never
     * dispatched at all (offload, administrative expiry, an unscheduled retry): cold/warm-start
     * and the per-attempt timers are skipped for it, but success/error is still recorded — it
     * always was, even for those cases.
     */
    @Override
    public void completed(InvocationTask task, DispatchResult result, long queueWaitNanos, long serviceNanos) {
        String functionName = task.functionName();
        bestEffort(() -> {
            if (queueWaitNanos != AttemptObserver.NO_ATTEMPT || serviceNanos != AttemptObserver.NO_ATTEMPT) {
                Metrics.FunctionTimers timers = metrics.timers(functionName);
                if (result.coldStart()) {
                    metrics.coldStart(functionName);
                    if (result.initDurationMs() != null) {
                        timers.initDuration().record(result.initDurationMs(), TimeUnit.MILLISECONDS);
                    }
                } else {
                    metrics.warmStart(functionName);
                }
                if (serviceNanos >= 0) {
                    timers.latency().record(serviceNanos, TimeUnit.NANOSECONDS);
                }
                if (queueWaitNanos >= 0) {
                    timers.queueWait().record(queueWaitNanos, TimeUnit.NANOSECONDS);
                }
            }
            if (result.result().success()) metrics.success(functionName);
            else metrics.error(functionName);
        });
    }

    /**
     * The e2e timer ONLY: {@code terminal} fires from the store's single shared terminal
     * transition, including for a record this class never actively concluded (a sync waiter's
     * timeout, a plain queue-side termination another module owns). Such a passive settle has no
     * success/error worth counting — only {@code completed} (see above) records those, gated on
     * this class having actually decided the conclusion. Recording success/error here too would
     * double-count every path {@code completed} already covers, and would wrongly count paths
     * (like an already-timed-out record settling again) that never should have been counted at
     * all — the exact regression {@code ExecutionCompletionHandlerTest
     * .completeExecution_afterTimeout_doesNotOverwriteTimeoutOrEmitSuccessMetrics} pins.
     */
    @Override
    public void terminal(InvocationTask task, InvocationResult result, long endToEndNanos) {
        String functionName = task.functionName();
        bestEffort(() -> metrics.timers(functionName).e2eLatency().record(endToEndNanos, TimeUnit.NANOSECONDS));
    }

    private static void bestEffort(Runnable observer) {
        try { observer.run(); }
        catch (RuntimeException failure) { log.warn("Completion observer failed", failure); }
    }

    /**
     * Wraps the injected {@link RetryScheduler} so republishing a retry keeps recording the
     * same queue-level counters {@code ExecutionCompletionHandler.publishRetry} used to record
     * via {@code InvocationEnqueueSupport.publishOrThrow(..., countAdmission=false)}: {@code
     * enqueue}/{@code queueRejected}, but never {@code admitted}/{@code refused} — republishing
     * an attempt is not a second user-facing admission (the execution was admitted once, at
     * invoke). {@link AttemptCoordinator} only needs a plain {@link RetryScheduler}, so this
     * wrapping is entirely transparent to it and confined to the facade.
     */
    private final class MeteredRetryScheduler implements RetryScheduler {
        private final RetryScheduler delegate;

        MeteredRetryScheduler(RetryScheduler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean enqueue(InvocationTask task) {
            boolean enqueued = delegate.enqueue(task);
            if (enqueued) {
                metrics.enqueue(task.functionName());
            } else {
                metrics.queueRejected(task.functionName());
            }
            return enqueued;
        }
    }
}
