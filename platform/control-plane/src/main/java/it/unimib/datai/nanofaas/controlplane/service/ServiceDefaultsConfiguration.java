package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ServiceDefaultsConfiguration {

    private static final int RETRY_POOL_CORE_SIZE = 2;
    private static final int RETRY_POOL_MAX_SIZE = 8;
    private static final int RETRY_POOL_QUEUE_CAPACITY = 256;

    /**
     * Default {@link InvocationEnqueuer} when no queue module (async-queue, sync-queue)
     * is loaded: retries still need somewhere to go, so this hands the next attempt to a
     * small bounded pool instead of the {@link InvocationEnqueuer#noOp()} placeholder,
     * whose {@code enqueue} used to throw. See {@link ExecutorBackedInvocationEnqueuer}
     * for why {@code enabled()} stays {@code false} regardless.
     *
     * <p>{@link ExecutionCompletionHandler} is resolved lazily through {@link
     * ObjectProvider}: it also takes an (optional) {@link InvocationEnqueuer} in its own
     * constructor, so eagerly injecting it here would be a circular bean dependency.
     * By the time a retry actually calls {@code dispatch}, the application context has
     * long finished starting, so the handler is there to resolve.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(InvocationEnqueuer.class)
    ExecutorBackedInvocationEnqueuer invocationEnqueuer(ObjectProvider<ExecutionCompletionHandler> completionHandler) {
        return new ExecutorBackedInvocationEnqueuer(
                task -> completionHandler.getObject().dispatch(task),
                SchedulerLifecycleSupport.newBoundedExecutor(
                        "nanofaas-core-retry", RETRY_POOL_CORE_SIZE, RETRY_POOL_MAX_SIZE, RETRY_POOL_QUEUE_CAPACITY));
    }

    @Bean
    FunctionRegistrationListener metricsLifecycleListener(Metrics metrics) {
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(it.unimib.datai.nanofaas.common.model.FunctionSpec spec) {
                metrics.registerFunction(spec.name());
            }

            @Override
            public void onRemove(String functionName) {
                metrics.removeFunction(functionName);
            }
        };
    }
}
