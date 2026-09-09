package it.unimib.datai.nanofaas.modules.syncqueue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class QueueLeaseGenerationTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void eitherCompletionOrderReleasesOnlyItsOwnGeneration(boolean freshFirst) {
        var capacity = new FunctionCapacityRegistry();
        var queue = new SyncQueueInvocationEnqueuer(capacity);
        var old = capacity.register("fn", 1);
        var oldLease = queue.tryAcquireLease(task());
        capacity.remove("fn");
        var fresh = capacity.register("fn", 1);
        var freshLease = queue.tryAcquireLease(task());
        assertThat(oldLease).isNotNull();
        assertThat(freshLease).isNotNull();
        assertThat(oldLease.generation()).isNotEqualTo(freshLease.generation());
        if (freshFirst) {
            freshLease.release();
            assertThat(fresh.inFlight()).isZero();
            assertThat(old.inFlight()).isEqualTo(1);
            oldLease.release();
        } else {
            oldLease.release();
            assertThat(old.inFlight()).isZero();
            assertThat(fresh.inFlight()).isEqualTo(1);
            freshLease.release();
        }
        oldLease.release(); freshLease.release();
        assertThat(old.inFlight()).isZero();
        assertThat(fresh.inFlight()).isZero();
    }
    private static FunctionSpec spec() {
        return new FunctionSpec("fn", "img", List.of(), Map.of(), null, 10000,
                1, 100, 0, null, ExecutionMode.LOCAL, null, null, null);
    }
    private static InvocationTask task() {
        return new InvocationTask("id", "fn", spec(), new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1, InvocationKind.SYNC);
    }
}
