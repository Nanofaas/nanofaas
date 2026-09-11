package it.unimib.datai.nanofaas.modules.asyncqueue;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@AutoConfiguration
public class AsyncQueueConfiguration {

    @Bean
    QueueManager queueManager(MeterRegistry meterRegistry, DispatchCapacity capacityRegistry) {
        return new QueueManager(meterRegistry, capacityRegistry);
    }

    /** The governor's capacity knob, backed by the shared core registry (P06). */
    @Bean
    WorkloadCapacityController asyncQueueWorkloadCapacityController(DispatchCapacity capacityRegistry) {
        return capacityRegistry::setEffectiveConcurrency;
    }

    @Bean
    AsyncQueueWorkloadMetricsSource asyncQueueWorkloadMetricsSource(QueueManager queueManager) {
        return new AsyncQueueWorkloadMetricsSource(queueManager);
    }

    @Bean
    WorkloadMetricsBinder asyncQueueWorkloadMetricsBinder(QueueManager queueManager) {
        return queueManager.workloadMetricsBinder();
    }

    @Bean
    Scheduler scheduler(QueueManager queueManager, InvocationDispatch invocationService, QueueLifecycle queueLifecycle) {
        return new Scheduler(queueManager, invocationService, queueLifecycle);
    }

    @Bean
    @Primary
    QueueBackedEnqueuer asyncQueueInvocationEnqueuer(QueueManager queueManager) {
        return new QueueBackedEnqueuer(queueManager);
    }

    @Bean
    FunctionRegistrationListener queueLifecycleListener(QueueManager queueManager, QueueLifecycle executionStore) {
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(it.unimib.datai.nanofaas.common.model.FunctionSpec spec) {
                queueManager.getOrCreate(spec);
            }

            @Override
            public void onRemove(String functionName) {
                for (InvocationTask task : queueManager.remove(functionName)) {
                    executionStore.removed(task);
                }
            }
        };
    }

}
