package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The idempotency-key budget ({@code maxKeys}) is an atomic reservation, not a
 * check-then-insert: every entry owns exactly one slot, released exactly once when
 * the entry leaves the cache (finding R7).
 *
 * <p>These tests force the interleavings the reservation must survive - concurrent
 * claims of one key, concurrent abandon and expiry, saturation replay, in-place
 * replacement and shutdown drain - and time-bound every concurrent case with a
 * latch/barrier and a bounded {@code Future.get}, never a sleep as proof of ordering.
 */
class IdempotencyKeyBudgetTest {

    private final AtomicLong clock = new AtomicLong();
    private final Ticker ticker = clock::get;

    private ExecutionStoreProperties props(long maxKeys) {
        return new ExecutionStoreProperties(
                Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofSeconds(30),
                100, maxKeys, 11_600);
    }

    /**
     * Expired entries are evicted by maintenance - this thread's {@code cleanUp()} or
     * the cache's scheduler - and release their slot from the removal listener. The
     * eviction is not guaranteed to happen on the first {@code size()} call when the
     * scheduler is involved, so force maintenance until the store drains, bounded in
     * time. Never a sleep as proof of ordering: the loop only spins while entries
     * remain, and returns immediately once they are gone.
     */
    private static void drainExpired(IdempotencyStore store) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.size() != 0 && System.nanoTime() < deadline) {
            // size() runs cleanUp(); repeat until the expired entries have left.
        }
    }

    @Test
    void concurrentClaimsOfTheSameKeyProduceASingleOwner() throws Exception {
        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        IdempotencyStore store = new IdempotencyStore(props(1), ticker);

        List<Future<IdempotencyStore.AcquireResult>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return store.acquireOrGet("fn", "same-key");
                }));
            }
            int claimed = 0;
            for (Future<IdempotencyStore.AcquireResult> future : futures) {
                IdempotencyStore.AcquireResult result = future.get(5, TimeUnit.SECONDS);
                if (result.state() == IdempotencyStore.AcquireResult.State.CLAIMED) {
                    claimed++;
                }
            }
            assertThat(claimed).as("one key has one owner").isEqualTo(1);
            assertThat(store.occupied()).as("a single owner holds a single slot").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentAbandonAndExpiryReleaseEachSlotExactlyOnceAndPermitNewClaims() throws Exception {
        int n = 32;
        IdempotencyStore store = new IdempotencyStore(props(n), ticker);
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            IdempotencyStore.AcquireResult result = store.acquireOrGet("fn", "k" + i);
            assertThat(result.state()).isEqualTo(IdempotencyStore.AcquireResult.State.CLAIMED);
            tokens.add(result.executionIdOrToken());
        }
        assertThat(store.occupied()).isEqualTo(n);

        // Half the keys are abandoned (explicit remove), the other half expire (ticker
        // advance + maintenance). A sampler races the releasers and records the lowest
        // occupancy it ever sees: however the releases interleave, the quota never reads
        // negative, and every slot is released exactly once.
        AtomicLong minObserved = new AtomicLong(Long.MAX_VALUE);
        int poolSize = n + 1;
        CyclicBarrier barrier = new CyclicBarrier(poolSize);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    if (index % 2 == 0) {
                        store.abandonClaim("fn", "k" + index, tokens.get(index));
                    } else {
                        // Expire everything; the odd keys leave via maintenance, not abandon.
                        clock.addAndGet(Duration.ofMinutes(31).toNanos());
                        store.size();
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < 100_000; i++) {
                    long observed = store.occupied();
                    if (observed < minObserved.get()) {
                        minObserved.set(observed);
                    }
                }
                return null;
            }));
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(minObserved.get()).as("the quota never goes negative").isGreaterThanOrEqualTo(0);
        drainExpired(store);
        assertThat(store.occupied()).as("every slot is released after abandon and expiry").isZero();
        IdempotencyStore.AcquireResult fresh = store.acquireOrGet("fn", "fresh");
        assertThat(fresh.state())
                .as("a released slot admits a new claim")
                .isEqualTo(IdempotencyStore.AcquireResult.State.CLAIMED);
    }

    @Test
    void replayOfAPresentKeyWorksAtSaturation() {
        IdempotencyStore store = new IdempotencyStore(props(1), ticker);
        IdempotencyStore.AcquireResult first = store.acquireOrGet("fn", "k1");
        assertThat(first.state()).isEqualTo(IdempotencyStore.AcquireResult.State.CLAIMED);
        store.publishClaim("fn", "k1", first.executionIdOrToken(), "exec-1");

        // A new key is refused: the single slot is taken.
        assertThat(store.acquireOrGet("fn", "k2").state())
                .isEqualTo(IdempotencyStore.AcquireResult.State.BUDGET_EXHAUSTED);

        // The published key still replays without touching the budget.
        IdempotencyStore.AcquireResult replay = store.acquireOrGet("fn", "k1");
        assertThat(replay.state()).isEqualTo(IdempotencyStore.AcquireResult.State.EXISTING);
        assertThat(replay.executionIdOrToken()).isEqualTo("exec-1");

        // A terminal key still replays at saturation too, and still blocks no one.
        store.markTerminal("fn", "k1", "exec-1");
        IdempotencyStore.AcquireResult terminalReplay = store.acquireOrGet("fn", "k1");
        assertThat(terminalReplay.state()).isEqualTo(IdempotencyStore.AcquireResult.State.EXISTING);
        assertThat(terminalReplay.terminal()).isTrue();
        assertThat(store.acquireOrGet("fn", "k3").state())
                .isEqualTo(IdempotencyStore.AcquireResult.State.BUDGET_EXHAUSTED);
    }

    @Test
    void replacingAndReclaimingTheSameAssociationNeverConsumesASecondSlot() {
        IdempotencyStore store = new IdempotencyStore(props(1), ticker);
        // Direct insertion reserves the single slot.
        store.put("fn", "k1", "exec-1");
        assertThat(store.occupied()).isEqualTo(1);

        // Re-claiming the published binding (a stale claim) is the same association.
        IdempotencyStore.AcquireResult reclaimed = store.claimIfMatches("fn", "k1", "exec-1");
        assertThat(reclaimed.state()).isEqualTo(IdempotencyStore.AcquireResult.State.CLAIMED);
        assertThat(store.occupied()).isEqualTo(1);

        // Publishing and archiving the re-claimed binding keep the same slot.
        store.publishClaim("fn", "k1", reclaimed.executionIdOrToken(), "exec-2");
        assertThat(store.occupied()).isEqualTo(1);
        store.markTerminal("fn", "k1", "exec-2");
        assertThat(store.occupied()).isEqualTo(1);

        // A single expiry releases the one slot exactly once - not zero, not twice.
        clock.addAndGet(Duration.ofMinutes(6).toNanos());
        drainExpired(store);
        assertThat(store.occupied()).isZero();
        assertThat(store.size()).isZero();
    }

    @Test
    void abandoningAClaimReleasesItsSlotOnceAndTheSlotIsReusable() {
        IdempotencyStore store = new IdempotencyStore(props(1), ticker);
        IdempotencyStore.AcquireResult claim = store.acquireOrGet("fn", "k1");
        assertThat(store.occupied()).isEqualTo(1);

        store.abandonClaim("fn", "k1", claim.executionIdOrToken());
        assertThat(store.occupied()).as("abandon releases the slot exactly once").isZero();

        // The released slot admits a new claim for a different key.
        IdempotencyStore.AcquireResult next = store.acquireOrGet("fn", "k2");
        assertThat(next.state()).isEqualTo(IdempotencyStore.AcquireResult.State.CLAIMED);
        assertThat(store.occupied()).isEqualTo(1);
    }

    @Test
    void shutdownReleasesEverySlotAndReopensTheBudget() {
        IdempotencyStore store = new IdempotencyStore(props(5), ticker);
        for (int i = 0; i < 5; i++) {
            store.put("fn", "k" + i, "exec-" + i);
        }
        assertThat(store.occupied()).isEqualTo(5);

        store.clear();

        assertThat(store.occupied()).as("shutdown releases every slot").isZero();
        assertThat(store.size()).isZero();
        IdempotencyStore.AcquireResult fresh = store.acquireOrGet("fn", "fresh");
        assertThat(fresh.state()).isEqualTo(IdempotencyStore.AcquireResult.State.CLAIMED);
        assertThat(store.occupied()).isEqualTo(1);
    }

    @Test
    void refusalsAreCountedWithoutLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IdempotencyStore store = new IdempotencyStore(props(1), registry);
        assertThat(store.acquireOrGet("fn", "k1").state())
                .isEqualTo(IdempotencyStore.AcquireResult.State.CLAIMED);
        assertThat(store.acquireOrGet("fn", "k2").state())
                .isEqualTo(IdempotencyStore.AcquireResult.State.BUDGET_EXHAUSTED);
        assertThat(store.acquireOrGet("fn", "k3").state())
                .isEqualTo(IdempotencyStore.AcquireResult.State.BUDGET_EXHAUSTED);

        assertThat(store.rejections()).isEqualTo(2);
        // Exposed as an unlabeled counter: no execution-id or user-key tag.
        var counter = registry.get("idempotency_key_budget_rejections").functionCounter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
        assertThat(counter.getId().getTags()).isEmpty();
    }
}
