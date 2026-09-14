package it.unimib.datai.nanofaas.controlplane.deployment;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot.RefreshLimits;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot.RefreshPath;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ReplicaStatusSnapshotTest {

    private static final Duration TTL = Duration.ofSeconds(1);
    private static final ManagedDeploymentTarget TARGET = new ManagedDeploymentTarget("fn", "k8s");

    // ------------------------------------------------------------------ item 3: observation states

    @Test
    void observe_reportsUnavailableOnAColdStartInsteadOfBlockingOnAFirstFetch() {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger fetches = new AtomicInteger();
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);

        ReplicaObservation cold = snapshot.observe(TARGET, t -> {
            fetches.incrementAndGet();
            return new ReplicaStatus(1, 1);
        });

        assertThat(cold).isInstanceOf(ReplicaObservation.Unavailable.class);
        assertThat(cold.state()).isEqualTo(ReplicaObservation.State.UNAVAILABLE);
        assertThat(cold.isUsable()).isFalse();
        // ... but the refresh it scheduled has already run on the inline executor.
        assertThat(fetches).hasValue(1);
        assertThat(snapshot.observe(TARGET, failingFetcher()))
                .isEqualTo(ReplicaObservation.fresh(new ReplicaStatus(1, 1), clock.instant()));
    }

    @Test
    void observe_walksFreshThenStaleThenUnavailableOnAControlledClock() {
        MutableInstantSource clock = new MutableInstantSource(0);
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);
        snapshot.observe(TARGET, t -> new ReplicaStatus(3, 3));

        // Inside the TTL: fresh, served from cache without touching the provider.
        ReplicaObservation fresh = snapshot.observe(TARGET, failingFetcher());
        assertThat(fresh.state()).isEqualTo(ReplicaObservation.State.FRESH);
        assertThat(fresh.age(clock.instant())).isZero();
        assertThat(statusOf(fresh)).isEqualTo(new ReplicaStatus(3, 3));

        // Past the TTL, inside the stale bound: last-known-good, explicitly labelled stale.
        clock.advanceMillis(TTL.toMillis() + 1);
        ReplicaObservation stale = snapshot.observe(TARGET, failingFetcher());
        assertThat(stale.state()).isEqualTo(ReplicaObservation.State.STALE);
        assertThat(statusOf(stale)).isEqualTo(new ReplicaStatus(3, 3));
        assertThat(stale.age(clock.instant())).isEqualTo(Duration.ofMillis(TTL.toMillis() + 1));

        // Past the stale bound: unavailable, never an invented zero.
        clock.advanceMillis(ReplicaStatusSnapshot.DEFAULT_MAX_STALE.toMillis());
        ReplicaObservation unavailable = snapshot.observe(TARGET, failingFetcher());
        assertThat(unavailable).isInstanceOf(ReplicaObservation.Unavailable.class);
        assertThat(((ReplicaObservation.Unavailable) unavailable).reason()).contains("provider down");
        // Both post-TTL observations scheduled a refresh, and both of those failed.
        assertThat(snapshot.failedRefreshes(RefreshPath.PERIODIC)).isEqualTo(2);
        assertThat(snapshot.completedRefreshes(RefreshPath.PERIODIC)).isEqualTo(3);
    }

    @Test
    void observe_recoversAsSoonAsTheProviderAnswersAgain() {
        MutableInstantSource clock = new MutableInstantSource(0);
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);
        snapshot.observe(TARGET, t -> new ReplicaStatus(3, 3));

        clock.advanceMillis(TTL.toMillis() + ReplicaStatusSnapshot.DEFAULT_MAX_STALE.toMillis() + 1);
        assertThat(snapshot.observe(TARGET, failingFetcher()).isUsable()).isFalse();

        snapshot.observe(TARGET, t -> new ReplicaStatus(5, 5));
        assertThat(statusOf(snapshot.observe(TARGET, failingFetcher()))).isEqualTo(new ReplicaStatus(5, 5));
    }

    @Test
    void observe_servesFreshValueFromCacheWithoutRefetching() {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger fetches = new AtomicInteger();
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);
        ReplicaStatusSnapshot.Fetcher fetcher = t -> {
            fetches.incrementAndGet();
            return new ReplicaStatus(1, 1);
        };

        snapshot.observe(TARGET, fetcher);
        snapshot.observe(TARGET, fetcher);
        snapshot.observe(TARGET, fetcher);

        assertThat(fetches).hasValue(1);
    }

    @Test
    void observe_refetchesOnlyAfterTheTtlBoundary() {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger fetches = new AtomicInteger();
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);
        ReplicaStatusSnapshot.Fetcher fetcher = t -> new ReplicaStatus(fetches.incrementAndGet(), 1);

        snapshot.observe(TARGET, fetcher);
        clock.advanceMillis(TTL.toMillis() - 1);
        snapshot.observe(TARGET, fetcher);
        assertThat(fetches).hasValue(1);

        clock.advanceMillis(1); // age == TTL: expired
        snapshot.observe(TARGET, fetcher);
        assertThat(fetches).hasValue(2);
    }

    @Test
    void observe_keepsLastKnownGoodWhenTheRefreshFails_neverZero() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        CountDownLatch failedRefreshDone = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, executor);
            snapshot.observe(TARGET, t -> new ReplicaStatus(3, 3));
            awaitValue(snapshot, new ReplicaStatus(3, 3));

            clock.advanceMillis(TTL.toMillis() + 1);
            ReplicaObservation stale = snapshot.observe(TARGET, t -> {
                failedRefreshDone.countDown();
                throw new IllegalStateException("provider down");
            });

            assertThat(statusOf(stale)).isEqualTo(new ReplicaStatus(3, 3));
            await(failedRefreshDone);
            assertThat(statusOf(snapshot.observe(TARGET, failingFetcher()))).isEqualTo(new ReplicaStatus(3, 3));
        }
    }

    // ------------------------------------------------- item 1/acceptance: one slow backend, others move

    @Test
    void observe_neverBlocksTheLoopOnAProviderThatDoesNotRespondWhileOthersDo() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        ManagedDeploymentTarget slow = new ManagedDeploymentTarget("slow", "k8s");
        ManagedDeploymentTarget fast = new ManagedDeploymentTarget("fast", "k8s");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, executor);
            ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
            ReplicaStatusSnapshot.Fetcher fetcher = t -> {
                calls.computeIfAbsent(t.functionName(), n -> new AtomicInteger()).incrementAndGet();
                if (t.functionName().equals("slow")) {
                    slowEntered.countDown();
                    await(releaseSlow);
                }
                return new ReplicaStatus(2, 2);
            };

            // The slow backend's very first observation costs the loop nothing: it is answered
            // immediately as UNAVAILABLE while the provider call hangs in the background.
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> snapshot.observe(slow, fetcher));
            await(slowEntered);

            // The other backend is visited and completed inside the same period.
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> snapshot.observe(fast, fetcher));
            assertThat(awaitValue(snapshot, fast, new ReplicaStatus(2, 2))).isTrue();
            assertThat(releaseSlow.getCount()).isEqualTo(1);
        } finally {
            releaseSlow.countDown();
        }
    }

    // ------------------------------------------------------------------ item 1: bounded queue

    @Test
    void observe_rejectsRefreshesPastTheQueueBoundInsteadOfQueueingThemOrRunningThemOnTheCaller() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ConcurrentHashMap<String, Thread> fetchThreads = new ConcurrentHashMap<>();
        // One worker, one queue slot: the third distinct function has nowhere to go.
        ReplicaStatusSnapshot snapshot = new ReplicaStatusSnapshot(clock.instantSource(), TTL,
                new RefreshLimits(1, 1, 1, 1, Duration.ofMillis(200)));
        try {
            ReplicaStatusSnapshot.Fetcher fetcher = t -> {
                fetchThreads.put(t.functionName(), Thread.currentThread());
                firstEntered.countDown();
                await(release, 10);
                return new ReplicaStatus(1, 1);
            };

            snapshot.observe(new ManagedDeploymentTarget("a", "k8s"), fetcher);   // runs, blocks
            await(firstEntered);
            snapshot.observe(new ManagedDeploymentTarget("b", "k8s"), fetcher);   // queued
            assertThat(snapshot.activeRefreshes(RefreshPath.PERIODIC)).isEqualTo(1);
            assertThat(snapshot.queueDepth(RefreshPath.PERIODIC)).isEqualTo(1);

            Thread caller = Thread.currentThread();
            ReplicaObservation rejected = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> snapshot.observe(new ManagedDeploymentTarget("c", "k8s"), fetcher));

            assertThat(rejected.isUsable()).isFalse();
            assertThat(snapshot.rejectedRefreshes(RefreshPath.PERIODIC)).isEqualTo(1);
            assertThat(snapshot.queueDepth(RefreshPath.PERIODIC)).isEqualTo(1);
            // No CallerRunsPolicy: the rejected refresh was not executed on the loop's thread.
            assertThat(fetchThreads).doesNotContainKey("c");
            assertThat(fetchThreads.values()).doesNotContain(caller);

            // A rejected submission must not strand the entry's single-flight slot: the next cycle
            // is free to try again (and is rejected again while the executor is still saturated).
            snapshot.observe(new ManagedDeploymentTarget("c", "k8s"), fetcher);
            assertThat(snapshot.rejectedRefreshes(RefreshPath.PERIODIC)).isEqualTo(2);
        } finally {
            release.countDown();
            snapshot.close();
        }
    }

    @Test
    void rejectsInvalidLimitsAndTtl() {
        MutableInstantSource clock = new MutableInstantSource(0);
        assertThatThrownBy(() -> new RefreshLimits(0, 1, 1, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshLimits(1, 0, 1, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshLimits(1, 1, -1, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshLimits(1, 1, 1, 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshLimits(1, 1, 1, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReplicaStatusSnapshot(clock.instantSource(), Duration.ZERO, Runnable::run))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReplicaStatusSnapshot(clock.instantSource(), Duration.ofMillis(-1), Runnable::run))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ item 2: single flight

    @Test
    void observe_sharesOneRefreshPerFunctionGeneration() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch refreshEntered = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, executor);
            ReplicaStatusSnapshot.Fetcher fetcher = t -> {
                if (fetches.incrementAndGet() > 1) {
                    refreshEntered.countDown();
                    await(releaseRefresh);
                }
                return new ReplicaStatus(1, 1);
            };

            snapshot.observe(TARGET, fetcher);
            awaitValue(snapshot, new ReplicaStatus(1, 1));

            clock.advanceMillis(TTL.toMillis() + 1);
            snapshot.observe(TARGET, fetcher);
            await(refreshEntered);

            snapshot.observe(TARGET, fetcher);
            snapshot.observe(TARGET, fetcher);
            snapshot.observe(TARGET, fetcher);
            assertThat(fetches).hasValue(2);
        } finally {
            releaseRefresh.countDown();
        }
    }

    @Test
    void repeatedInvalidateWhileAFetchIsBlockedCannotQueueUnboundedNewRefreshes() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ReplicaStatusSnapshot snapshot = new ReplicaStatusSnapshot(clock.instantSource(), TTL,
                new RefreshLimits(1, 2, 1, 1, Duration.ofMillis(200)));
        try {
            // Uninterruptible on purpose: a provider adapter that ignores interrupts is exactly the
            // case where invalidation must not be allowed to pile up new work behind it.
            ReplicaStatusSnapshot.Fetcher fetcher = t -> {
                entered.countDown();
                awaitIgnoringInterrupts(release);
                return new ReplicaStatus(1, 1);
            };

            snapshot.observe(TARGET, fetcher);
            await(entered);
            for (int i = 0; i < 200; i++) {
                snapshot.invalidate(TARGET.functionName());
                snapshot.observe(TARGET, fetcher);
            }

            assertThat(snapshot.queueDepth(RefreshPath.PERIODIC)).isEqualTo(2);
            assertThat(snapshot.activeRefreshes(RefreshPath.PERIODIC)).isEqualTo(1);
            // 201 submissions in all: one running, two queued, the rest refused.
            assertThat(snapshot.rejectedRefreshes(RefreshPath.PERIODIC)).isEqualTo(198);
            assertThat(snapshot.entryCount()).isEqualTo(1);
        } finally {
            release.countDown();
            snapshot.close();
        }
    }

    @Test
    void invalidatedEntriesAreRemovedSoRepeatedChurnLeavesNothingBehind() {
        MutableInstantSource clock = new MutableInstantSource(0);
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);
        for (int i = 0; i < 1000; i++) {
            String name = "deleted-" + i;
            snapshot.observe(new ManagedDeploymentTarget(name, "container-local"), t -> new ReplicaStatus(1, 1));
            snapshot.invalidate(name);
            snapshot.invalidate(name);   // repeated invalidate is a no-op, not a resurrection
        }
        assertThat(snapshot.entryCount()).isZero();
        assertThat(snapshot.queueDepth(RefreshPath.PERIODIC)).isZero();
    }

    @Test
    void invalidate_forcesARefetchOnTheNextObservation() {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger fetches = new AtomicInteger();
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);
        ReplicaStatusSnapshot.Fetcher fetcher = t -> new ReplicaStatus(fetches.incrementAndGet(), 1);

        snapshot.observe(TARGET, fetcher);
        assertThat(statusOf(snapshot.observe(TARGET, fetcher))).isEqualTo(new ReplicaStatus(1, 1));
        snapshot.invalidate("fn");
        snapshot.observe(TARGET, fetcher);
        assertThat(statusOf(snapshot.observe(TARGET, fetcher))).isEqualTo(new ReplicaStatus(2, 1));
    }

    // ------------------------------------------------- item 5: removal during a GET, late completion

    @Test
    void aRemovalDuringAGetDiscardsTheAnswerAndDoesNotReinsertTheEntry() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        CountDownLatch fetchEntered = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        CountDownLatch fetchReturned = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, executor);
            snapshot.observe(TARGET, t -> {
                fetchEntered.countDown();
                // Ignores the interrupt invalidate() sends, like a provider adapter with no
                // cancellation hook: the answer arrives late, and must still be discarded.
                awaitIgnoringInterrupts(releaseFetch);
                fetchReturned.countDown();
                return new ReplicaStatus(9, 9);
            });
            await(fetchEntered);

            snapshot.invalidate(TARGET.functionName());
            assertThat(snapshot.entryCount()).isZero();

            releaseFetch.countDown();
            await(fetchReturned);

            // The late answer belongs to a generation nobody owns any more: it neither publishes
            // (9, 9) nor puts the removed function back into the map.
            assertThat(snapshot.entryCount()).isZero();
            ReplicaObservation afterRemoval = snapshot.observe(TARGET, t -> new ReplicaStatus(1, 1));
            assertThat(afterRemoval.isUsable()).isFalse();
            assertThat(awaitValue(snapshot, TARGET, new ReplicaStatus(1, 1))).isTrue();
        } finally {
            releaseFetch.countDown();
        }
    }

    @Test
    void aReRegistrationUnderAnotherBackendDuringAGetDiscardsTheOldBackendsAnswer() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        CountDownLatch fetchEntered = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, executor);
            snapshot.observe(TARGET, t -> {
                fetchEntered.countDown();
                awaitIgnoringInterrupts(releaseFetch);
                return new ReplicaStatus(9, 9);
            });
            await(fetchEntered);

            ManagedDeploymentTarget reRegistered = new ManagedDeploymentTarget("fn", "container-local");
            snapshot.observe(reRegistered, t -> new ReplicaStatus(2, 2));
            releaseFetch.countDown();

            assertThat(awaitValue(snapshot, reRegistered, new ReplicaStatus(2, 2))).isTrue();
            assertThat(statusOf(snapshot.observe(reRegistered, failingFetcher())))
                    .isEqualTo(new ReplicaStatus(2, 2));
        } finally {
            releaseFetch.countDown();
        }
    }

    @Test
    void aCompletionThatLandsAfterTheFreshnessDeadlineStillPopulatesTheCache() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            ReplicaStatusSnapshot snapshot = new ReplicaStatusSnapshot(clock.instantSource(), TTL,
                    executor, executor, Duration.ofMillis(100));

            assertThatThrownBy(() -> snapshot.refresh(TARGET, t -> {
                awaitIgnoringInterrupts(release);
                return new ReplicaStatus(7, 7);
            })).isInstanceOf(ReplicaStatusSnapshot.ReplicaStatusUnavailableException.class)
                    .hasMessageContaining("freshness deadline");

            release.countDown();
            // The caller gave up, but the reading is real and belongs to the current generation.
            assertThat(awaitValue(snapshot, new ReplicaStatus(7, 7))).isTrue();
        } finally {
            release.countDown();
        }
    }

    // ------------------------------------------------------------------ item 4: freshness path

    @Test
    void refresh_alwaysFetchesFreshIgnoringTheCacheAndItsTtl() {
        MutableInstantSource clock = new MutableInstantSource(0);
        AtomicInteger fetches = new AtomicInteger();
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);
        ReplicaStatusSnapshot.Fetcher fetcher = t -> new ReplicaStatus(fetches.incrementAndGet(), 1);

        assertThat(snapshot.refresh(TARGET, fetcher)).isEqualTo(new ReplicaStatus(1, 1));
        assertThat(snapshot.refresh(TARGET, fetcher)).isEqualTo(new ReplicaStatus(2, 1));
        assertThat(snapshot.refresh(TARGET, fetcher)).isEqualTo(new ReplicaStatus(3, 1));
    }

    @Test
    void refresh_releasesTheCallerAtItsDeadlineWhenTheProviderNeverAnswers() throws Exception {
        // RED before P10: refresh() joined the fetch with no bound at all, so a hung provider held
        // the wake-up worker forever (the 3s preemptive bound below never returned).
        MutableInstantSource clock = new MutableInstantSource(0);
        CountDownLatch releaseHang = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            ReplicaStatusSnapshot snapshot = new ReplicaStatusSnapshot(clock.instantSource(), TTL,
                    executor, executor, Duration.ofMillis(150));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThatThrownBy(() -> snapshot.refresh(TARGET, t -> {
                        await(releaseHang, 10);
                        return new ReplicaStatus(1, 1);
                    })).isInstanceOf(ReplicaStatusSnapshot.ReplicaStatusUnavailableException.class));
        } finally {
            releaseHang.countDown();
        }
    }

    @Test
    void refresh_doesNotQueueBehindTheSlowPeriodicRefreshPool() throws Exception {
        // Wake-up and lifecycle reads are latency-critical: they run on their own bounded pool so a
        // saturated periodic pool cannot delay them.
        MutableInstantSource clock = new MutableInstantSource(0);
        CountDownLatch periodicOccupied = new CountDownLatch(1);
        CountDownLatch releasePeriodic = new CountDownLatch(1);
        try (ExecutorService periodic = Executors.newFixedThreadPool(1);
             ExecutorService freshness = Executors.newFixedThreadPool(1)) {
            ReplicaStatusSnapshot snapshot = new ReplicaStatusSnapshot(clock.instantSource(), TTL,
                    periodic, freshness, Duration.ofSeconds(2));
            periodic.execute(() -> {
                periodicOccupied.countDown();
                await(releasePeriodic, 10);
            });
            await(periodicOccupied);

            ReplicaStatus fresh = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> snapshot.refresh(TARGET, t -> new ReplicaStatus(4, 4)));

            assertThat(fresh).isEqualTo(new ReplicaStatus(4, 4));
        } finally {
            releasePeriodic.countDown();
        }
    }

    @Test
    void refresh_failsWhenTheFreshnessExecutorIsSaturatedRatherThanRunningOnTheCaller() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ConcurrentHashMap<String, Thread> fetchThreads = new ConcurrentHashMap<>();
        ReplicaStatusSnapshot snapshot = new ReplicaStatusSnapshot(clock.instantSource(), TTL,
                new RefreshLimits(1, 1, 1, 1, Duration.ofSeconds(5)));
        try {
            ReplicaStatusSnapshot.Fetcher blocking = t -> {
                fetchThreads.put(t.functionName(), Thread.currentThread());
                entered.countDown();
                await(release, 10);
                return new ReplicaStatus(1, 1);
            };
            ExecutorService callers = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = callers.submit(
                        () -> snapshot.refresh(new ManagedDeploymentTarget("a", "k8s"), blocking));
                await(entered);
                Future<?> second = callers.submit(
                        () -> snapshot.refresh(new ManagedDeploymentTarget("b", "k8s"), blocking));
                awaitQueueDepth(snapshot, RefreshPath.FRESHNESS, 1);

                Thread caller = Thread.currentThread();
                assertThatThrownBy(() -> snapshot.refresh(new ManagedDeploymentTarget("c", "k8s"), blocking))
                        .isInstanceOf(ReplicaStatusSnapshot.ReplicaStatusUnavailableException.class)
                        .hasMessageContaining("rejected");
                assertThat(snapshot.rejectedRefreshes(RefreshPath.FRESHNESS)).isEqualTo(1);
                assertThat(fetchThreads.values()).doesNotContain(caller);
                release.countDown();
                first.get(1, TimeUnit.SECONDS);
                second.get(1, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                callers.shutdownNow();
            }
        } finally {
            release.countDown();
            snapshot.close();
        }
    }

    @Test
    void refresh_doesNotHandBackAValueTheGenerationGuardRejected() throws Exception {
        // The guard keeps a superseded fetch out of the CACHE, but a caller blocked on that same
        // fetch was still handed the old incarnation's replica count. A forced-fresh read spanning
        // a deprovision and re-registration must fail rather than answer for a function that no
        // longer exists in that form.
        MutableInstantSource clock = new MutableInstantSource(0);
        CountDownLatch fetchEntered = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);
            var pending = caller.submit(() -> snapshot.refresh(TARGET, t -> {
                fetchEntered.countDown();
                await(releaseFetch, 10);
                return new ReplicaStatus(9, 9);
            }));

            await(fetchEntered);
            snapshot.invalidate(TARGET.functionName());
            releaseFetch.countDown();

            assertThatThrownBy(pending::get).hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }

    // ------------------------------------------------------------------ item 5: shutdown, metrics

    @Test
    void close_stopsTheOwnedPoolsAndRetiresEveryEntry() {
        MutableInstantSource clock = new MutableInstantSource(0);
        ReplicaStatusSnapshot snapshot = new ReplicaStatusSnapshot(clock.instantSource(), TTL,
                RefreshLimits.DEFAULTS);
        snapshot.observe(TARGET, t -> new ReplicaStatus(1, 1));

        snapshot.close();

        // close() interrupts rather than waiting, so a worker already inside a task may still be
        // unwinding; termination is expected promptly, not instantaneously.
        assertThat(awaitTermination(snapshot)).isTrue();
        assertThat(snapshot.entryCount()).isZero();
        awaitQueueDepth(snapshot, RefreshPath.PERIODIC, 0);

        // A reader that arrives after shutdown is answered, not blown up: the submission is
        // rejected and the observation is UNAVAILABLE.
        ReplicaObservation afterClose = snapshot.observe(TARGET, t -> new ReplicaStatus(1, 1));
        assertThat(afterClose.isUsable()).isFalse();
        assertThat(snapshot.rejectedRefreshes(RefreshPath.PERIODIC)).isEqualTo(1);
    }

    @Test
    void close_leavesAnInjectedExecutorToItsOwner() throws Exception {
        MutableInstantSource clock = new MutableInstantSource(0);
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            ReplicaStatusSnapshot snapshot = snapshot(clock, executor);
            snapshot.observe(TARGET, t -> new ReplicaStatus(1, 1));
            snapshot.close();
            assertThat(executor.isShutdown()).isFalse();
        }
    }

    @Test
    void bindTo_publishesQueueActiveRejectionAgeAndDurationWithoutPerFunctionCardinality() {
        MutableInstantSource clock = new MutableInstantSource(0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReplicaStatusSnapshot snapshot = snapshot(clock, Runnable::run);
        snapshot.bindTo(registry);
        snapshot.observe(TARGET, t -> new ReplicaStatus(1, 1));
        clock.advanceMillis(2000);

        assertThat(registry.get("replica_snapshot_entries").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("replica_snapshot_observation_age_seconds_max").gauge().value())
                .isEqualTo(2.0);
        for (String path : List.of("periodic", "freshness")) {
            assertThat(registry.get("replica_snapshot_refresh_queue_depth").tag("path", path).gauge()).isNotNull();
            assertThat(registry.get("replica_snapshot_refresh_active").tag("path", path).gauge()).isNotNull();
            assertThat(registry.get("replica_snapshot_refresh_rejected_total").tag("path", path)
                    .functionCounter()).isNotNull();
            assertThat(registry.get("replica_snapshot_refresh_failed_total").tag("path", path)
                    .functionCounter()).isNotNull();
            assertThat(registry.get("replica_snapshot_refresh_seconds").tag("path", path)
                    .functionTimer()).isNotNull();
        }
        assertThat(registry.get("replica_snapshot_refresh_seconds").tag("path", "periodic")
                .functionTimer().count()).isEqualTo(1.0);
        assertThat(registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getKey))
                .containsOnly("path");
    }

    // ------------------------------------------------------------------ helpers

    private static ReplicaStatusSnapshot snapshot(MutableInstantSource clock, Executor executor) {
        return new ReplicaStatusSnapshot(clock.instantSource(), TTL, executor);
    }

    private static ReplicaStatusSnapshot.Fetcher failingFetcher() {
        return t -> {
            throw new IllegalStateException("provider down");
        };
    }

    private static ReplicaStatus statusOf(ReplicaObservation observation) {
        assertThat(observation).isInstanceOf(ReplicaObservation.Available.class);
        return ((ReplicaObservation.Available) observation).status();
    }

    private static boolean awaitValue(ReplicaStatusSnapshot snapshot, ReplicaStatus expected) {
        return awaitValue(snapshot, TARGET, expected);
    }

    /** Polls the cached observation until it reports the expected value, or the bound elapses. */
    private static boolean awaitValue(ReplicaStatusSnapshot snapshot, ManagedDeploymentTarget target,
                                      ReplicaStatus expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            // A fetcher that can only fail: the value must come from the refresh under test, never
            // from the poll itself.
            if (snapshot.observe(target, failingFetcher()) instanceof ReplicaObservation.Available available
                    && expected.equals(available.status())) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private static boolean awaitTermination(ReplicaStatusSnapshot snapshot) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (snapshot.isTerminated()) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private static void awaitQueueDepth(ReplicaStatusSnapshot snapshot, RefreshPath path, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (snapshot.queueDepth(path) == expected) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("queue depth never reached " + expected
                + " (last: " + snapshot.queueDepth(path) + ")");
    }

    private static void await(CountDownLatch latch) {
        await(latch, 1);
    }

    /** Simulates a provider adapter that does not honour interruption, with a safety bound. */
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try {
                if (latch.await(50, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException ignored) {
                // deliberately swallowed: that is the behaviour under test
            }
        }
    }

    private static void await(CountDownLatch latch, int seconds) {
        try {
            if (!latch.await(seconds, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
