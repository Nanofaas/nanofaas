package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Ticker;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for finding R7 of the 2026-09-08 pre-soak review.
 *
 * <p>{@link IdempotencyStore#acquireOrGet} checks {@code size() >= maxKeys} and then
 * inserts into a concurrent map. The check and the claim are not one atomic
 * reservation, so distinct keys can all observe spare capacity before any of them is
 * inserted. The review's reproduction holds every claimant at a barrier placed after
 * the real {@code size()} read, with {@code maxKeys = 1}: all 16 claimants observe a
 * zero-sized store and then all 16 claim.
 *
 * <p>Correct behavior (plan task P03): capacity is reserved atomically before a new
 * binding is claimed, so a configured budget of one admits exactly one concurrent
 * new key.
 *
 * <p>This test asserts the desired behavior, so it is RED on the current baseline,
 * where all 16 claims are accepted.
 */
class R7KeyBudgetAtomicityRegressionTest {

    @Test
    void concurrentDistinctClaimsNeverExceedTheConfiguredKeyBudget() throws Exception {
        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutionStoreProperties props = new ExecutionStoreProperties(null, null, null, 100, 1, 11_600);
        IdempotencyStore keys = new IdempotencyStore(props, Ticker.systemTicker()) {
            @Override
            public int size() {
                // Pause every claimant after the real size read and before its insert, so
                // each of them observes the same pre-insert spare capacity. This models a
                // legal scheduling interleaving; it is not a sleep-based guess.
                int observed = super.size();
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("barrier interrupted while synchronizing key claims", e);
                }
                return observed;
            }
        };

        List<Future<IdempotencyStore.AcquireResult>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                int n = i;
                futures.add(executor.submit(() -> keys.acquireOrGet("fn", "key" + n)));
            }
            int claimed = 0;
            for (Future<IdempotencyStore.AcquireResult> future : futures) {
                IdempotencyStore.AcquireResult result = future.get(5, TimeUnit.SECONDS);
                if (result.state() == IdempotencyStore.AcquireResult.State.CLAIMED) {
                    claimed++;
                }
            }
            // With maxKeys = 1 the overshoot must be impossible: at most one new key is
            // admitted, whatever the interleaving. The baseline admits all 16 and fails here.
            assertThat(claimed)
                    .as("concurrent distinct key claims must not exceed maxKeys = 1")
                    .isEqualTo(1);
        }
    }
}
