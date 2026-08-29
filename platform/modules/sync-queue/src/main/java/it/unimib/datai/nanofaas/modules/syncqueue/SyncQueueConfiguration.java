package it.unimib.datai.nanofaas.modules.syncqueue;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.modules.syncqueue.scheduler.SyncScheduler;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueMetrics;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadDiagnostics;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Primary;

@AutoConfiguration
@EnableConfigurationProperties(SyncQueueProperties.class)
public class SyncQueueConfiguration {

    @Bean
    SyncQueueMetrics syncQueueMetrics(MeterRegistry meterRegistry) {
        return new SyncQueueMetrics(meterRegistry);
    }

    @Bean
    FunctionCapacityRegistry syncQueueCapacityRegistry() {
        return new FunctionCapacityRegistry();
    }

    @Bean
    WorkloadDiagnostics syncQueueWorkloadDiagnostics(MeterRegistry meterRegistry) {
        return new WorkloadDiagnostics(meterRegistry);
    }

    @Bean
    SyncQueueWorkloadMetricsSource syncQueueWorkloadMetricsSource(
            SyncQueueService syncQueueService, FunctionCapacityRegistry capacityRegistry) {
        return new SyncQueueWorkloadMetricsSource(syncQueueService, capacityRegistry);
    }

    @Bean
    WorkloadMetricsBinder syncQueueWorkloadMetricsBinder(MeterRegistry meterRegistry,
                                                         SyncQueueWorkloadMetricsSource source) {
        return new WorkloadMetricsBinder(meterRegistry, source);
    }

    @Bean
    @Primary
    SyncQueueInvocationEnqueuer syncQueueInvocationEnqueuer(FunctionCapacityRegistry capacityRegistry,
                                                             WorkloadDiagnostics diagnostics,
                                                             SyncQueueService syncQueueService) {
        return new SyncQueueInvocationEnqueuer(capacityRegistry, diagnostics,
                syncQueueService::onDispatchSlotReleased);
    }

    @Bean
    SyncQueueService syncQueueService(SyncQueueProperties props,
                                      ExecutionStore executionStore,
                                      SyncQueueMetrics metrics,
                                      SyncQueueConfigSource configSource,
                                      FunctionCapacityRegistry capacityRegistry,
                                      WorkloadDiagnostics diagnostics) {
        return new SyncQueueService(props, executionStore, metrics, configSource,
                capacityRegistry, diagnostics);
    }

    @Bean("mutableSyncQueueConfigSource")
    @Primary
    MutableSyncQueueConfigSource syncQueueConfigSource(SyncQueueProperties props) {
        return new MutableSyncQueueConfigSource(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "sync-queue", name = "enabled", havingValue = "true")
    SyncScheduler syncScheduler(InvocationEnqueuer enqueuer,
                                SyncQueueService syncQueueService,
                                InvocationService invocationService,
                                WorkloadDiagnostics diagnostics) {
        return new SyncScheduler(enqueuer, syncQueueService, invocationService, diagnostics);
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
