package it.unimib.datai.nanofaas.controlplane.deployment;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ReplicaStatusSnapshotTest {

    private static final Duration TTL = Duration.ofMillis(1000);
    private static final ManagedDeploymentTarget TARGET = new ManagedDeploymentTarget("fn", "k8s");

    @Test
    void read_servesFreshValueFromCacheWithoutRefetching() {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger fetches = new AtomicInteger();
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);

        ReplicaStatus first = snapshot.read(TARGET, t -> {
            fetches.incrementAndGet();
            return new ReplicaStatus(1, 1);
        });
        ReplicaStatus second = snapshot.read(TARGET, t -> {
            fetches.incrementAndGet();
            return new ReplicaStatus(1, 1);
        });

        assertThat(first).isEqualTo(new ReplicaStatus(1, 1));
        assertThat(second).isEqualTo(new ReplicaStatus(1, 1));
        assertThat(fetches).hasValue(1);
    }

    @Test
    void read_refetchesOnlyAfterTheTtlBoundary() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch secondFetchDone = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, executor);
            ReplicaStatusSnapshot.Fetcher fetcher = t -> {
                int call = fetches.incrementAndGet();
                if (call == 1) {
                    return new ReplicaStatus(1, 1);
                }
                secondFetchDone.countDown();
                return new ReplicaStatus(2, 2);
            };

            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));

            clock.advanceMillis(TTL.toMillis() - 1);
            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));
            assertThat(fetches).hasValue(1);

            clock.advanceMillis(1); // age == TTL: now expired
            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));

            await(secondFetchDone);
            assertThat(fetches).hasValue(2);
            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(2, 2));
        }
    }

    @Test
    void read_keepsLastKnownGoodWhenRefreshFails_neverZero() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch failedRefreshDone = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, executor);
            ReplicaStatusSnapshot.Fetcher fetcher = t -> {
                if (fetches.incrementAndGet() == 1) {
                    return new ReplicaStatus(3, 3);
                }
                failedRefreshDone.countDown();
                throw new IllegalStateException("provider down");
            };

            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(3, 3));

            clock.advanceMillis(TTL.toMillis() + 1);
            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(3, 3));

            await(failedRefreshDone);
            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(3, 3));
        }
    }

    @Test
    void read_propagatesFirstFetchFailure_neverZero() {
        MutableInstantSource clock = new MutableInstantSource(0);
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);

        assertThatThrownBy(() -> snapshot.read(TARGET, t -> {
            throw new IllegalStateException("provider down");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider down");
    }

    @Test
    void read_servesStaleImmediatelyWithoutBlockingOnASlowRefresh() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, executor);
            ReplicaStatusSnapshot.Fetcher fetcher = t -> {
                if (calls.incrementAndGet() == 1) {
                    return new ReplicaStatus(1, 1);
                }
                slowEntered.countDown();
                await(releaseSlow);
                return new ReplicaStatus(1, 1);
            };

            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));

            clock.advanceMillis(TTL.toMillis() + 1);
            // Returns the stale value immediately while the slow refresh is still blocked.
            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));
            assertThat(slowEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseSlow.getCount()).isEqualTo(1);
        } finally {
            releaseSlow.countDown();
        }
    }

    @Test
    void read_isolatesASlowFunctionFromOthersThroughBoundedConcurrency() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, executor);
            java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> calls = new java.util.concurrent.ConcurrentHashMap<>();
            ReplicaStatusSnapshot.Fetcher fetcher = t -> {
                int call = calls.computeIfAbsent(t.functionName(), n -> new AtomicInteger()).incrementAndGet();
                if (t.functionName().equals("slow")) {
                    if (call == 1) {
                        return new ReplicaStatus(1, 1);
                    }
                    slowEntered.countDown();
                    await(releaseSlow);
                    return new ReplicaStatus(1, 1);
                }
                if (call == 1) {
                    return new ReplicaStatus(2, 2);
                }
                fastDone.countDown();
                return new ReplicaStatus(2, 2);
            };
            ManagedDeploymentTarget slow = new ManagedDeploymentTarget("slow", "k8s");
            ManagedDeploymentTarget fast = new ManagedDeploymentTarget("fast", "k8s");

            assertThat(snapshot.read(slow, fetcher)).isEqualTo(new ReplicaStatus(1, 1));
            assertThat(snapshot.read(fast, fetcher)).isEqualTo(new ReplicaStatus(2, 2));

            clock.advanceMillis(TTL.toMillis() + 1);
            assertThat(snapshot.read(slow, fetcher)).isEqualTo(new ReplicaStatus(1, 1));
            await(slowEntered);

            // The fast function's refresh completes on the second executor thread while the slow
            // one still holds the first: a slow refresh never starves another function.
            assertThat(snapshot.read(fast, fetcher)).isEqualTo(new ReplicaStatus(2, 2));
            await(fastDone);
        } finally {
            releaseSlow.countDown();
        }
    }

    @Test
    void read_sharesOneRefreshPerFunctionGeneration() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch refreshEntered = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, executor);
            ReplicaStatusSnapshot.Fetcher fetcher = t -> {
                int call = fetches.incrementAndGet();
                if (call == 1) {
                    return new ReplicaStatus(1, 1);
                }
                refreshEntered.countDown();
                await(releaseRefresh);
                return new ReplicaStatus(2, 2);
            };

            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));

            clock.advanceMillis(TTL.toMillis() + 1);
            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));
            await(refreshEntered);

            // Three more reads while the refresh is in flight must not start new fetches.
            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));
            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));
            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));
            assertThat(fetches).hasValue(2);
        } finally {
            releaseRefresh.countDown();
        }
    }

    @Test
    void invalidate_forcesARefetchOnTheNextRead() {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger fetches = new AtomicInteger();
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);
        ReplicaStatusSnapshot.Fetcher fetcher = t -> new ReplicaStatus(fetches.incrementAndGet(), 1);

        assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));
        snapshot.invalidate("fn");
        assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(2, 1));
        assertThat(fetches).hasValue(2);
    }

    @Test
    void invalidate_discardsAStaleInFlightRefresh_soNoOldGenerationUpdateLands() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch oldRefreshEntered = new CountDownLatch(1);
        CountDownLatch releaseOldRefresh = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, executor);
            ReplicaStatusSnapshot.Fetcher fetcher = t -> {
                int call = fetches.incrementAndGet();
                if (call == 1) {
                    return new ReplicaStatus(1, 1);
                }
                if (call == 2) {
                    oldRefreshEntered.countDown();
                    await(releaseOldRefresh);
                    return new ReplicaStatus(2, 2); // stale value from the previous generation
                }
                return new ReplicaStatus(3, 3);     // fresh value for the re-registered function
            };

            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));

            clock.advanceMillis(TTL.toMillis() + 1);
            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));
            await(oldRefreshEntered);

            // Re-registration invalidates the generation while the old refresh is still in flight.
            snapshot.invalidate("fn");
            releaseOldRefresh.countDown();

            // The stale (2,2) from the old generation must not land; the next read fetches fresh.
            assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(3, 3));
            assertThat(fetches).hasValue(3);
        } finally {
            releaseOldRefresh.countDown();
        }
    }

    @Test
    void refresh_alwaysFetchesFreshIgnoringTheCacheAndItsTtl() {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger fetches = new AtomicInteger();
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);
        ReplicaStatusSnapshot.Fetcher fetcher = t -> new ReplicaStatus(fetches.incrementAndGet(), 1);

        assertThat(snapshot.read(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));
        assertThat(snapshot.refresh(TARGET, fetcher)).isEqualTo(new ReplicaStatus(2, 1));
        assertThat(snapshot.refresh(TARGET, fetcher)).isEqualTo(new ReplicaStatus(3, 1));
        assertThat(fetches).hasValue(3);
    }

    @Test
    void rejectsNonPositiveTtl() {
        MutableInstantSource clock = new MutableInstantSource(0);
        assertThatThrownBy(() -> new ReplicaStatusSnapshot(clock.instantSource(), Duration.ZERO, Runnable::run))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReplicaStatusSnapshot(clock.instantSource(), Duration.ofMillis(-1), Runnable::run))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void read_stopsServingALastKnownGoodValueOnceItIsOlderThanTheStaleBound() {
        // A silently failing refresh used to leave read() serving the same value forever, with no
        // upper bound on its age and no signal anywhere. Past the bound the failure is the answer:
        // the autoscaler's per-function catch skips that cycle rather than deciding on a frozen
        // tuple, which is also what keeps a dead read path from reading as a stuck rollout.
        MutableInstantSource clock = new MutableInstantSource(0);
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);
        ReplicaStatusSnapshot.Fetcher failing = t -> {
            throw new IllegalStateException("provider down");
        };

        assertThat(snapshot.read(TARGET, t -> new ReplicaStatus(3, 3))).isEqualTo(new ReplicaStatus(3, 3));

        // Inside the bound: expired, but the last-known-good value is still the better answer.
        clock.advanceMillis(TTL.toMillis() + 1);
        assertThat(snapshot.read(TARGET, failing)).isEqualTo(new ReplicaStatus(3, 3));

        // Past it: no longer a defensible reading of reality.
        clock.advanceMillis(ReplicaStatusSnapshot.DEFAULT_MAX_STALE.toMillis());
        assertThatThrownBy(() -> snapshot.read(TARGET, failing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider down");
    }

    @Test
    void read_recoversAsSoonAsTheProviderAnswersAgain() {
        MutableInstantSource clock = new MutableInstantSource(0);
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);
        snapshot.read(TARGET, t -> new ReplicaStatus(3, 3));

        clock.advanceMillis(TTL.toMillis() + ReplicaStatusSnapshot.DEFAULT_MAX_STALE.toMillis() + 1);
        assertThatThrownBy(() -> snapshot.read(TARGET, t -> {
            throw new IllegalStateException("provider down");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(snapshot.read(TARGET, t -> new ReplicaStatus(5, 5))).isEqualTo(new ReplicaStatus(5, 5));
    }

    @Test
    void refresh_doesNotQueueBehindTheSharedBackgroundRefreshPool() throws Exception {
        // Wake-up and lifecycle reads are forced-fresh and latency-critical. Routing them through
        // the small background pool put them behind slow periodic refreshes — and behind the
        // unauthenticated replicas endpoint, which shares it. A saturated pool must not delay them.
        MutableInstantSource clock = new MutableInstantSource(0);
        CountDownLatch poolOccupied = new CountDownLatch(1);
        CountDownLatch releasePool = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, pool);
            pool.execute(() -> {
                poolOccupied.countDown();
                await(releasePool);
            });
            assertThat(poolOccupied.await(1, TimeUnit.SECONDS)).isTrue();

            ReplicaStatus fresh = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> snapshot.refresh(TARGET, t -> new ReplicaStatus(4, 4)));

            assertThat(fresh).isEqualTo(new ReplicaStatus(4, 4));
            releasePool.countDown();
        }
    }

    private static ReplicaStatusSnapshot snapshot(MutableInstantSource clock, Executor executor) {
        return new ReplicaStatusSnapshot(clock.instantSource(), TTL, executor);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
