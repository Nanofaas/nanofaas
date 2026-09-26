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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * P07a contract for one quota dimension. A reservation is the capability that
 * owns its units; the quota never releases by function name alone.
 */
class ResourceQuotaTest {

    @Test
    void aSuccessfulReservationAttributesUnitsToItsOwnerAndGeneration() {
        // Break caught: reserving without recording all three aggregate views would let
        // later admission checks or generation drain observe phantom free capacity.
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionGeneration generation = register(registry, "echo");
        ResourceQuota quota = new ResourceQuota(registry, 10, 6);
        ResourceOwner owner = new ResourceOwner(ResourceOwner.Scope.LOGICAL_EXECUTION, "exec-1");

        ResourceQuota.Reservation reservation = quota.tryReserve(generation, owner, 4).orElseThrow();

        assertThat(reservation.generation()).isEqualTo(generation);
        assertThat(reservation.owner()).isEqualTo(owner);
        assertThat(reservation.units()).isEqualTo(4);
        assertThat(quota.reservedGlobally()).isEqualTo(4);
        assertThat(quota.reservedForFunction("echo")).isEqualTo(4);
        assertThat(quota.reservedForGeneration(generation)).isEqualTo(4);
    }

    @Test
    void globalSaturationRejectsAReservationWithoutChangingAnyCounter() {
        // Break caught: a per-function-only check would let many individually-small
        // functions exceed the process-wide retained-resource cap.
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionGeneration echo = register(registry, "echo");
        FunctionGeneration other = register(registry, "other");
        FunctionGeneration third = register(registry, "third");
        ResourceQuota quota = new ResourceQuota(registry, 5, 5);
        assertThat(quota.tryReserve(echo, owner("exec-1"), 3)).isPresent();
        assertThat(quota.tryReserve(other, owner("exec-2"), 2)).isPresent();

        assertThat(quota.tryReserve(third, owner("exec-3"), 1))
                .isEmpty();
        assertThat(quota.reservedGlobally()).isEqualTo(5);
        assertThat(quota.reservedForFunction("third")).isZero();
    }

    @Test
    void perFunctionSaturationIncludesEveryGenerationOfTheSameName() {
        // Break caught: keying the function cap only by generation would let remove plus
        // re-register bypass the per-function budget while old physical work drains.
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionGeneration oldGeneration = register(registry, "echo");
        ResourceQuota quota = new ResourceQuota(registry, 20, 5);
        assertThat(quota.tryReserve(oldGeneration, owner("old"), 3)).isPresent();
        registry.remove("echo");
        FunctionGeneration newGeneration = register(registry, "echo");
        assertThat(quota.tryReserve(newGeneration, owner("new"), 2)).isPresent();

        assertThat(quota.tryReserve(newGeneration, owner("too-many"), 1)).isEmpty();
        assertThat(quota.reservedForFunction("echo")).isEqualTo(5);
        assertThat(quota.reservedForGeneration(oldGeneration)).isEqualTo(3);
        assertThat(quota.reservedForGeneration(newGeneration)).isEqualTo(2);
    }

    @Test
    void closeIsIdempotentAndCountersCannotGoNegative() {
        // Break caught: two completion paths closing one owner must not return another
        // owner's units or drive aggregate accounting below zero.
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionGeneration generation = register(registry, "echo");
        ResourceQuota quota = new ResourceQuota(registry, 10, 10);
        ResourceQuota.Reservation reservation =
                quota.tryReserve(generation, owner("exec-1"), 7).orElseThrow();

        reservation.close();
        reservation.close();

        assertThat(reservation.isClosed()).isTrue();
        assertThat(quota.reservedGlobally()).isZero();
        assertThat(quota.reservedForFunction("echo")).isZero();
        assertThat(quota.reservedForGeneration(generation)).isZero();
    }

    @Test
    void aLateOldGenerationCloseCannotReleaseTheNewGenerationsReservation() {
        // Break caught: releasing by name after remove/re-register would debit the new
        // incarnation instead of the exact reservation acquired by the old one.
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionGeneration oldGeneration = register(registry, "echo");
        ResourceQuota quota = new ResourceQuota(registry, 10, 10);
        ResourceQuota.Reservation oldReservation =
                quota.tryReserve(oldGeneration, owner("old"), 4).orElseThrow();
        registry.remove("echo");
        FunctionGeneration newGeneration = register(registry, "echo");
        ResourceQuota.Reservation newReservation =
                quota.tryReserve(newGeneration, owner("new"), 3).orElseThrow();

        oldReservation.close();
        oldReservation.close();

        assertThat(quota.reservedGlobally()).isEqualTo(3);
        assertThat(quota.reservedForFunction("echo")).isEqualTo(3);
        assertThat(quota.reservedForGeneration(oldGeneration)).isZero();
        assertThat(quota.reservedForGeneration(newGeneration)).isEqualTo(3);
        assertThat(newReservation.isClosed()).isFalse();
    }

    @Test
    void aRetiredGenerationCannotRecreateAccountingAfterItDrains() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        registry.register("echo", 1);
        FunctionGeneration retired = registry.activeGeneration("echo");
        DispatchLease drainingCapacity = registry.tryAcquireLease("echo", 1);
        assertThat(drainingCapacity).isNotNull();
        ResourceQuota quota = new ResourceQuota(registry, 4, 4);
        ResourceQuota.Reservation original =
                quota.tryReserve(retired, owner("original"), 4).orElseThrow();

        registry.remove("echo");
        original.close();

        assertThat(registry.activeGeneration("echo")).isNull();
        assertThat(quota.reservedForGeneration(retired)).isZero();
        assertThat(quota.tryReserve(retired, owner("late-callback"), 1)).isEmpty();
        assertThat(quota.reservedGlobally()).isZero();

        drainingCapacity.release();
        assertThat(quota.tryReserve(retired, owner("post-drain-callback"), 1)).isEmpty();
        assertThat(quota.reservedGlobally()).isZero();
    }

    @Test
    void anUncommittedBatchRollsBackEverySuccessfulIntermediateReservation() {
        // Break caught: failure of a later quota reservation must not leak earlier
        // execution/input ownership that was never published.
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionGeneration generation = register(registry, "echo");
        ResourceQuota executionQuota = new ResourceQuota(registry, 1, 1);
        ResourceQuota inputQuota = new ResourceQuota(registry, 1, 1);
        ResourceQuota.Reservation existingInput =
                inputQuota.tryReserve(generation, new ResourceOwner(ResourceOwner.Scope.INPUT_COPY, "held"), 1)
                        .orElseThrow();

        try (ReservationBatch batch = new ReservationBatch()) {
            assertThat(batch.tryReserve(executionQuota, generation, owner("exec-1"), 1)).isPresent();
            assertThat(batch.tryReserve(
                    inputQuota,
                    generation,
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
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionGeneration generation = register(registry, "echo");
        ResourceQuota quota = new ResourceQuota(registry, 1, 1);
        ResourceQuota.Reservation reservation;
        try (ReservationBatch batch = new ReservationBatch()) {
            reservation = batch.tryReserve(quota, generation, owner("exec-1"), 1).orElseThrow();
            batch.commit();
        }

        assertThat(quota.reservedGlobally()).isEqualTo(1);
        reservation.close();
        assertThat(quota.reservedGlobally()).isZero();
    }

    @Test
    void concurrentReservationsNeverExceedGlobalOrPerFunctionCaps() throws Exception {
        // Break caught: splitting cap checks from increments would allow simultaneous
        // admissions to oversubscribe either aggregate.
        int contenders = 32;
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionGeneration echo = register(registry, "echo");
        FunctionGeneration other = register(registry, "other");
        ResourceQuota quota = new ResourceQuota(registry, 7, 4);
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
                    FunctionGeneration generation = index % 2 == 0 ? echo : other;
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

    private static FunctionGeneration register(
            FunctionCapacityRegistry registry, String functionName) {
        registry.register(functionName, 1);
        return registry.activeGeneration(functionName);
    }
}
