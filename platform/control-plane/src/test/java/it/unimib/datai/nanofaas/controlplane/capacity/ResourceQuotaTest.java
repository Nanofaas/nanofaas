package it.unimib.datai.nanofaas.controlplane.capacity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P07a contract for one quota dimension. A reservation is the capability that
 * owns its units; the quota never releases by function name alone.
 */
class ResourceQuotaTest {

    private static final FunctionGeneration ECHO_1 = new FunctionGeneration("echo", 1);
    private static final FunctionGeneration ECHO_2 = new FunctionGeneration("echo", 2);
    private static final FunctionGeneration OTHER_3 = new FunctionGeneration("other", 3);

    @Test
    void aSuccessfulReservationAttributesUnitsToItsOwnerAndGeneration() {
        // Break caught: reserving without recording all three aggregate views would let
        // later admission checks or generation drain observe phantom free capacity.
        ResourceQuota quota = new ResourceQuota(10, 6);
        ResourceOwner owner = new ResourceOwner(ResourceOwner.Scope.LOGICAL_EXECUTION, "exec-1");

        ResourceQuota.Reservation reservation = quota.tryReserve(ECHO_1, owner, 4).orElseThrow();

        assertThat(reservation.generation()).isEqualTo(ECHO_1);
        assertThat(reservation.owner()).isEqualTo(owner);
        assertThat(reservation.units()).isEqualTo(4);
        assertThat(quota.reservedGlobally()).isEqualTo(4);
        assertThat(quota.reservedForFunction("echo")).isEqualTo(4);
        assertThat(quota.reservedForGeneration(ECHO_1)).isEqualTo(4);
    }

    @Test
    void globalSaturationRejectsAReservationWithoutChangingAnyCounter() {
        // Break caught: a per-function-only check would let many individually-small
        // functions exceed the process-wide retained-resource cap.
        ResourceQuota quota = new ResourceQuota(5, 5);
        assertThat(quota.tryReserve(ECHO_1, owner("exec-1"), 3)).isPresent();
        assertThat(quota.tryReserve(OTHER_3, owner("exec-2"), 2)).isPresent();

        assertThat(quota.tryReserve(new FunctionGeneration("third", 4), owner("exec-3"), 1))
                .isEmpty();
        assertThat(quota.reservedGlobally()).isEqualTo(5);
        assertThat(quota.reservedForFunction("third")).isZero();
    }

    @Test
    void perFunctionSaturationIncludesEveryGenerationOfTheSameName() {
        // Break caught: keying the function cap only by generation would let remove plus
        // re-register bypass the per-function budget while old physical work drains.
        ResourceQuota quota = new ResourceQuota(20, 5);
        assertThat(quota.tryReserve(ECHO_1, owner("old"), 3)).isPresent();
        assertThat(quota.tryReserve(ECHO_2, owner("new"), 2)).isPresent();

        assertThat(quota.tryReserve(ECHO_2, owner("too-many"), 1)).isEmpty();
        assertThat(quota.reservedForFunction("echo")).isEqualTo(5);
        assertThat(quota.reservedForGeneration(ECHO_1)).isEqualTo(3);
        assertThat(quota.reservedForGeneration(ECHO_2)).isEqualTo(2);
    }

    @Test
    void closeIsIdempotentAndCountersCannotGoNegative() {
        // Break caught: two completion paths closing one owner must not return another
        // owner's units or drive aggregate accounting below zero.
        ResourceQuota quota = new ResourceQuota(10, 10);
        ResourceQuota.Reservation reservation =
                quota.tryReserve(ECHO_1, owner("exec-1"), 7).orElseThrow();

        reservation.close();
        reservation.close();

        assertThat(reservation.isClosed()).isTrue();
        assertThat(quota.reservedGlobally()).isZero();
        assertThat(quota.reservedForFunction("echo")).isZero();
        assertThat(quota.reservedForGeneration(ECHO_1)).isZero();
    }

    @Test
    void aLateOldGenerationCloseCannotReleaseTheNewGenerationsReservation() {
        // Break caught: releasing by name after remove/re-register would debit the new
        // incarnation instead of the exact reservation acquired by the old one.
        ResourceQuota quota = new ResourceQuota(10, 10);
        ResourceQuota.Reservation oldReservation =
                quota.tryReserve(ECHO_1, owner("old"), 4).orElseThrow();
        ResourceQuota.Reservation newReservation =
                quota.tryReserve(ECHO_2, owner("new"), 3).orElseThrow();

        oldReservation.close();
        oldReservation.close();

        assertThat(quota.reservedGlobally()).isEqualTo(3);
        assertThat(quota.reservedForFunction("echo")).isEqualTo(3);
        assertThat(quota.reservedForGeneration(ECHO_1)).isZero();
        assertThat(quota.reservedForGeneration(ECHO_2)).isEqualTo(3);
        assertThat(newReservation.isClosed()).isFalse();
    }

    @Test
    void transferMovesOneReservationWithoutDoubleCountingOrLeavingTheOldHandleLive() {
        // Break caught: release-then-reacquire creates a quota gap (and may fail at
        // saturation), while copying a reservation double-counts retry-shared input.
        ResourceQuota quota = new ResourceQuota(4, 4);
        ResourceOwner firstCopy =
                new ResourceOwner(ResourceOwner.Scope.INPUT_COPY, "exec-1/attempt-1");
        ResourceQuota.Reservation first = quota.tryReserve(ECHO_1, firstCopy, 4).orElseThrow();
        ResourceOwner retryCopy =
                new ResourceOwner(ResourceOwner.Scope.INPUT_COPY, "exec-1/attempt-2");

        ResourceQuota.Reservation retry = first.transferTo(ECHO_2, retryCopy);

        assertThat(first.isClosed()).isTrue();
        assertThat(retry.generation()).isEqualTo(ECHO_2);
        assertThat(retry.owner()).isEqualTo(retryCopy);
        assertThat(quota.reservedGlobally()).isEqualTo(4);
        assertThat(quota.reservedForFunction("echo")).isEqualTo(4);
        assertThat(quota.reservedForGeneration(ECHO_1)).isZero();
        assertThat(quota.reservedForGeneration(ECHO_2)).isEqualTo(4);

        first.close();
        assertThat(quota.reservedGlobally()).isEqualTo(4);
        retry.close();
        assertThat(quota.reservedGlobally()).isZero();
    }

    @Test
    void anUncommittedBatchRollsBackEverySuccessfulIntermediateReservation() {
        // Break caught: failure of a later quota reservation must not leak earlier
        // execution/input ownership that was never published.
        ResourceQuota executionQuota = new ResourceQuota(1, 1);
        ResourceQuota inputQuota = new ResourceQuota(1, 1);
        ResourceQuota.Reservation existingInput =
                inputQuota.tryReserve(ECHO_1, new ResourceOwner(ResourceOwner.Scope.INPUT_COPY, "held"), 1)
                        .orElseThrow();

        try (ReservationBatch batch = new ReservationBatch()) {
            assertThat(batch.tryReserve(executionQuota, ECHO_1, owner("exec-1"), 1)).isPresent();
            assertThat(batch.tryReserve(
                    inputQuota,
                    ECHO_1,
                    new ResourceOwner(ResourceOwner.Scope.INPUT_COPY, "exec-1/input"),
                    1)).isEmpty();
        }

        assertThat(executionQuota.reservedGlobally()).isZero();
        assertThat(inputQuota.reservedGlobally()).isEqualTo(1);
        existingInput.close();
    }

    @Test
    void committingABatchLeavesItsReservationWithThePublishedOwner() {
        // Break caught: closing the try-with-resources admission scope after publish
        // must not release ownership that the live execution still retains.
        ResourceQuota quota = new ResourceQuota(1, 1);
        ResourceQuota.Reservation reservation;
        try (ReservationBatch batch = new ReservationBatch()) {
            reservation = batch.tryReserve(quota, ECHO_1, owner("exec-1"), 1).orElseThrow();
            batch.commit();
        }

        assertThat(quota.reservedGlobally()).isEqualTo(1);
        reservation.close();
        assertThat(quota.reservedGlobally()).isZero();
    }

    @Test
    void rollbackFollowsAReservationTransferredBeforePublication() {
        // Break caught: a batch tracking only the superseded handle would make its
        // close a no-op and leak the transfer if publication then failed.
        ResourceQuota quota = new ResourceQuota(3, 3);
        try (ReservationBatch batch = new ReservationBatch()) {
            ResourceQuota.Reservation provisional = batch.tryReserve(
                    quota,
                    ECHO_1,
                    new ResourceOwner(ResourceOwner.Scope.INPUT_COPY, "exec-1/provisional"),
                    3).orElseThrow();
            provisional.transferTo(
                    ECHO_2,
                    new ResourceOwner(ResourceOwner.Scope.INPUT_COPY, "exec-1/attempt-2"));
        }

        assertThat(quota.reservedGlobally()).isZero();
        assertThat(quota.reservedForGeneration(ECHO_1)).isZero();
        assertThat(quota.reservedForGeneration(ECHO_2)).isZero();
    }

    @Test
    void concurrentReservationsNeverExceedGlobalOrPerFunctionCaps() throws Exception {
        // Break caught: splitting cap checks from increments would allow simultaneous
        // admissions to oversubscribe either aggregate.
        int contenders = 32;
        ResourceQuota quota = new ResourceQuota(7, 4);
        CyclicBarrier start = new CyclicBarrier(contenders + 1);
        CountDownLatch attempted = new CountDownLatch(contenders);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(contenders);
        List<Future<Optional<ResourceQuota.Reservation>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < contenders; i++) {
                int index = i;
                futures.add(workers.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    FunctionGeneration generation = index % 2 == 0 ? ECHO_1 : OTHER_3;
                    Optional<ResourceQuota.Reservation> reservation =
                            quota.tryReserve(generation, owner("owner-" + index), 1);
                    attempted.countDown();
                    if (reservation.isPresent()) {
                        release.await(5, TimeUnit.SECONDS);
                    }
                    return reservation;
                }));
            }

            start.await(5, TimeUnit.SECONDS);
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(quota.reservedGlobally()).isEqualTo(7);
            assertThat(quota.reservedForFunction("echo")).isLessThanOrEqualTo(4);
            assertThat(quota.reservedForFunction("other")).isLessThanOrEqualTo(4);

            release.countDown();
            int acquired = 0;
            for (Future<Optional<ResourceQuota.Reservation>> future : futures) {
                Optional<ResourceQuota.Reservation> reservation = future.get(5, TimeUnit.SECONDS);
                if (reservation.isPresent()) {
                    acquired++;
                    reservation.orElseThrow().close();
                }
            }
            assertThat(acquired).isEqualTo(7);
            assertThat(quota.reservedGlobally()).isZero();
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private static ResourceOwner owner(String identity) {
        return new ResourceOwner(ResourceOwner.Scope.LOGICAL_EXECUTION, identity);
    }
}
