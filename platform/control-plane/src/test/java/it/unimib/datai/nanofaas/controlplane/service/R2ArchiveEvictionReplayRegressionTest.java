package it.unimib.datai.nanofaas.controlplane.service;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for finding R2 of the 2026-09-08 pre-soak review.
 *
 * <p>{@link ExecutionStore#settle} publishes the outcome, invalidates the live record,
 * and only then notifies the terminal listeners; the factory's listener is what moves
 * the idempotency key to its terminal (tombstone) binding. When an oversized outcome is
 * evicted for capacity, there is a window after the live record is invalidated and
 * before the key listener runs in which {@link InvocationExecutionFactory} finds
 * neither a live record nor an outcome, sees a still-published key, and re-claims it —
 * authorising a second dispatch for a function that already ran.
 *
 * <p>Correct behavior (plan task P04): the key binding becomes non-reclaimable before
 * the last live reference can disappear. A replay arriving inside that window must NOT
 * observe a new execution.
 *
 * <p>This test pauses a terminal listener exactly at that boundary (after real archive
 * and live invalidation, before the key's terminal notification), then performs a real
 * factory lookup. It asserts the desired behavior, so it is RED on the current baseline,
 * where the replay is admitted as a new execution.
 */
class R2ArchiveEvictionReplayRegressionTest {

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                10000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
    }

    private static InvocationExecutionFactory.ExecutionLookup lookup(
            InvocationExecutionFactory factory, String key) {
        return factory.createOrReuseExecution("fn", spec("fn"),
                new InvocationRequest("payload", Map.of()), key, null, InvocationKind.SYNC);
    }

    @Test
    void replayDuringOutcomeEvictionAndArchiveDoesNotBecomeANewExecution() throws Exception {
        // An outcome budget so small that the 1000-byte payload is evicted immediately.
        ExecutionStoreProperties props = new ExecutionStoreProperties(null, null, null, 100, 100, 100);
        ExecutionStore store = new ExecutionStore(props, Ticker.systemTicker());
        IdempotencyStore keys = new IdempotencyStore(props, Ticker.systemTicker());

        CountDownLatch archived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Pause at the actual boundary: after outcome publication and live-record
        // invalidation, but before the factory's key-terminal listener runs.
        store.onTerminal(record -> {
            store.size();
            archived.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to resume terminal listener");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted while pausing terminal listener", e);
            }
        });

        InvocationExecutionFactory factory =
                new InvocationExecutionFactory(store, keys, new Metrics(new SimpleMeterRegistry()));
        InvocationExecutionFactory.ExecutionLookup first = lookup(factory, "race");
        first.publishAdmission(); // The first dispatch is still pending; the key is published.

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> settling = executor.submit(() -> {
                first.executionRecord().markSuccess("x".repeat(1000));
                store.settle(first.executionRecord());
            });

            if (!archived.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("terminal listener did not reach the archive boundary");
            }

            // A replay arriving now must not re-run the function: the outcome is evicted and
            // the live record is gone, but the key is mid-terminal-transition. The baseline
            // re-claims the published key and reports a new execution.
            InvocationExecutionFactory.ExecutionLookup replay = lookup(factory, "race");
            boolean replayIsNew = replay.isNew();
            release.countDown();
            settling.get(5, TimeUnit.SECONDS);

            assertThat(replayIsNew)
                    .as("replay during the terminal transition must not authorise a second execution")
                    .isFalse();
            if (replayIsNew) {
                // Clean up the duplicate admission the defect created, so no pending claim or
                // orphaned record survives the test.
                replay.abandonAdmission();
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
