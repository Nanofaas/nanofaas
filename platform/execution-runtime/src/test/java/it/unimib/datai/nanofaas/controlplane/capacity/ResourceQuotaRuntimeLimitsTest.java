package it.unimib.datai.nanofaas.controlplane.capacity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceQuotaRuntimeLimitsTest {

    @Test
    void reducingBelowOccupancyDoesNotEvictAndAdmissionResumesAfterDrain() {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("fn", 1);
        FunctionGeneration generation = generations.activeGeneration("fn");
        ResourceQuota quota = new ResourceQuota(generations, 10, 10);
        ResourceQuota.Reservation first = quota.tryReserve(
                generation, new ResourceOwner(ResourceOwner.Scope.LOGICAL_EXECUTION, "one"), 6).orElseThrow();

        quota.updateLimits(5, 5);

        assertThat(quota.reservedGlobally()).isEqualTo(6);
        assertThat(quota.tryReserve(
                generation, new ResourceOwner(ResourceOwner.Scope.LOGICAL_EXECUTION, "blocked"), 1)).isEmpty();

        first.close();
        assertThat(quota.tryReserve(
                generation, new ResourceOwner(ResourceOwner.Scope.LOGICAL_EXECUTION, "resumed"), 5)).isPresent();
    }

    @Test
    void increasingLimitsPreservesGenerationOwnership() {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("fn", 1);
        FunctionGeneration oldGeneration = generations.activeGeneration("fn");
        ResourceQuota quota = new ResourceQuota(generations, 1, 1);
        ResourceQuota.Reservation old = quota.tryReserve(
                oldGeneration, new ResourceOwner(ResourceOwner.Scope.WAITER, "old"), 1).orElseThrow();
        generations.remove("fn");
        generations.register("fn", 1);
        FunctionGeneration currentGeneration = generations.activeGeneration("fn");

        quota.updateLimits(2, 2);
        ResourceQuota.Reservation current = quota.tryReserve(
                currentGeneration, new ResourceOwner(ResourceOwner.Scope.WAITER, "current"), 1).orElseThrow();

        assertThat(quota.reservedForGeneration(oldGeneration)).isOne();
        assertThat(quota.reservedForGeneration(currentGeneration)).isOne();
        old.close();
        assertThat(quota.reservedForGeneration(currentGeneration)).isOne();
        current.close();
    }
}
