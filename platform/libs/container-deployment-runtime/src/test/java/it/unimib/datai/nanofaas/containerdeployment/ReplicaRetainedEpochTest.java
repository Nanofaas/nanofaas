package it.unimib.datai.nanofaas.containerdeployment;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ReplicaRetainedEpochTest {
    @Test
    void unchangedAllocationRotatesAdmissionWhileOldPhysicalWorkKeepsItsSlot() {
        var slots = new ReplicaSlots();
        slots.update(Map.of("a", "inc-a", "b", "inc-b"));
        var first = slots.beginQuiesce();
        assertThat(slots.resume(first, first.admissionEpoch())).isTrue();
        var old = slots.tryAcquire("old", "1", first.admissionEpoch()).orElseThrow();
        old.markDispatched();
        var next = slots.beginQuiesce();
        assertThat(slots.resume(next, next.admissionEpoch())).isFalse();
        assertThat(slots.resumeRetained(next, next.admissionEpoch())).isTrue();
        assertThat(slots.occupied()).containsExactly(old);
        assertThat(slots.tryAcquire("stale", "1", first.admissionEpoch())).isEmpty();
        var fresh = slots.tryAcquire("fresh", "1", next.admissionEpoch()).orElseThrow();
        assertThat(fresh.backend()).isNotEqualTo(old.backend());
        assertThat(slots.tryAcquire("full", "1", next.admissionEpoch())).isEmpty();
        assertThat(old.markReleased(new ExecutionObservation("RELEASED", old.incarnation(), "old", "2", true))).isFalse();
        assertThat(slots.tryAcquire("still-full", "1", next.admissionEpoch())).isEmpty();
        assertThat(old.markReleased(new ExecutionObservation("RELEASED", old.incarnation(), "old", "1", true))).isTrue();
        assertThat(slots.tryAcquire("reuse", "1", next.admissionEpoch()).orElseThrow().backend()).isEqualTo(old.backend());
    }

    @Test
    void retainedRotationCannotConsumeStaleForeignOrInventedCapabilities() {
        var slots = new ReplicaSlots();
        slots.update(Map.of("a", "inc-a"));
        var old = slots.beginQuiesce();
        var next = slots.beginQuiesce();
        assertThat(slots.resumeRetained(old, old.admissionEpoch())).isFalse();
        assertThat(slots.resumeRetained(next, "invented")).isFalse();
        var foreign = new ReplicaSlots().beginQuiesce();
        assertThat(slots.resumeRetained(foreign, foreign.admissionEpoch())).isFalse();
        assertThat(slots.tryAcquire("closed")).isEmpty();
        assertThat(slots.resumeRetained(next, next.admissionEpoch())).isTrue();
        assertThat(slots.resumeRetained(next, next.admissionEpoch())).isFalse();
    }

    @Test
    void membershipOrIncarnationChangesKeepRetainedAdmissionClosed() {
        var slots = new ReplicaSlots();
        slots.update(Map.of("a", "inc-a"));
        var token = slots.beginQuiesce();
        slots.update(Map.of("a", "replacement"));
        assertThat(slots.resumeRetained(token, token.admissionEpoch())).isFalse();
        var fresh = slots.beginQuiesce();
        assertThat(slots.resumeRetained(fresh, fresh.admissionEpoch())).isFalse();
        assertThat(slots.tryAcquire("closed", "1", fresh.admissionEpoch())).isEmpty();
    }

    @Test
    void permanentlyDrainingReplicaCannotBeReopenedWithoutPhysicalDrain() {
        var slots = new ReplicaSlots();
        slots.update(Map.of("a", "inc-a"));
        var old = slots.tryAcquire("old", "1").orElseThrow();
        old.markDispatched();
        slots.beginDrain("a");
        var token = slots.beginQuiesce();
        assertThat(slots.resumeRetained(token, token.admissionEpoch())).isFalse();
        assertThat(slots.occupied()).containsExactly(old);
        assertThat(slots.tryAcquire("closed", "1", token.admissionEpoch())).isEmpty();
    }
}
