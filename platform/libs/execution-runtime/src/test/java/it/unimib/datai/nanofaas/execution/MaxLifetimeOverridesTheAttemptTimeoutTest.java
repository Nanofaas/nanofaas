package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.execution.ExecutionFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.github.benmanes.caffeine.cache.Ticker;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.RetryScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The execution store evicts a live record {@code maxLifetime} after it was admitted, and the
 * eviction concludes it as {@code EXECUTION_EXPIRED}. Nothing compares that lifetime with the
 * function's own timeout, so a run well inside its timeout is still cut, and the answer it then
 * delivers is discarded. This is the consequence registration must prevent
 * ({@code ExecutionLifetimeBudgetHttpTest} in the control plane).
 */
class MaxLifetimeOverridesTheAttemptTimeoutTest {

    private static final Duration MAX_LIFETIME = Duration.ofMinutes(30);
    private static final int TIMEOUT_MS = (int) Duration.ofMinutes(40).toMillis();

    private final AtomicLong clock = new AtomicLong();
    private final Ticker ticker = clock::get;

    @Test
    void aRunInsideItsOwnTimeoutIsCutAtMaxLifetimeAndItsAnswerIsDiscarded() throws Exception {
        var properties = ExecutionFixtures.properties(Duration.ofMinutes(5), MAX_LIFETIME, Duration.ofSeconds(30));
        var store = ExecutionFixtures.store(properties, ticker);

        var answer = new CompletableFuture<DispatchResult>();
        AttemptTransport transport = task -> new AttemptHandle(
                answer, answer.thenApply(ignored -> null), new CompletableFuture<Void>());
        var capacity = new FunctionCapacityRegistry();
        capacity.register("fn", 1);
        var coordinator = AttemptFixtures.coordinator(store, capacity,
                RetryScheduler.unavailable(), transport, mock(AttemptObserver.class));

        var spec = new FunctionSpec("fn", "image", null, null, null,
                TIMEOUT_MS, 1, 10, 0, "http://function", ExecutionMode.EXTERNAL, null, null, null);
        var task = new InvocationTask("long-run", "fn", spec, new InvocationRequest("input", Map.of()),
                null, null, Instant.now(), 1, InvocationKind.SYNC);
        var executionRecord = ExecutionFixtures.record("long-run", task, capacity);
        store.put(executionRecord);
        coordinator.dispatchDirect(task);

        // Minute 31 of a run allowed 40: the store evicts the record and concludes it.
        clock.addAndGet(MAX_LIFETIME.plusMinutes(1).toNanos());
        ExecutionFixtures.inFlight(store);
        InvocationResult result = executionRecord.completion().get(5, TimeUnit.SECONDS);
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo("EXECUTION_EXPIRED");

        // Minute 35: the function answers, successfully and inside its timeout. Nobody keeps it.
        answer.complete(DispatchResult.warm(InvocationResult.success("done")));
        assertThat(store.outcomeOf("long-run").state()).isEqualTo(ExecutionState.ERROR);
        assertThat(store.outcomeOf("long-run").error().code()).isEqualTo("EXECUTION_EXPIRED");
    }
}
