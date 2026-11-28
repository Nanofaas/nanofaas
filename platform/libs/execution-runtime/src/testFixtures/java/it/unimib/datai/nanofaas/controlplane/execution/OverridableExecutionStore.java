package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.concurrent.ForkJoinPool;

/**
 * A base for a test's own {@link ExecutionStore} double, which overrides a method to inject a
 * failure or a pause: the default properties on real time, built through the one production
 * constructor, and known to {@link ExecutionFixtures} like the stores it builds.
 */
public class OverridableExecutionStore extends ExecutionStore {
    public OverridableExecutionStore() {
        this(ExecutionFixtures.keys());
    }

    public OverridableExecutionStore(IdempotencyStore keys) {
        this(new SimpleMeterRegistry(), keys);
    }

    private OverridableExecutionStore(SimpleMeterRegistry registry, IdempotencyStore keys) {
        super(ExecutionFixtures.defaults(), registry, Scheduler.systemScheduler(), Ticker.systemTicker(),
                ForkJoinPool.commonPool(), keys);
        ExecutionFixtures.track(this, registry, keys);
    }
}
