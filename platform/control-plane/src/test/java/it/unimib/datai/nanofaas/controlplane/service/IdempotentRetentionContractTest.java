package it.unimib.datai.nanofaas.controlplane.service;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The A5 retention contract, exercised end-to-end through the factory with a
 * controllable clock: the key stays bound to its execution for the execution's
 * whole life, terminal retention starts at completion, and a replay within the
 * window never re-acquires the key - even when the payload was evicted for
 * capacity (in which case it reads as "gone" instead of re-invoking).
 */
class IdempotentRetentionContractTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(30);
    private static final Duration SYNC_TTL = Duration.ofSeconds(30);

    private final AtomicLong clock = new AtomicLong();
    private final Ticker ticker = clock::get;

    private ExecutionStore executions;
    private IdempotencyStore keys;
    private InvocationExecutionFactory factory;

    @BeforeEach
    void setUp() {
        clock.set(0);
        rebuild(ExecutionStoreProperties.of(TTL, MAX_LIFETIME, SYNC_TTL), 100_000);
    }

    private void rebuild(ExecutionStoreProperties props, long maxKeys) {
        ExecutionStoreProperties keyProps = new ExecutionStoreProperties(
                props.ttl(), props.maxLifetime(), props.syncTtl(), props.maxOutcomes(), maxKeys,
                props.maxOutcomeBytes());
        this.executions = new ExecutionStore(props, ticker);
        this.keys = new IdempotencyStore(keyProps, ticker);
        this.factory = new InvocationExecutionFactory(executions, keys, new Metrics(new SimpleMeterRegistry()));
    }

    private void advance(Duration duration) {
        clock.addAndGet(duration.toNanos());
    }

    private static FunctionSpec spec() {
        return new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
    }

    private static InvocationRequest request() {
        return new InvocationRequest("payload", Map.of());
    }

    @Test
    void aKeyOutlivesALongExecutionForItsTerminalWindowThenAllowsANewExecution() {
        InvocationExecutionFactory.ExecutionLookup lookup =
                factory.createOrReuseExecution("fn", spec(), request(), "k", "trace-1", InvocationKind.SYNC);
        String executionId = lookup.executionRecord().executionId();
        lookup.publishAdmission();

        // Long execution: 29 minutes in flight, just under maxLifetime.
        advance(Duration.ofMinutes(29));
        lookup.executionRecord().markSuccess("done");
        executions.settle(lookup.executionRecord());

        // t=31m: past the old max(ttl, maxLifetime)=30m-from-publication window that
        // would have dropped the key, but inside terminal retention (29m + 5m = 34m).
        advance(Duration.ofMinutes(2));
        InvocationExecutionFactory.ExecutionLookup replay =
                factory.createOrReuseExecution("fn", spec(), request(), "k", "trace-2", InvocationKind.SYNC);
        assertThat(replay.isNew()).isFalse();
        assertThat(replay.gone()).isFalse();
        assertThat(replay.settledExecutionId()).isEqualTo(executionId);
        assertThat(replay.settledOutcome().output()).isEqualTo("done");

        // t=34m+1s: after the documented expiry, the same key starts a new execution.
        advance(Duration.ofMinutes(3).plusSeconds(1));
        InvocationExecutionFactory.ExecutionLookup fresh =
                factory.createOrReuseExecution("fn", spec(), request(), "k", "trace-3", InvocationKind.SYNC);
        assertThat(fresh.isNew()).isTrue();
        assertThat(fresh.executionRecord().executionId()).isNotEqualTo(executionId);
    }

    @Test
    void theKeyStaysBoundAcrossInternalRetries() {
        InvocationExecutionFactory.ExecutionLookup lookup =
                factory.createOrReuseExecution("fn", spec(), request(), "k", "trace-1", InvocationKind.SYNC);
        String executionId = lookup.executionRecord().executionId();
        lookup.publishAdmission();

        // The retry replaces the task with one whose key is null; the record must still
        // remember the key it was admitted under so the terminal transition can find it.
        ExecutionRecord record = lookup.executionRecord();
        record.markRunning();
        record.resetForRetry(new InvocationTask(executionId, "fn", spec(), request(), null,
                "trace-1", Instant.now(), 2, InvocationKind.SYNC));
        record.markSuccess("done");
        executions.settle(record);

        InvocationExecutionFactory.ExecutionLookup replay =
                factory.createOrReuseExecution("fn", spec(), request(), "k", "trace-2", InvocationKind.SYNC);
        assertThat(replay.isNew()).isFalse();
        assertThat(replay.gone()).isFalse();
        assertThat(replay.settledExecutionId()).isEqualTo(executionId);
        assertThat(replay.settledOutcome().output()).isEqualTo("done");
    }

    @Test
    void capacityEvictionLeavesABindingThatRepliesGoneInsteadOfReinvoking() {
        // A byte budget too small for any outcome payload: nothing is retained, which is
        // exactly the scenario under test — the binding outlives the payload. Expressing it
        // as "one slot" made the test depend on which entry Caffeine picks as the victim,
        // and with a weighted budget it can reject the newcomer instead of evicting the
        // incumbent. That is Caffeine's business; A5's guarantee is not.
        rebuild(new ExecutionStoreProperties(TTL, MAX_LIFETIME, SYNC_TTL, 1, 100_000, 1), 100_000);

        InvocationExecutionFactory.ExecutionLookup first =
                factory.createOrReuseExecution("fn", spec(), request(), "k1", "trace-1", InvocationKind.SYNC);
        String firstId = first.executionRecord().executionId();
        first.publishAdmission();
        first.executionRecord().markSuccess("gone-soon");
        executions.settle(first.executionRecord());

        assertThat(executions.size()).isZero();
        assertThat(executions.outcomeOf(firstId)).isNull();

        InvocationExecutionFactory.ExecutionLookup replay =
                factory.createOrReuseExecution("fn", spec(), request(), "k1", "trace-3", InvocationKind.SYNC);
        assertThat(replay.isNew()).isFalse();
        assertThat(replay.gone()).isTrue();
        assertThat(replay.settledExecutionId()).isEqualTo(firstId);
    }

    @Test
    void capacityEvictionRepliesGoneEvenWhenTheExecutionSettledBeforeItsClaimWasPublished() {
        // Production ordering, not the convenience ordering the other tests use:
        // InvocationEnqueueSupport.admitIfNew runs the admission action FIRST and
        // publishes the claim after it returns. In the no-queue profile the dispatch
        // runs inline on the admitting thread, so the record is already terminal by
        // the time publishAdmission() is reached and the terminal listener has
        // already fired against a still-pending key.
        rebuild(new ExecutionStoreProperties(TTL, MAX_LIFETIME, SYNC_TTL, 1, 100_000, 1), 100_000);

        InvocationExecutionFactory.ExecutionLookup first =
                factory.createOrReuseExecution("fn", spec(), request(), "k1", "trace-1", InvocationKind.SYNC);
        String firstId = first.executionRecord().executionId();
        first.executionRecord().markSuccess("gone-soon");
        executions.settle(first.executionRecord());
        first.publishAdmission();

        assertThat(executions.size()).isZero();
        assertThat(executions.outcomeOf(firstId)).isNull();

        InvocationExecutionFactory.ExecutionLookup replay =
                factory.createOrReuseExecution("fn", spec(), request(), "k1", "trace-3", InvocationKind.SYNC);
        assertThat(replay.isNew())
                .as("a replay past capacity eviction must never re-invoke the function")
                .isFalse();
        assertThat(replay.gone()).isTrue();
        assertThat(replay.settledExecutionId()).isEqualTo(firstId);
    }

    @Test
    void aLateSettleOfAReplacedExecutionDoesNotMarkTheCurrentBindingTerminal() {
        // The first execution is abandoned after publishing (admission failed late),
        // so a replay legitimately re-claims the key for a NEW execution. The first
        // record settling afterwards must not flip the new binding to terminal:
        // terminal retention would then start at the wrong instant and never restart.
        InvocationExecutionFactory.ExecutionLookup first =
                factory.createOrReuseExecution("fn", spec(), request(), "k", "trace-1", InvocationKind.SYNC);
        first.publishAdmission();
        ExecutionRecord abandoned = first.executionRecord();
        // An explicit abandon after publication is what makes the binding reclaimable;
        // the mere absence of the record is not (finding R2).
        first.abandonAdmission();

        InvocationExecutionFactory.ExecutionLookup second =
                factory.createOrReuseExecution("fn", spec(), request(), "k", "trace-2", InvocationKind.SYNC);
        assertThat(second.isNew()).isTrue();
        second.publishAdmission();

        abandoned.markSuccess("late");
        executions.settle(abandoned);

        assertThat(keys.acquireOrGet("fn", "k").terminal())
                .as("a settle from a replaced execution must not mark the live binding terminal")
                .isFalse();
    }

    @Test
    void anExhaustedKeyBudgetRejectsNewKeyedAdmissionsBeforeDispatchAndKeepsReplaysServiceable() {
        rebuild(ExecutionStoreProperties.of(TTL, MAX_LIFETIME, SYNC_TTL), 1);

        InvocationExecutionFactory.ExecutionLookup first =
                factory.createOrReuseExecution("fn", spec(), request(), "k1", "trace-1", InvocationKind.SYNC);
        first.publishAdmission();

        assertThatThrownBy(() -> factory.createOrReuseExecution("fn", spec(), request(), "k2", "trace-2", InvocationKind.SYNC))
                .isInstanceOf(IdempotencyBudgetExhaustedException.class);

        // The existing key still serves its replay.
        InvocationExecutionFactory.ExecutionLookup replay =
                factory.createOrReuseExecution("fn", spec(), request(), "k1", "trace-3", InvocationKind.SYNC);
        assertThat(replay.isNew()).isFalse();
        assertThat(replay.executionRecord().executionId()).isEqualTo(first.executionRecord().executionId());
    }

    @Test
    void concurrentReplaysOfASettledKeyAllGetTheSameExecutionWithoutNewOnes() throws Exception {
        InvocationExecutionFactory.ExecutionLookup lookup =
                factory.createOrReuseExecution("fn", spec(), request(), "k", "trace-1", InvocationKind.SYNC);
        String executionId = lookup.executionRecord().executionId();
        lookup.publishAdmission();
        lookup.executionRecord().markSuccess("done");
        executions.settle(lookup.executionRecord());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<InvocationExecutionFactory.ExecutionLookup>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() ->
                        factory.createOrReuseExecution("fn", spec(), request(), "k", "trace-replay", InvocationKind.SYNC)));
            }
            for (Future<InvocationExecutionFactory.ExecutionLookup> future : futures) {
                InvocationExecutionFactory.ExecutionLookup replay = future.get(5, TimeUnit.SECONDS);
                assertThat(replay.isNew()).isFalse();
                assertThat(replay.settledExecutionId()).isEqualTo(executionId);
                assertThat(replay.settledOutcome().output()).isEqualTo("done");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
