package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.config.RetryProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentReadiness;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionFixtures;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;

/**
 * Builds the invocation services through their one production constructor, filling in for tests
 * what the application context always provides: a retry scheduler, a readiness gate, a capacity
 * registry and the retry timing.
 */
public final class ServiceFixtures {
    /** The input limits the application uses by default. */
    public static final RetainedInputEstimator.Limits INPUT_LIMITS =
            new RetainedInputEstimator.Limits(32, 16_384, 65_536, 64L << 20);

    private ServiceFixtures() {
    }

    // ---- ExecutionCompletionHandler

    public static ExecutionCompletionHandler completionHandler(ExecutionStore store, RetryScheduler retry,
                                                               DispatcherRouter router, Metrics metrics) {
        return completionHandler(store, retry, router, metrics, null, null, null);
    }

    public static ExecutionCompletionHandler completionHandler(ExecutionStore store, RetryScheduler retry,
                                                               DispatcherRouter router, Metrics metrics,
                                                               DeploymentReadiness readiness) {
        return completionHandler(store, retry, router, metrics, readiness, null, null);
    }

    public static ExecutionCompletionHandler completionHandler(ExecutionStore store, RetryScheduler retry,
                                                               DispatcherRouter router, Metrics metrics,
                                                               DeploymentReadiness readiness,
                                                               FunctionCapacityRegistry capacity) {
        return completionHandler(store, retry, router, metrics, readiness, capacity, null);
    }

    /** Nulls stand for what the application context provides when nothing replaces it. */
    public static ExecutionCompletionHandler completionHandler(ExecutionStore store, RetryScheduler retry,
                                                               DispatcherRouter router, Metrics metrics,
                                                               DeploymentReadiness readiness,
                                                               FunctionCapacityRegistry capacity,
                                                               RetryProperties retryProperties) {
        return new ExecutionCompletionHandler(store,
                retry == null ? RetryScheduler.unavailable() : retry,
                router, metrics,
                readiness == null ? DeploymentReadiness.immediate() : readiness,
                capacity == null ? new FunctionCapacityRegistry() : capacity,
                retryProperties == null ? new RetryProperties(null, null) : retryProperties);
    }

    // ---- InvocationExecutionFactory

    /**
     * A factory with its own capacity, as the application's before any function is registered:
     * the named functions are registered with it, as their registration listener would.
     */
    public static InvocationExecutionFactory factory(ExecutionStore store, IdempotencyStore keys, Metrics metrics,
                                                     String... functions) {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        for (String function : functions) {
            generations.register(function, 1);
        }
        InvocationCapacity capacity = new InvocationCapacity(generations, 100_000, 10_000, 1L << 30, 256L << 20, 64);
        return factory(store, keys, metrics, capacity, INPUT_LIMITS);
    }

    /** The key store must be the one {@code store} settles keys into, or dedup would split in two. */
    @SuppressWarnings("ReferenceEquality") // the same instance is the requirement
    public static InvocationExecutionFactory factory(ExecutionStore store, IdempotencyStore keys, Metrics metrics,
                                                     InvocationCapacity capacity, RetainedInputEstimator.Limits limits) {
        IdempotencyStore settledInto = ExecutionFixtures.keysOf(store);
        if (settledInto != null && settledInto != keys) {
            throw new IllegalArgumentException("the factory must claim keys in the store's own key store");
        }
        return new InvocationExecutionFactory(store, keys, metrics, capacity, limits);
    }

    /** A factory on the key store {@code store} was built with. */
    public static InvocationExecutionFactory factory(ExecutionStore store, Metrics metrics,
                                                     InvocationCapacity capacity, RetainedInputEstimator.Limits limits) {
        IdempotencyStore keys = ExecutionFixtures.keysOf(store);
        if (keys == null) {
            throw new IllegalArgumentException("store not built by ExecutionFixtures: pass its key store");
        }
        return new InvocationExecutionFactory(store, keys, metrics, capacity, limits);
    }
}
