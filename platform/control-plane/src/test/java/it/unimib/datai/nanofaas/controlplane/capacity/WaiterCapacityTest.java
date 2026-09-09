package it.unimib.datai.nanofaas.controlplane.capacity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaiterCapacityTest {

    @Test
    void reservationsEnforceGlobalAndPerFunctionCapsAndDrainExactlyOnce() {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("a", 1);
        generations.register("b", 1);
        WaiterCapacity capacity = new WaiterCapacity(generations, 2, 1);

        WaiterCapacity.Waiter first = capacity.reserve(generations.activeGeneration("a"), "exec-a");
        assertThatThrownBy(() -> capacity.reserve(generations.activeGeneration("a"), "exec-a"))
                .isInstanceOfSatisfying(InvocationQuotaExceededException.class,
                        failure -> assertThat(failure.resource())
                                .isEqualTo(InvocationQuotaExceededException.Resource.WAITER));
        WaiterCapacity.Waiter second = capacity.reserve(generations.activeGeneration("b"), "exec-b");
        assertThatThrownBy(() -> capacity.reserve(generations.activeGeneration("b"), "exec-b"))
                .isInstanceOf(InvocationQuotaExceededException.class);

        assertThat(capacity.reservedGlobally()).isEqualTo(2);
        assertThat(capacity.retainedWaiters()).isEqualTo(2);
        first.close();
        first.close();
        second.close();

        assertThat(capacity.reservedGlobally()).isZero();
        assertThat(capacity.reservedForFunction("a")).isZero();
        assertThat(capacity.reservedForFunction("b")).isZero();
        assertThat(capacity.retainedWaiters()).isZero();
    }

    @Test
    void lateOldGenerationCloseCannotReleaseAReplacementWaiter() {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("fn", 1);
        WaiterCapacity capacity = new WaiterCapacity(generations, 2, 2);
        FunctionGeneration oldGeneration = generations.activeGeneration("fn");
        WaiterCapacity.Waiter old = capacity.reserve(oldGeneration, "old-exec");

        generations.remove("fn");
        generations.register("fn", 1);
        FunctionGeneration replacementGeneration = generations.activeGeneration("fn");
        WaiterCapacity.Waiter replacement = capacity.reserve(replacementGeneration, "new-exec");

        assertThat(capacity.reservedForGeneration(oldGeneration)).isOne();
        assertThat(capacity.reservedForGeneration(replacementGeneration)).isOne();

        old.close();
        old.close();
        assertThat(capacity.reservedGlobally()).isOne();
        assertThat(capacity.reservedForFunction("fn")).isOne();
        assertThat(capacity.reservedForGeneration(oldGeneration)).isZero();
        assertThat(capacity.reservedForGeneration(replacementGeneration)).isOne();
        replacement.close();
        assertThat(capacity.reservedGlobally()).isZero();
        assertThat(capacity.reservedForGeneration(replacementGeneration)).isZero();
        assertThat(capacity.retainedWaiters()).isZero();
    }

    @Test
    void closeFencesAdmissionAndDrainsPublishedWaiters() {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("fn", 1);
        WaiterCapacity capacity = new WaiterCapacity(generations, 2, 2);
        WaiterCapacity.Waiter waiter = capacity.reserve(generations.activeGeneration("fn"), "exec");

        capacity.close();

        assertThat(capacity.reservedGlobally()).isZero();
        assertThat(capacity.retainedWaiters()).isZero();
        waiter.close();
        assertThatThrownBy(() -> capacity.reserve(generations.activeGeneration("fn"), "late"))
                .isInstanceOf(InvocationQuotaExceededException.class);
    }
}
