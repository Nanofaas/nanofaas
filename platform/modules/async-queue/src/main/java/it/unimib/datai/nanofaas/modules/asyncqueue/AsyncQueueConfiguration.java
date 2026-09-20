package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registers only this module's strategy factory (Task 8, issue #208): the worker, capacity
 * registration listener, metrics source/binder and {@code InvocationEnqueuer} this module used
 * to publish on its own now live once, in {@code SchedulerConfiguration}, shared with the
 * sync-queue module instead of being mutually exclusive with it.
 */
@AutoConfiguration
public class AsyncQueueConfiguration {

    @Bean
    SchedulingStrategy perFunctionStrategy() {
        return new PerFunctionSchedulingStrategy();
    }
}
