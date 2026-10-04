package it.unimib.datai.nanofaas.containerdeployment;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
class ReplicaSlotsTest {
    @Test void reservesFreeBackendAndRequiresExactReleaseProof() {
        var slots = new ReplicaSlots(); slots.update(Map.of("http://a", "inc-a", "http://b", "inc-b"));
        var first = slots.tryAcquire("first", "attempt-1").orElseThrow();
        var second = slots.tryAcquire("second", "attempt-2").orElseThrow();
        assertThat(first.backend()).isNotEqualTo(second.backend());
        assertThat(slots.tryAcquire("third")).isEmpty();
        assertThat(first.markReleased(new ExecutionObservation("UNKNOWN", "inc-a", "first", "attempt-1"))).isFalse();
        assertThat(first.markReleased(new ExecutionObservation("RELEASED", "old", "first", "attempt-1"))).isFalse();
        assertThat(first.markReleased(new ExecutionObservation("RELEASED", first.incarnation(), "first", "old-attempt"))).isFalse();
        assertThat(first.markReleased(new ExecutionObservation("RELEASED", first.incarnation(), "first", "attempt-1"))).isTrue();
        assertThat(slots.tryAcquire("third")).isPresent();
    }
    @Test void drainAndPoolUpdateCannotForgetActiveLease() {
        var slots = new ReplicaSlots(); slots.update(Map.of("http://a", "inc-a"));
        var lease = slots.tryAcquire("work").orElseThrow(); slots.beginDrain("http://a");
        slots.update(Map.of()); assertThat(slots.drained("http://a")).isFalse();
        slots.update(Map.of("http://a", "inc-a")); assertThat(slots.tryAcquire("new")).isEmpty();
        lease.markReleased(new ExecutionObservation("RELEASED", "inc-a", "work", null));
        assertThat(slots.drained("http://a")).isTrue(); assertThat(slots.tryAcquire("new")).isEmpty();
    }    @Test void onlyNeverDispatchedReservationsCanBeCancelledWithoutProof() {
        var slots=new ReplicaSlots(); slots.update(Map.of("http://a","inc"));
        var unstarted=slots.tryAcquire("unstarted").orElseThrow(); assertThat(unstarted.cancelBeforeDispatch()).isTrue();
        var sent=slots.tryAcquire("sent").orElseThrow(); sent.markDispatched(); assertThat(sent.cancelBeforeDispatch()).isFalse();
        assertThat(slots.tryAcquire("other")).isEmpty();
    }

}
