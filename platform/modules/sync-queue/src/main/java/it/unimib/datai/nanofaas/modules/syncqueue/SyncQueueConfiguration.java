package it.unimib.datai.nanofaas.modules.syncqueue;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer.AdmissionProfile;
import it.unimib.datai.nanofaas.controlplane.service.EngineSyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.execution.admission.SyncQueueAdmissionController;
import it.unimib.datai.nanofaas.execution.admission.SyncQueueAdmissionResult;
import it.unimib.datai.nanofaas.execution.admission.WaitEstimator;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueMetrics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Registers this module's strategy factory plus the legacy config adapters genuinely still
 * needed (Task 8, issue #208): the runtime-mutable {@link SyncQueueConfigSource}, the module's
 * runtime defaults record, and the admission collaborators ({@link WaitEstimator},
 * {@link SyncQueueAdmissionController}) composed into {@link EngineSyncQueueGateway}. The old
 * {@code SyncQueueService}/{@code SyncScheduler} worker and its own queue are retired as beans —
 * {@code SchedulerConfiguration} now owns the single engine they used to duplicate — but the
 * class itself is untouched (Task 13 removes it, after a full impact pass).
 */
@AutoConfiguration
@EnableConfigurationProperties(SyncQueueProperties.class)
public class SyncQueueConfiguration {

    /** How often the wait estimator prunes expired samples in the absence of new dispatches,
     * mirroring the cadence {@code SyncScheduler}'s own tick loop gave it
     * ({@code SyncScheduler.EMPTY_QUEUE_AWAIT_MS}) before that worker was retired. */
    private static final long ESTIMATOR_MAINTENANCE_PERIOD_MS = 500L;

    @Bean
    SchedulingStrategy sharedQueueStrategy() {
        return new SharedQueueSchedulingStrategy();
    }

    @Bean("mutableSyncQueueConfigSource")
    @Primary
    MutableSyncQueueConfigSource syncQueueConfigSource(SyncQueueProperties props) {
        return new MutableSyncQueueConfigSource(props);
    }

    @Bean
    @Primary
    SyncQueueRuntimeDefaults moduleSyncQueueRuntimeDefaults(SyncQueueProperties props) {
        return props.runtimeDefaults();
    }

    @Bean("syncQueueMaxDepth")
    Integer syncQueueMaxDepth(SyncQueueProperties props) {
        return props.maxDepth();
    }

    @Bean
    WaitEstimator syncQueueWaitEstimator(SyncQueueProperties props) {
        return new WaitEstimator(props.throughputWindow(), props.perFunctionMinSamples());
    }

    @Bean
    SyncQueueAdmissionController syncQueueAdmissionController(SyncQueueConfigSource configSource,
            SyncQueueProperties props, WaitEstimator estimator) {
        return new SyncQueueAdmissionController(configSource, props.maxDepth(), estimator);
    }

    /** ponytail: a single daemon timer, not a general-purpose scheduling facility — its only job
     * is keeping {@link WaitEstimator#maintain} running while the queue is idle, since nothing
     * else calls it once {@code SyncScheduler}'s tick loop is retired. */
    @Bean(destroyMethod = "shutdown")
    @SuppressWarnings("FutureReturnValueIgnored") // Periodic maintenance; nothing awaits this handle.
    ScheduledExecutorService syncQueueEstimatorMaintenance(WaitEstimator estimator) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nanofaas-sync-queue-estimator-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> estimator.maintain(Instant.now()),
                ESTIMATOR_MAINTENANCE_PERIOD_MS, ESTIMATOR_MAINTENANCE_PERIOD_MS, TimeUnit.MILLISECONDS);
        return executor;
    }

    /**
     * Reclaims {@code SyncQueueMetrics}' {@code sync_queue_admitted_total}/
     * {@code sync_queue_rejected_total} counters (Task 11, issue #208): the retired
     * {@code SyncQueueService} recorded these on its own queue; {@link EngineSyncQueueGateway}
     * previously recorded neither. A dedicated bean, not shared with the engine-backed
     * {@code WorkloadMetricsBinder}/{@code WorkloadDiagnostics} in {@code SchedulerConfiguration}:
     * those are cumulative depth/backlog gauges the whole composition owns; this is this module's
     * own admission counter, exactly as it was before Task 8.
     */
    @Bean
    SyncQueueMetrics syncQueueMetrics(MeterRegistry registry) {
        return new SyncQueueMetrics(registry);
    }

    /** Registers/retires this module's own admission counters alongside every other per-function
     * resource; independent of {@code SchedulerConfiguration}'s own
     * {@code FunctionRegistrationListener} — {@code FunctionService} notifies every listener
     * bean, so both run on every register/remove without needing to know about each other. */
    @Bean
    FunctionRegistrationListener syncQueueMetricsLifecycleListener(SyncQueueMetrics metrics) {
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(FunctionSpec spec) {
                metrics.registerFunction(spec.name());
            }

            @Override
            public void onRemove(String functionName) {
                metrics.removeFunctionState(functionName);
            }
        };
    }

    @Bean
    @Primary
    EngineSyncQueueGateway engineSyncQueueGateway(SyncQueueConfigSource configSource,
            SyncQueueAdmissionController admissionController, WaitEstimator estimator,
            org.springframework.beans.factory.ObjectProvider<SchedulerEngine> engine,
            PendingWorkStore store, DispatchCapacity capacityRegistry,
            LongSupplier schedulerTicketSequence, AdmissionProfile admissionProfile,
            SyncQueueMetrics metrics) {
        return new EngineSyncQueueGateway(configSource,
                (functionName, depth, now) -> {
                    SyncQueueAdmissionResult result = admissionController.evaluate(functionName, depth, now);
                    return result.accepted() ? null : result.reason();
                },
                estimator::recordDispatch,
                estimator::removeFunctionState,
                engine, store, capacityRegistry, schedulerTicketSequence, admissionProfile,
                metrics::admitted, metrics::rejected);
    }
}
