package it.unimib.datai.nanofaas.containerdeployment;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ManagedReplicaPoolTest {
    @Test void busyReplicaDuringMembershipUpdateReturnsAfterCorrelatedRelease() throws Exception {
        var busy = new java.util.concurrent.atomic.AtomicBoolean();
        var probe = new RuntimeExecutionProbe() {
            public String eligibleIncarnation(URI backend) {
                if (busy.get() && backend.getHost().equals("a")) throw new IllegalStateException("active");
                return "inc";
            }
            public ExecutionObservation observe(URI backend, String id) {
                return new ExecutionObservation("RELEASED", "inc", id, "1", true);
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var pool = pool(executor, probe)) {
            pool.enablePhysicalSlots(); pool.updateBackends(List.of("http://a"));
            var active = pool.acquire("active", "1"); assertThat(active.markDispatched()).isTrue();
            busy.set(true); pool.updateBackends(List.of("http://a", "http://b"));
            assertThat(active.executionEvidence().toCompletableFuture().get(1, TimeUnit.SECONDS)).isTrue();
            active.close();
            var first = pool.acquire("next-1", "1"); var second = pool.acquire("next-2", "1");
            assertThat(first).isNotNull(); assertThat(second).isNotNull();
            first.close(); second.close();
        }
    }

    @Test void failedMultiReplicaProbeCannotClearReplacementQuarantine() {
        var stage = new java.util.concurrent.atomic.AtomicInteger();
        var probe = new RuntimeExecutionProbe() {
            public String eligibleIncarnation(URI backend) {
                if (stage.get() == 1 || stage.get() == 2 && backend.getHost().equals("b")) {
                    throw new IllegalStateException("probe unavailable");
                }
                return stage.get() == 2 ? "replacement" : "original";
            }
            public ExecutionObservation observe(URI backend, String id) { return ExecutionObservation.unknown(); }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var pool = pool(executor, probe)) {
            var backends = List.of("http://a", "http://b");
            pool.enablePhysicalSlots(); pool.updateBackends(backends);
            stage.set(1);
            assertThatThrownBy(() -> pool.updateBackends(backends)).isInstanceOf(IllegalStateException.class);
            stage.set(2);
            assertThatThrownBy(() -> pool.updateBackends(backends)).isInstanceOf(IllegalStateException.class);
            assertThat(pool.acquire("must-not-use-old-incarnation", "1")).isNull();
        }
    }

    @Test void transientIdleProbeFailureRecoversOnlyAfterFreshIncarnationProof() {
        var failing = new java.util.concurrent.atomic.AtomicBoolean();
        var probe = new RuntimeExecutionProbe() {
            public String eligibleIncarnation(URI backend) {
                if (failing.get()) throw new IllegalStateException("probe unavailable");
                return "same-incarnation";
            }
            public ExecutionObservation observe(URI backend, String id) { return ExecutionObservation.unknown(); }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var pool = pool(executor, probe)) {
            pool.enablePhysicalSlots(); pool.updateBackends(List.of("http://a"));
            failing.set(true);
            assertThatThrownBy(() -> pool.updateBackends(List.of("http://a"))).isInstanceOf(IllegalStateException.class);
            pool.updateReadyBackends(List.of("http://a"));
            assertThat(pool.acquire("unverified", "1")).isNull();
            failing.set(false); pool.updateBackends(List.of("http://a"));
            try (var next = pool.acquire("recovered", "1")) {
                assertThat(next).isNotNull();
                assertThat(next.markDispatched()).isTrue();
            }
        }
    }

    @Test void failedShrinkRepublishingRetainedBackendReopensOrdinaryAdmission() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var pool = pool(executor, new Probe())) {
            pool.updateBackends(List.of("http://a"));
            var active = pool.acquire("active", "1");
            assertThat(active.markDispatched()).isTrue();
            pool.beginDrain("http://a");
            assertThat(pool.awaitDrained("http://a", Duration.ofMillis(1))).isFalse();
            pool.updateBackends(List.of("http://a"));
            active.close();
            try (var next = pool.acquire("next", "1")) {
                assertThat(next).isNotNull();
                assertThat(next.markDispatched()).isTrue();
            }
        }
    }

    @Test void roundRobinRespectsReplicaAndFunctionCapacityAndUpdatedLimits() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var pool = pool(executor, new Probe())) {
            pool.updateBackends(List.of("http://a", "http://b"));
            pool.updateLimits(3, 2, Duration.ofSeconds(1));
            var a = pool.acquire("a", "1"); var b = pool.acquire("b", "1"); var c = pool.acquire("c", "1");
            assertThat(List.of(a.replicaId(), b.replicaId(), c.replicaId())).containsExactly("http://a", "http://b", "http://a");
            assertThat(pool.acquire("full", "1")).isNull();
            a.close(); a.close();
            pool.updateLimits(1, 1, Duration.ofSeconds(1));
            assertThat(pool.acquire("reduced", "1")).isNull();
            b.close(); c.close();
            try (var next = pool.acquire("next", "1")) { assertThat(next.endpointUrl()).isEqualTo("http://b/invoke"); }
        }
    }
    @Test void concurrentAcquisitionsNeverOverbookAndDrainFencesUnsentTargets() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var pool = pool(executor, new Probe())) {
            pool.updateBackends(List.of("http://a", "http://b")); pool.updateLimits(2, 1, Duration.ofSeconds(1));
            var futures = new java.util.ArrayList<Future<it.unimib.datai.nanofaas.controlplane.deployment.ManagedInvocationTarget>>();
            for (int i = 0; i < 20; i++) { final int id = i; futures.add(executor.submit(() -> pool.acquire("id" + id, "1"))); }
            var targets = new java.util.ArrayList<it.unimib.datai.nanofaas.controlplane.deployment.ManagedInvocationTarget>();
            for (var f : futures) { var target = f.get(2, TimeUnit.SECONDS); if (target != null) targets.add(target); }
            assertThat(targets).hasSize(2);
            pool.beginDrain("http://a");
            for (var target : targets) { assertThat(target.markDispatched()).isEqualTo(target.replicaId().equals("http://b")); target.close(); }
            assertThat(pool.awaitDrained("http://a", Duration.ofMillis(100))).isTrue();
        }
    }
    @Test void physicalTimeoutRequiresCorrelatedReleaseProofAndNeverAttributesAnAdmissionRejection() throws Exception {
        var probe = new Probe();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var pool = pool(executor, probe)) {
            pool.enablePhysicalSlots(); pool.updateBackends(List.of("http://a"));
            var target = pool.acquire("work", "2"); assertThat(target.markDispatched()).isTrue(); target.close();
            assertThat(pool.acquire("other", "1")).isNull();
            probe.observation.set(new ExecutionObservation("RELEASED", "run", "work", "1", true));
            assertThat(pool.awaitDrained("http://a", Duration.ofMillis(10))).isFalse();
            probe.observation.set(new ExecutionObservation("RELEASED", "old-run", "work", "2", true));
            assertThat(pool.awaitDrained("http://a", Duration.ofMillis(10))).isFalse();
            probe.observation.set(new ExecutionObservation("RELEASED", "run", "work", "2", false));
            assertThat(target.executionEvidence().toCompletableFuture().get(1, TimeUnit.SECONDS)).isFalse();
            assertThat(pool.awaitDrained("http://a", Duration.ofSeconds(1))).isTrue();
            try (var next = pool.acquire("next", "1")) { assertThat(next).isNotNull(); }
        }
    }
    @Test void cancellationBeforeSendReturnsPhysicalSlotButClosedPoolCannotSend() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var pool = pool(executor, new Probe())) {
            pool.enablePhysicalSlots(); pool.updateBackends(List.of("http://a"));
            var cancelled = pool.acquire("cancelled", "1"); cancelled.close();
            assertThat(cancelled.markDispatched()).isFalse();
            var next = pool.acquire("next", "1"); assertThat(next).isNotNull();
            pool.close(); assertThat(next.markDispatched()).isFalse(); next.close();
        }
    }
    @Test void physicalRoundRobinDoesNotRedispatchAnUncertainExecutionOnAnotherReplica() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var pool = pool(executor, new Probe())) {
            pool.enablePhysicalSlots(); pool.updateBackends(List.of("http://a", "http://b"));
            var first = pool.acquire("first", "1"); assertThat(first.replicaId()).isEqualTo("http://a"); first.close();
            var next = pool.acquire("work", "1"); assertThat(next.replicaId()).isEqualTo("http://b");
            assertThat(next.markDispatched()).isTrue(); next.close();
            assertThat(pool.acquire("work", "2")).isNull();
            try (var other = pool.acquire("other", "1")) { assertThat(other.replicaId()).isEqualTo("http://a"); }
        }
    }

    @Test void acceptedAdmissionWaitsThroughQuiesceAndCancellationReturnsItsPermit() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var pool = pool(executor, new Probe())) {
            pool.enablePhysicalSlots(); pool.updateBackends(List.of("http://a"));
            pool.updateLimits(2, 1, Duration.ofSeconds(2));
            pool.beginQuiesce();
            var cancelled = pool.acquire("cancelled", "1");
            assertThat(cancelled).isNotNull(); assertThat(cancelled.ready().toCompletableFuture()).isNotDone();
            cancelled.close();
            var accepted = pool.acquire("accepted", "1");
            assertThat(accepted).isNotNull(); assertThat(accepted.ready().toCompletableFuture()).isNotDone();
            pool.updateBackends(List.of("http://b"));
            var token = pool.beginQuiesce(); assertThat(pool.resume(token, token.admissionEpoch())).isTrue();
            accepted.ready().toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertThat(accepted.endpointUrl()).isEqualTo("http://b/invoke");
            assertThat(accepted.markDispatched()).isTrue(); accepted.close();
            assertThat(cancelled.markDispatched()).isFalse();
        }
    }

    @Test void readinessObserversCannotCompleteTheOwnedReservation() {
        var tasks=new java.util.ArrayDeque<Runnable>();
        try(var pool=pool(tasks::add,new Probe())) {
            pool.enablePhysicalSlots(); pool.updateBackends(List.of("http://a"));
            var token=pool.beginQuiesce();
            var accepted=pool.acquire("accepted","1");
            accepted.ready().toCompletableFuture().complete(null);
            assertThat(accepted.ready().toCompletableFuture()).isNotDone();
            var observer=accepted.ready();
            if(observer instanceof Future<?> future) future.cancel(true);
            assertThat(accepted.ready().toCompletableFuture()).isNotDone();
            assertThat(pool.resume(token,token.admissionEpoch())).isTrue();
            tasks.remove().run();
            assertThat(accepted.ready().toCompletableFuture()).isCompleted();
            accepted.close();
        }
    }

    @Test void acceptedWaiterStartingAfterResumeStillWaitsForCapacityUntilItsDeadline() {
        var tasks=new java.util.ArrayDeque<Runnable>();
        try(var pool=pool(tasks::add,new Probe())) {
            pool.enablePhysicalSlots(); pool.updateBackends(List.of("http://a"));
            pool.updateLimits(2,1,Duration.ofMillis(20));
            var token=pool.beginQuiesce();
            var first=pool.acquire("first","1");
            var second=pool.acquire("second","1");
            assertThat(pool.resume(token,token.admissionEpoch())).isTrue();
            tasks.remove().run();
            assertThat(first.ready().toCompletableFuture()).isCompleted();
            // Both were admitted while paused; the second worker starts after resume with no free slot.
            tasks.remove().run();
            assertThat(second.ready().toCompletableFuture()).hasFailedWithThrowableThat().isInstanceOf(TimeoutException.class);
            second.close(); first.close();
            try(var next=pool.acquire("next","1")) { assertThat(next).isNotNull(); }
        }
    }

    @Test void replacedIncarnationIsFencedAndLateOldProofCannotReopenIt() {
        var incarnation = new AtomicReference<>("old");
        var probe = new RuntimeExecutionProbe() {
            public String eligibleIncarnation(URI backend) { return incarnation.get(); }
            public ExecutionObservation observe(URI backend, String id) { return ExecutionObservation.unknown(); }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var pool = pool(executor, probe)) {
            pool.enablePhysicalSlots(); pool.updateBackends(List.of("http://a"));
            var first = pool.acquire("old-work", "1"); first.close();
            incarnation.set("replacement"); pool.updateBackends(List.of("http://a"));
            assertThat(pool.backendReady("http://a")).isFalse();
            assertThat(pool.acquire("new-work", "1")).isNull();
        }
    }

    private static ManagedReplicaPool pool(Executor executor, RuntimeExecutionProbe probe) {
        return new ManagedReplicaPool(probe, executor, Duration.ofSeconds(1));
    }
    private static final class Probe implements RuntimeExecutionProbe {
        final AtomicReference<ExecutionObservation> observation = new AtomicReference<>(ExecutionObservation.unknown());
        public String eligibleIncarnation(URI backend) { return "run"; }
        public ExecutionObservation observe(URI backend, String id) { return observation.get(); }
    }
}
