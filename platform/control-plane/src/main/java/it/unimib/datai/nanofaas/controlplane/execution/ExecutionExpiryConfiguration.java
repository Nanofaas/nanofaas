package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Scheduler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledThreadPoolExecutor;

/** Owns cache expiry timers, including physical removal of cancelled deadlines. */
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
}
