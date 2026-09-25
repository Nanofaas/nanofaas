package it.unimib.datai.nanofaas.controlplane.capacity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvocationCapacityTest {

    @Test
    void inputReservationErrorRollsBackTheExecutionReservation() {
        FunctionCapacityRegistry registry = registry("fn");
        ResourceQuota executions = new ResourceQuota(registry, 4, 4);
        ResourceQuota inputs = mock(ResourceQuota.class);
        when(inputs.tryReserve(any(), any(), anyLong()))
                .thenThrow(new AssertionError("input allocator failed"));
        InvocationCapacity capacity = new InvocationCapacity(registry, executions, inputs, 16);

        assertThatThrownBy(() -> capacity.reserve("fn", "e1", 10))
                .isInstanceOf(AssertionError.class)
                .hasMessage("input allocator failed");
        assertThat(executions.reservedGlobally()).isZero();
    }

    @Test
    void globalExecutionLimitAggregatesAcrossFunctionNames() {
        FunctionCapacityRegistry registry = registry("a", "b", "c");
        InvocationCapacity capacity = new InvocationCapacity(registry, 2, 2, 10_000, 10_000, 16);
        InvocationCapacity.Admission first = capacity.reserve("a", "e1", 10);
        InvocationCapacity.Admission second = capacity.reserve("b", "e2", 10);

        assertThatThrownBy(() -> capacity.reserve("c", "e3", 10))
                .isInstanceOf(InvocationQuotaExceededException.class)
                .extracting("resource")
                .isEqualTo(InvocationQuotaExceededException.Resource.EXECUTION);
        assertThat(capacity.executionReservedGlobally()).isEqualTo(2);
        first.rollback();
        second.rollback();
        assertThat(capacity.executionReservedGlobally()).isZero();
    }

    @Test
    void perFunctionLimitIncludesOldAndNewGenerations() {
        FunctionCapacityRegistry registry = registry("fn");
        InvocationCapacity capacity = new InvocationCapacity(registry, 10, 2, 10_000, 10_000, 16);
        InvocationCapacity.Admission old = capacity.reserve("fn", "old", 10);
        old.publish();
        registry.remove("fn");
        registry.register("fn", 1);
        InvocationCapacity.Admission current = capacity.reserve("fn", "new", 10);
        current.publish();

        assertThatThrownBy(() -> capacity.reserve("fn", "too-many", 10))
                .isInstanceOf(InvocationQuotaExceededException.class);
        assertThat(capacity.executionReservedForFunction("fn")).isEqualTo(2);

        old.logicalExecution().close();
        old.canonicalInput().close();
        assertThat(capacity.executionReservedForFunction("fn")).isEqualTo(1);
        current.logicalExecution().close();
        current.canonicalInput().close();
    }

    @Test
    void inputSaturationRollsBackTheDistinctExecutionReservation() {
        FunctionCapacityRegistry registry = registry("fn");
        InvocationCapacity capacity = new InvocationCapacity(registry, 4, 4, 100, 100, 16);
        InvocationCapacity.Admission first = capacity.reserve("fn", "e1", 80);
        first.publish();

        assertThatThrownBy(() -> capacity.reserve("fn", "e2", 30))
                .isInstanceOf(InvocationQuotaExceededException.class)
                .extracting("resource")
                .isEqualTo(InvocationQuotaExceededException.Resource.INPUT);
        assertThat(capacity.executionReservedGlobally()).isEqualTo(1);
        assertThat(capacity.inputReservedGlobally()).isEqualTo(80);

        first.logicalExecution().close();
        first.canonicalInput().close();
    }

    @Test
    void canonicalInputDrainsOnlyAfterBaseAndEveryPhysicalReaderClose() {
        FunctionCapacityRegistry registry = registry("fn");
        InvocationCapacity capacity = new InvocationCapacity(registry, 4, 4, 100, 100, 16);
        InvocationCapacity.Admission admission = capacity.reserve("fn", "e1", 80);
        admission.publish();
        RetainedInputLease.Reference local = admission.canonicalInput().retain(
                new ResourceOwner(ResourceOwner.Scope.PHYSICAL_ATTEMPT, "e1/attempt-1/local"));
        RetainedInputLease.Reference callback = admission.canonicalInput().retain(
                new ResourceOwner(ResourceOwner.Scope.PHYSICAL_ATTEMPT, "e1/attempt-1/callback"));

        admission.logicalExecution().close();
        admission.canonicalInput().close();
        assertThat(capacity.executionReservedGlobally()).isZero();
        assertThat(capacity.inputReservedGlobally()).isEqualTo(80);
        local.close();
        assertThat(capacity.inputReservedGlobally()).isEqualTo(80);
        callback.close();
        assertThat(capacity.inputReservedGlobally()).isZero();
    }

    @Test
    void retryReadersShareOneCanonicalReservationWithoutDoubleCharging() {
        FunctionCapacityRegistry registry = registry("fn");
        InvocationCapacity capacity = new InvocationCapacity(registry, 4, 4, 100, 100, 16);
        InvocationCapacity.Admission admission = capacity.reserve("fn", "e1", 60);
        admission.publish();

        RetainedInputLease.Reference first = admission.canonicalInput().retain(owner("attempt-1"));
        first.close();
        RetainedInputLease.Reference retry = admission.canonicalInput().retain(owner("attempt-2"));

        assertThat(capacity.inputReservedGlobally()).isEqualTo(60);
        retry.close();
        admission.logicalExecution().close();
        admission.canonicalInput().close();
        assertThat(capacity.inputReservedGlobally()).isZero();
    }

    @Test
    void inputReferenceCountHasAnExplicitBoundAndNeverChangesQuotaOnRefusal() {
        FunctionCapacityRegistry registry = registry("fn");
        InvocationCapacity capacity = new InvocationCapacity(registry, 4, 4, 100, 100, 2);
        InvocationCapacity.Admission admission = capacity.reserve("fn", "e1", 60);
        admission.publish();
        RetainedInputLease.Reference onlyPhysical = admission.canonicalInput().retain(owner("one"));

        var canonicalInput = admission.canonicalInput();
        var overflow = owner("overflow");
        assertThatThrownBy(() -> canonicalInput.retain(overflow))
                .isInstanceOf(IllegalStateException.class);
        assertThat(capacity.inputReservedGlobally()).isEqualTo(60);

        onlyPhysical.close();
        admission.logicalExecution().close();
        admission.canonicalInput().close();
    }

    @Test
    void concurrentAdmissionNeverExceedsTheGlobalCap() throws Exception {
        FunctionCapacityRegistry registry = registry("fn");
        InvocationCapacity capacity = new InvocationCapacity(registry, 3, 3, 10_000, 10_000, 16);
        CyclicBarrier barrier = new CyclicBarrier(12);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        List<Future<InvocationCapacity.Admission>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                int id = i;
                futures.add(executor.submit(() -> {
                    barrier.await(2, TimeUnit.SECONDS);
                    try {
                        InvocationCapacity.Admission admission = capacity.reserve("fn", "e" + id, 1);
                        admission.publish();
                        return admission;
                    } catch (InvocationQuotaExceededException _) {
                        return null;
                    }
                }));
            }
            List<InvocationCapacity.Admission> admitted = new ArrayList<>();
            for (Future<InvocationCapacity.Admission> future : futures) {
                InvocationCapacity.Admission admission = future.get(3, TimeUnit.SECONDS);
                if (admission != null) admitted.add(admission);
            }
            assertThat(admitted).hasSize(3);
            assertThat(capacity.executionReservedGlobally()).isEqualTo(3);
            admitted.forEach(admission -> {
                admission.logicalExecution().close();
                admission.canonicalInput().close();
            });
            assertThat(capacity.executionReservedGlobally()).isZero();
            assertThat(capacity.inputReservedGlobally()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private static ResourceOwner owner(String identity) {
        return new ResourceOwner(ResourceOwner.Scope.PHYSICAL_ATTEMPT, identity);
    }

    private static FunctionCapacityRegistry registry(String... names) {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        for (String name : names) registry.register(name, 1);
        return registry;
    }
}
