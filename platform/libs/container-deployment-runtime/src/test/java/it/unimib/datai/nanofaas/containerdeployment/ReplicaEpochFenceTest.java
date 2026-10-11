package it.unimib.datai.nanofaas.containerdeployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ReplicaEpochFenceTest {
    @Test
    void quiesceClosesFreeSlotsWithoutRemovingWarmBackends() {
        var slots = new ReplicaSlots();
        slots.update(Map.of("http://a", "inc-a"));
        var token = slots.beginQuiesce();
        assertThat(slots.tryAcquire("late")).isEmpty();
        assertThat(slots.routable("http://a")).isFalse();
        assertThat(slots.resume(token, token.admissionEpoch())).isTrue();
        var admitted = slots.tryAcquire("new", "1", token.admissionEpoch()).orElseThrow();
        assertThat(admitted.backend()).isEqualTo("http://a");
        assertThat(admitted.incarnation()).isEqualTo("inc-a");
    }

    @Test
    void resumeWaitsForMatchingPhysicalReleaseOfAnOccupiedSlot() {
        var slots = new ReplicaSlots();
        slots.update(Map.of("http://a", "inc-a"));
        var old = slots.tryAcquire("old", "1").orElseThrow();
        old.markDispatched();
        var token = slots.beginQuiesce();
        assertThat(slots.resume(token, token.admissionEpoch())).isFalse();
        assertThat(old.cancelBeforeDispatch()).isFalse();
        assertThat(old.markReleased(new ExecutionObservation("RELEASED", "inc-a", "old", "2", true))).isFalse();
        assertThat(slots.resume(token, token.admissionEpoch())).isFalse();
        assertThat(old.markReleased(new ExecutionObservation("RELEASED", "inc-a", "old", "1", true))).isTrue();
        assertThat(slots.resume(token, token.admissionEpoch())).isTrue();
    }

    @Test
    void retiredOrMissingEpochCannotAcquireAfterSameBackendResumes() {
        var slots = new ReplicaSlots();
        slots.update(Map.of("http://a", "inc-a"));
        var first = slots.beginQuiesce();
        assertThat(slots.resume(first, first.admissionEpoch())).isTrue();
        assertThat(slots.tryAcquire("old", "1", first.admissionEpoch()).orElseThrow().cancelBeforeDispatch()).isTrue();
        var token = slots.beginQuiesce();
        assertThat(slots.resume(token, token.admissionEpoch())).isTrue();
        assertThat(slots.tryAcquire("late", "1", first.admissionEpoch())).isEmpty();
        assertThat(slots.tryAcquire("unfenced")).isEmpty();
        assertThat(slots.tryAcquire("missing", "1", null)).isEmpty();
        assertThat(slots.tryAcquire("new", "1", token.admissionEpoch())).isPresent();
    }

    @Test
    void consumedOrForeignQuiesceTokenCannotChangeAdmissionEpoch() {
        var slots = new ReplicaSlots();
        slots.update(Map.of("http://a", "inc-a"));
        var token = slots.beginQuiesce();
        assertThat(slots.resume(token, token.admissionEpoch())).isTrue();
        assertThat(slots.resume(token, token.admissionEpoch())).isFalse();
        var foreign = new ReplicaSlots();
        foreign.update(Map.of("http://a", "inc-a"));
        assertThat(slots.resume(foreign.beginQuiesce(), "session:1")).isFalse();
        assertThat(slots.tryAcquire("valid", "1", token.admissionEpoch())).isPresent();
    }

    @Test
    void poolMembershipChangeInvalidatesTokenEvenIfOriginalMembershipReturns() {
        var slots = new ReplicaSlots();
        slots.update(Map.of("http://a", "inc-a"));
        var token = slots.beginQuiesce();
        slots.update(Map.of());
        slots.update(Map.of("http://a", "inc-a"));
        assertThat(slots.resume(token, token.admissionEpoch())).isFalse();
        assertThat(slots.tryAcquire("late", "1", "session:1")).isEmpty();
        var fresh = slots.beginQuiesce();
        assertThat(slots.resume(fresh, fresh.admissionEpoch())).isTrue();
    }

    @Test
    void permanentBackendDrainCannotBeReopenedByPoolResume() {
        var slots = new ReplicaSlots();
        slots.update(Map.of("http://a", "inc-a"));
        slots.beginDrain("http://a");
        var token = slots.beginQuiesce();
        assertThat(slots.resume(token, token.admissionEpoch())).isFalse();
        assertThat(slots.tryAcquire("late", "1", "session:1")).isEmpty();
    }

    @Test
    void callerCannotReopenWithARetiredOrInventedAdmissionIdentity() {
        var slots = new ReplicaSlots();
        slots.update(Map.of("http://a", "inc-a"));
        var old = slots.beginQuiesce();
        assertThat(slots.resume(old, old.admissionEpoch())).isTrue();
        var next = slots.beginQuiesce();
        assertThat(slots.resume(next, old.admissionEpoch())).isFalse();
        assertThat(slots.resume(next, "invented")).isFalse();
        assertThat(slots.tryAcquire("late", "1", old.admissionEpoch())).isEmpty();
        assertThat(slots.resume(next, next.admissionEpoch())).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void invalidAdmissionEpochLeavesPoolClosed(String epoch) {
        var slots = new ReplicaSlots();
        slots.update(Map.of("http://a", "inc-a"));
        var token = slots.beginQuiesce();
        assertThatIllegalArgumentException().isThrownBy(() -> slots.resume(token, epoch));
        assertThat(slots.tryAcquire("late")).isEmpty();
        assertThat(slots.resume(token, token.admissionEpoch())).isTrue();
    }
}
