package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.service.RetryScheduler;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Builds an {@link AttemptCoordinator} with the platform's default retry backoff (100 ms to 2 s). */
public final class AttemptFixtures {
    private AttemptFixtures() {
    }

    public static AttemptCoordinator coordinator(ExecutionStore executionStore, FunctionCapacityRegistry capacity,
                                                 RetryScheduler retry, AttemptTransport transport,
                                                 AttemptObserver observer) {
        return new AttemptCoordinator(executionStore, capacity, retry, transport, observer,
                new RetryBackoff(Duration.ofMillis(100), Duration.ofSeconds(2),
                        () -> ThreadLocalRandom.current().nextDouble()));
    }
}
