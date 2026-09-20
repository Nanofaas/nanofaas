package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Scheduler;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreBindingProperties;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Owns cache expiry timers, including physical removal of cancelled deadlines, and the
 * explicit construction of the execution-ownership beans extracted into the mandatory
 * {@code :execution-runtime} library (issue #208, Task 9): {@link ExecutionStore} and
 * {@link IdempotencyStore} no longer carry {@code @Component}/{@code @Autowired} themselves,
 * since that library must not depend on Spring.
 */
@Configuration(proxyBeanMethods = false)
public class ExecutionExpiryConfiguration {
    @Bean(name = "executionExpiryExecutor", destroyMethod = "shutdownNow")
    public ScheduledThreadPoolExecutor executionExpiryExecutor() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "execution-expiry");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    @Bean("executionExpiryScheduler")
    public Scheduler executionExpiryScheduler(
            @Qualifier("executionExpiryExecutor") ScheduledThreadPoolExecutor executor) {
        // The returned Future is the actual scheduled task, unlike systemScheduler's
        // CompletableFuture wrapping a delayed submission. Caffeine cancellation must
        // remove that submission immediately rather than retain it until maxLifetime.
        // The timer only submits maintenance to Caffeine's executor; it does not run it.
        return Scheduler.forScheduledExecutorService(executor);
    }

    /** The runtime record, produced from the mutable Spring binding target. */
    @Bean
    public ExecutionStoreProperties executionStoreProperties(ExecutionStoreBindingProperties binding) {
        return binding.toRuntime();
    }

    /** Explicit construction: {@link ExecutionStore} carries no Spring annotations of its own. */
    @Bean
    public ExecutionStore executionStore(ExecutionStoreProperties properties, MeterRegistry registry,
            @Qualifier("executionExpiryScheduler") Scheduler scheduler) {
        return new ExecutionStore(properties, registry, scheduler);
    }

    /**
     * Explicit construction: {@link IdempotencyStore} carries no Spring annotations of its own.
     * {@code destroyMethod = "clear"} preserves exactly the shutdown contract its former
     * {@code @PreDestroy} gave it — draining the store and returning every reserved slot.
     */
    @Bean(destroyMethod = "clear")
    public IdempotencyStore idempotencyStore(ExecutionStoreProperties properties, MeterRegistry registry) {
        return new IdempotencyStore(properties, registry);
    }
}
