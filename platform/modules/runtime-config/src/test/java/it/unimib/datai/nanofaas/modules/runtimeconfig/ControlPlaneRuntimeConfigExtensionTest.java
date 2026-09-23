package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.service.HotAdmissionLimits;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.ResourceOwner;
import it.unimib.datai.nanofaas.controlplane.capacity.ResourceQuota;
import it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity;
import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneRuntimeConfigExtensionTest {
    @Test
    void validatesAndAppliesPositiveRateLimit() {
        RateLimiter limiter = new RateLimiter();
        Fixture fixture = fixture(limiter);
        ControlPlaneRuntimeConfigExtension extension = fixture.extension;

        assertThat(extension.validate(Map.of("rateMaxPerSecond", 500))).isEmpty();
        extension.apply(Map.of("rateMaxPerSecond", 500));

        assertThat(limiter.getMaxPerSecond()).isEqualTo(500);
        assertThat(extension.validate(Map.of("rateMaxPerSecond", 0))).isNotEmpty();
    }

    @Test
    void acceptsOnlyPositiveIntRateLimits() {
        ControlPlaneRuntimeConfigExtension extension =
                fixture(new RateLimiter()).extension;

        for (Number value : new Number[]{0, -1, 1.5, 2147483648L}) {
            assertThat(extension.validate(Map.of("rateMaxPerSecond", value)))
                    .as("value %s", value)
                    .isNotEmpty();
        }
        assertThat(extension.validate(Map.of("rateMaxPerSecond", Integer.MAX_VALUE))).isEmpty();
    }

    @Test
    void reducesQuotaBelowOccupancyWithoutEvictionAndResumesAfterDrain() {
        Fixture fixture = fixture(new RateLimiter());
        fixture.generations.register("fn", 1);
        FunctionGeneration generation = fixture.generations.activeGeneration("fn");
        InvocationCapacity.Admission admission = fixture.invocations.reserve("fn", "e1", 8);
        admission.publish();
        ResourceQuota.Reservation copy = fixture.invocations.reserveInputCopy(
                generation, new ResourceOwner(ResourceOwner.Scope.INPUT_COPY, "e1/copy"), 8);
        WaiterCapacity.Waiter firstWaiter = fixture.waiters.reserve(generation, "e1/first");
        WaiterCapacity.Waiter secondWaiter = fixture.waiters.reserve(generation, "e1/second");

        Map<String, Object> reduction = Map.of(
                "maxExecutionsGlobal", 1,
                "maxExecutionsPerFunction", 1,
                "maxCanonicalInputBytesGlobal", 4,
                "maxCanonicalInputBytesPerFunction", 4,
                "maxPhysicalInputCopyBytesGlobal", 4,
                "maxPhysicalInputCopyBytesPerFunction", 4,
                "maxWaitersGlobal", 1,
                "maxWaitersPerFunction", 1);

        assertThat(fixture.extension.validate(reduction)).isEmpty();
        fixture.extension.apply(reduction);
        assertThat(fixture.invocations.executionReservedGlobally()).isOne();
        assertThat(fixture.invocations.inputReservedGlobally()).isEqualTo(8);
        assertThat(fixture.invocations.physicalInputCopyReservedGlobally()).isEqualTo(8);
        assertThat(fixture.waiters.reservedGlobally()).isEqualTo(2);
        assertThatThrownBy(() -> fixture.invocations.reserve("fn", "e2", 1))
                .isInstanceOf(RuntimeException.class);
        var owner = new ResourceOwner(ResourceOwner.Scope.INPUT_COPY, "e2/copy");
        assertThatThrownBy(() -> fixture.invocations.reserveInputCopy(
                generation, owner, 1))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> fixture.waiters.reserve(generation, "e2"))
                .isInstanceOf(RuntimeException.class);

        firstWaiter.close();
        secondWaiter.close();
        copy.close();
        admission.logicalExecution().close();
        admission.canonicalInput().close();
        InvocationCapacity.Admission resumed = fixture.invocations.reserve("fn", "e3", 4);
        ResourceQuota.Reservation resumedCopy = fixture.invocations.reserveInputCopy(
                generation, new ResourceOwner(ResourceOwner.Scope.INPUT_COPY, "e3/copy"), 4);
        WaiterCapacity.Waiter resumedWaiter = fixture.waiters.reserve(generation, "e3");
        resumedWaiter.close();
        resumedCopy.close();
        resumed.rollback();
    }

    @Test
    void validatesTheEffectiveMergedQuotaPatch() {
        Fixture fixture = fixture(new RateLimiter());

        assertThat(fixture.extension.validate(Map.of("maxExecutionsGlobal", 50_000)))
                .isEmpty();
        assertThat(fixture.extension.validate(Map.of("maxExecutionsGlobal", 10)))
                .containsExactly("maxExecutionsPerFunction must not exceed maxExecutionsGlobal");
        assertThat(fixture.extension.validate(Map.of("maxWaitersPerFunction", -1)))
                .containsExactly("maxWaitersPerFunction must be a positive integer");
    }

    private static Fixture fixture(RateLimiter limiter) {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        InvocationCapacity invocations = new InvocationCapacity(
                generations, 100, 20, 1_000, 100, 1_000, 100, 16);
        WaiterCapacity waiters = new WaiterCapacity(generations, 100, 20);
        // Driven through the real core port, so the test still exercises the live quotas
        // rather than a stub of the limits contract.
        return new Fixture(generations, invocations, waiters,
                new ControlPlaneRuntimeConfigExtension(
                        new HotAdmissionLimits(limiter, invocations, waiters)));
    }

    private record Fixture(FunctionCapacityRegistry generations, InvocationCapacity invocations,
                           WaiterCapacity waiters, ControlPlaneRuntimeConfigExtension extension) {
    }
}
