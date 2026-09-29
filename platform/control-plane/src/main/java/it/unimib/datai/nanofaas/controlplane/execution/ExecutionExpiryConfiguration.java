package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Scheduler;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Owns cache expiry timers, including physical removal of cancelled deadlines, and the
 * explicit construction of the execution-ownership beans from the mandatory
 * {@code :execution-runtime} library: {@link ExecutionStore} and {@link IdempotencyStore}
 * carry no {@code @Component}/{@code @Autowired} themselves, since that library must not
 * depend on Spring.
 */
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding(ExecutionStoreProperties.class)
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

    /**
     * Binds {@code nanofaas.execution-store} straight into the runtime record; every default and
     * validation lives in its compact constructor.
     */
    @Bean
    public ExecutionStoreProperties executionStoreProperties(Environment environment) {
        return Binder.get(environment).bindOrCreate(
                "nanofaas.execution-store", ExecutionStoreProperties.class);
    }

    /** Report the normalized retention values that the stores actually use. */
    @Bean
    public InfoContributor executionStoreInfoContributor(ExecutionStoreProperties properties) {
        return builder -> builder.withDetail("executionStore", Map.of(
                "ttl", properties.ttl().toString(),
                "syncTtl", properties.syncTtl().toString(),
                "maxLifetime", properties.maxLifetime().toString()));
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
