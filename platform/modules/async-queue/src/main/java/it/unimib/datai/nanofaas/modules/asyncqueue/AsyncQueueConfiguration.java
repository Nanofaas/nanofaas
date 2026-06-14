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
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@ConditionalOnBean({MeterRegistry.class, InvocationService.class})
public class AsyncQueueConfiguration {
    private static final String FUNCTION_REMOVED = "FUNCTION_REMOVED";

    @Bean
    QueueManager queueManager(MeterRegistry meterRegistry) {
        return new QueueManager(meterRegistry);
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
    @Primary
    ScalingMetricsSource asyncQueueScalingMetricsSource(QueueManager queueManager) {
        return new QueueBackedMetricsSource(queueManager);
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
        ExecutionRecord record = executionStore.getOrNull(task.executionId());
        if (record == null) {
            return;
        }
        InvocationResult result = InvocationResult.error(
                FUNCTION_REMOVED,
                "Function '%s' was removed before queued execution could run".formatted(functionName)
        );
        ErrorInfo error = result.error();
        synchronized (record) {
            if (record.isTerminal()) {
                return;
            }
            record.markError(error);
            record.completion().complete(result);
        }
    }
}
