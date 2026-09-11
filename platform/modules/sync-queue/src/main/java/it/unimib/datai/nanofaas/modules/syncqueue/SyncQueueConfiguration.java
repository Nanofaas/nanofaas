package it.unimib.datai.nanofaas.modules.syncqueue;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueuedDispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.modules.syncqueue.scheduler.SyncScheduler;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueMetrics;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadDiagnostics;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@AutoConfiguration
@EnableConfigurationProperties(SyncQueueProperties.class)
public class SyncQueueConfiguration {

    @Bean
    SyncQueueMetrics syncQueueMetrics(MeterRegistry meterRegistry) {
        return new SyncQueueMetrics(meterRegistry);
    }

    @Bean
    WorkloadDiagnostics syncQueueWorkloadDiagnostics(MeterRegistry meterRegistry) {
        return new WorkloadDiagnostics(meterRegistry);
    }

    /** The governor's capacity knob, backed by the shared core registry (P06). */
    @Bean
    WorkloadCapacityController syncQueueWorkloadCapacityController(DispatchCapacity capacityRegistry) {
        return capacityRegistry::setEffectiveConcurrency;
    }

    @Bean
    SyncQueueWorkloadMetricsSource syncQueueWorkloadMetricsSource(
            SyncQueueService syncQueueService, DispatchCapacity capacityRegistry) {
        return new SyncQueueWorkloadMetricsSource(syncQueueService, capacityRegistry);
    }

    @Bean
    WorkloadMetricsBinder syncQueueWorkloadMetricsBinder(MeterRegistry meterRegistry,
                                                         SyncQueueWorkloadMetricsSource source) {
        return new WorkloadMetricsBinder(meterRegistry, source);
    }

    @Bean
    @Primary
    SyncQueueInvocationEnqueuer syncQueueInvocationEnqueuer(DispatchCapacity capacityRegistry,
                                                             WorkloadDiagnostics diagnostics,
                                                             SyncQueueService syncQueueService) {
        return new SyncQueueInvocationEnqueuer(capacityRegistry, diagnostics,
                syncQueueService::onDispatchSlotReleased, syncQueueService);
    }

    @Bean
    SyncQueueService syncQueueService(SyncQueueProperties props,
                                      QueueLifecycle executionStore,
                                      SyncQueueMetrics metrics,
                                      SyncQueueConfigSource configSource,
                                      DispatchCapacity capacityRegistry,
                                      WorkloadDiagnostics diagnostics) {
        return new SyncQueueService(props, executionStore, metrics, configSource,
                capacityRegistry, diagnostics);
    }

    @Bean("mutableSyncQueueConfigSource")
    @Primary
    MutableSyncQueueConfigSource syncQueueConfigSource(SyncQueueProperties props) {
        return new MutableSyncQueueConfigSource(props);
    }

    // Unconditional: the scheduler must exist from module load even when admission is
    // disabled at startup, because the runtime flag (MutableSyncQueueConfigSource) can
    // switch the queue on at runtime, and work admitted before a runtime deactivation -
    // including retries re-enqueued by the completion path - must keep draining. Its
    // worker idles (parks on the queue's work signal, bounded by a safety timeout) when
    // there is nothing to dispatch, so an always-on scheduler costs nothing while idle.
    @Bean
    SyncScheduler syncScheduler(QueuedDispatchCapacity enqueuer,
                                SyncQueueService syncQueueService,
                                InvocationDispatch invocationService,
                                QueueLifecycle queueLifecycle,
                                WorkloadDiagnostics diagnostics) {
        return new SyncScheduler(enqueuer, syncQueueService, invocationService, queueLifecycle, diagnostics);
    }

    @Bean
    @Primary
    SyncQueueRuntimeDefaults moduleSyncQueueRuntimeDefaults(SyncQueueProperties props) {
        return props.runtimeDefaults();
    }

    @Bean
    @Primary
    SyncQueueGateway moduleSyncQueueGateway(SyncQueueService syncQueueService) {
        return syncQueueService;
    }

    @Bean
    FunctionRegistrationListener syncQueueLifecycleListener(SyncQueueService syncQueueService,
                                                             WorkloadMetricsBinder binder,
                                                             WorkloadDiagnostics diagnostics) {
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(FunctionSpec spec) {
                syncQueueService.registerFunction(spec.name(), spec.concurrency());
                binder.registerFunction(spec.name());
                diagnostics.registerFunction(spec.name());
            }

            @Override
            public void onRemove(String functionName) {
                syncQueueService.removeFunctionState(functionName);
                binder.removeFunction(functionName);
                diagnostics.removeFunction(functionName);
            }
        };
    }
}
