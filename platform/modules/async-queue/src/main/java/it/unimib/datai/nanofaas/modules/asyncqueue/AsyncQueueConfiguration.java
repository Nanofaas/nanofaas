package it.unimib.datai.nanofaas.modules.asyncqueue;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Primary;

@AutoConfiguration
public class AsyncQueueConfiguration {
    private static final String FUNCTION_REMOVED = "FUNCTION_REMOVED";

    @Bean
    FunctionCapacityRegistry asyncQueueCapacityRegistry() {
        return new FunctionCapacityRegistry();
    }

    @Bean
    QueueManager queueManager(MeterRegistry meterRegistry, FunctionCapacityRegistry capacityRegistry) {
        return new QueueManager(meterRegistry, capacityRegistry);
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
    Scheduler scheduler(QueueManager queueManager, InvocationService invocationService) {
        return new Scheduler(queueManager, invocationService);
    }

    @Bean
    @Primary
    InvocationEnqueuer asyncQueueInvocationEnqueuer(QueueManager queueManager) {
        return new QueueBackedEnqueuer(queueManager);
    }


    @Bean
    FunctionRegistrationListener queueLifecycleListener(QueueManager queueManager, ExecutionStore executionStore) {
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(it.unimib.datai.nanofaas.common.model.FunctionSpec spec) {
                queueManager.getOrCreate(spec);
            }

            @Override
            public void onRemove(String functionName) {
                for (InvocationTask task : queueManager.remove(functionName)) {
                    markFunctionRemoved(executionStore, functionName, task);
                }
            }
        };
    }

    private static void markFunctionRemoved(ExecutionStore executionStore, String functionName, InvocationTask task) {
        ExecutionRecord executionRecord = executionStore.getOrNull(task.executionId());
        if (executionRecord == null) {
            return;
        }
        InvocationResult result = InvocationResult.error(
                FUNCTION_REMOVED,
                "Function '%s' was removed before queued execution could run".formatted(functionName)
        );
        ErrorInfo error = result.error();
        synchronized (executionRecord) {
            // An already-terminal record must not make this an early return that skips
            // the settle (finding R4, applied to the queue-side terminal early returns):
            // the already-definitive result prevails, and the settle below is idempotent.
            if (!executionRecord.isTerminal()) {
                executionRecord.markError(error);
                executionRecord.completion().complete(result);
            }
        }
        executionStore.settle(executionRecord);
    }
}
