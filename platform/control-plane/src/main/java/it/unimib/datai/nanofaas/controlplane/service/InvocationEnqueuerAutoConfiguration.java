package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * The core fallback {@link InvocationEnqueuer}, used when no queue module is loaded.
 *
 * <p>This lives in an auto-configuration at the lowest precedence rather than in a
 * component-scanned {@code @Configuration} for one reason: {@code @ConditionalOnMissingBean}
 * only sees what is already registered when it is evaluated, and component-scanned
 * configurations are processed BEFORE any auto-configuration. The queue modules publish their
 * enqueuers from {@code @AutoConfiguration} classes, so the condition never saw them and both
 * beans were registered in every queue profile — harmless only because each module also marks
 * its own {@code @Primary}. A future module that forgot that annotation would have failed the
 * context with {@code NoUniqueBeanDefinitionException}, and meanwhile every queue profile
 * carried a dead retry pool with a destroy method.
 *
 * <p>Ordering last, rather than naming the known modules in {@code afterName}, keeps this
 * general: a queue module added later needs no change here.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
public class InvocationEnqueuerAutoConfiguration {

    private static final int RETRY_POOL_CORE_SIZE = 2;
    private static final int RETRY_POOL_MAX_SIZE = 8;
    private static final int RETRY_POOL_QUEUE_CAPACITY = 256;

    /** No initial queue or async capability when a provider only offers retries. */
    @Bean
    @ConditionalOnMissingBean(InvocationEnqueuer.class)
    InvocationEnqueuer invocationAdmission() { return InvocationEnqueuer.noOp(); }

    /**
     * Resolve dispatch lazily: the completion handler itself consumes RetryScheduler.
     * A real queue retry provider suppresses this bean, so no dead retry pool is created.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RetryScheduler.class)
    ExecutorBackedInvocationEnqueuer invocationEnqueuer(ObjectProvider<ExecutionCompletionHandler> completionHandler,
                                                       FunctionCapacityRegistry capacityRegistry) {
        return new ExecutorBackedInvocationEnqueuer(
                task -> completionHandler.getObject().dispatch(task),
                capacityRegistry,
                SchedulerLifecycleSupport.newBoundedExecutor(
                        "nanofaas-core-retry", RETRY_POOL_CORE_SIZE, RETRY_POOL_MAX_SIZE, RETRY_POOL_QUEUE_CAPACITY));
    }
}
