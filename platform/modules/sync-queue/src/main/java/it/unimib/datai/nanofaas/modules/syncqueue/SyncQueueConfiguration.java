package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer.AdmissionProfile;
import it.unimib.datai.nanofaas.controlplane.service.EngineSyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueAdmissionController;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueAdmissionResult;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.WaitEstimator;
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

    @Bean
    @Primary
    EngineSyncQueueGateway engineSyncQueueGateway(SyncQueueConfigSource configSource,
            SyncQueueAdmissionController admissionController, WaitEstimator estimator,
            org.springframework.beans.factory.ObjectProvider<SchedulerEngine> engine,
            PendingWorkStore store, DispatchCapacity capacityRegistry,
            LongSupplier schedulerTicketSequence, AdmissionProfile admissionProfile) {
        return new EngineSyncQueueGateway(configSource,
                (functionName, depth, now) -> {
                    SyncQueueAdmissionResult result = admissionController.evaluate(functionName, depth, now);
                    return result.accepted() ? null : result.reason();
                },
                estimator::recordDispatch,
                estimator::removeFunctionState,
                engine, store, capacityRegistry, schedulerTicketSequence, admissionProfile);
    }
}
