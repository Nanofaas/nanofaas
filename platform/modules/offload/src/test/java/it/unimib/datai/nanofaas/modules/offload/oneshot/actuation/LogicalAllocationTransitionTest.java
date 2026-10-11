package it.unimib.datai.nanofaas.modules.offload.oneshot.actuation;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import it.unimib.datai.nanofaas.controlplane.registry.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LogicalAllocationTransitionTest {
    private static final Instant LABEL = Instant.parse("2020-01-01T00:00:00Z");

    private static final class Fixture implements AutoCloseable {
        final AtomicLong nano = new AtomicLong(100);
        final Map<String, Integer> counts = new HashMap<>(Map.of("f", 0, "g", 0));
        final Map<String, Integer> admission = new HashMap<>();
        final List<String> mutations = new ArrayList<>();
        boolean occupied, healthyClock;
        final ManagedReplicaControl control = mock(ManagedReplicaControl.class);
        final ReplicaPlanActuator actuator;

        Fixture() {
            when(control.generationOf(any())).thenAnswer(a -> new FunctionGeneration(((RegisteredFunction) a.getArgument(0)).name(), 3));
            when(control.acquireReplicaLease(any(), anyString(), any())).thenAnswer(a -> Optional.of(new ReplicaControlLease(
                    a.getArgument(0), UUID.randomUUID().toString(), a.getArgument(1), Long.MAX_VALUE)));
            when(control.renewReplicaLease(any(), any())).thenAnswer(a -> Optional.of(a.getArgument(0)));
            when(control.ownsReplicaLease(any())).thenReturn(true);
            when(control.supportsPhysicalReplicaControl(any())).thenReturn(true);
            when(control.supportsEpochReplicaControl(any())).thenReturn(true);
            when(control.pauseReplicaAdmission(any(), any())).thenAnswer(a -> Optional.of(new ReplicaQuiesceToken(
                    a.getArgument(0), a.getArgument(1), new ReplicaAdmissionToken(UUID.randomUUID().toString()))));
            when(control.resumeRetainedReplicas(any(), any(), any(), anyString())).thenReturn(true);
            when(control.setReadyConcurrency(any(), anyInt())).thenAnswer(a -> {
                admission.put(((ReplicaControlLease) a.getArgument(0)).generation().functionName(), a.getArgument(1));
                return true;
            });
            when(control.observeReplicaStatus(any())).thenAnswer(a -> {
                int count = counts.get(((ManagedDeploymentTarget) a.getArgument(0)).functionName());
                return ReplicaObservation.fresh(new ReplicaStatus(count, count), LABEL);
            });
            when(control.setReplicas(any(ReplicaControlLease.class), any(), anyInt())).thenAnswer(a -> {
                String name = ((ManagedDeploymentTarget) a.getArgument(1)).functionName();
                int target = a.getArgument(2);
                if (occupied && name.equals("f") && target < counts.get(name)) return false;
                counts.put(name, target);
                mutations.add(name + "=" + target);
                assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(2);
                return true;
            });
            actuator = new ReplicaPlanActuator(control, () -> List.of(ReplicaPlanActuatorTest.registered("f", 1),
                    ReplicaPlanActuatorTest.registered("g", 1)), ReplicaPlanActuatorTest.peers("a"),
                    () -> LABEL.plus(Duration.ofDays(500)), () -> healthyClock, Duration.ofSeconds(2), Duration.ofSeconds(1));
            actuator.start();
        }
        EpochOutcome outcome(long epoch, double f, double g) {
            var from = LABEL.plusSeconds((epoch - 1) * 300);
            var base = ReplicaPlanActuatorTest.memoryOutcome(epoch, from, f, g);
            return new EpochOutcome(base.epoch(), base.status(), base.snapshot(), base.auctionNanos(),
                    base.solverNanos(), base.rounds(), base.reason(), base.input(),
                    EpochTiming.capture("session", from, from.plusSeconds(300), Duration.ofSeconds(5), nano::get));
        }
        void firstAllocation() {
            assertThat(actuator.prepare(outcome(1, 2, 0)).block().status().name()).isEqualTo("NEGOTIATED");
            assertThat(counts).containsEntry("f", 0).containsEntry("g", 0);
            assertThat(actuator.activate("session", 1).block().status()).isEqualTo(PlanActivation.Status.PREPARED);
            assertThat(counts).containsEntry("f", 2).containsEntry("g", 0);
        }
        public void close() { actuator.close(); }
    }

    @Test
    void fullMemoryChangesAllocationAtItsOwnActivationWithoutWaitingAnotherEpoch() {
        try (var f = new Fixture()) {
            f.firstAllocation();
            assertThat(f.actuator.prepare(f.outcome(2, 0, 2)).block().status().name()).isEqualTo("NEGOTIATED");
            assertThat(f.counts).containsEntry("f", 2).containsEntry("g", 0);
            assertThat(f.actuator.activePlan().orElseThrow().epoch()).isEqualTo(1);
            var result = f.actuator.activate("session", 2).block();
            assertThat(result.status()).as(result.reason()).isEqualTo(PlanActivation.Status.PREPARED);
            assertThat(result.readyReplicas()).containsEntry("f", 0).containsEntry("g", 2);
            assertThat(result.plan().functions().get("g").cloudRate()).isZero();
            assertThat(f.mutations).containsExactly("f=2", "f=0", "g=2");
            assertThat(f.admission).containsEntry("f", 0).containsEntry("g", 2);
            assertThat(f.actuator.activate("session", 2).block()).isEqualTo(result);
            assertThat(f.mutations).hasSize(3);
        }
    }

    @Test
    void localQueueIsAvailableDuringPhysicalTransitionOnly() {
        try (var f = new Fixture()) {
            assertThat(f.actuator.transitionQueueNode("f")).isEmpty();
            f.firstAllocation();
            f.actuator.prepare(f.outcome(2, 0, 2)).block();
            assertThat(f.actuator.transitionQueueNode("f")).isEmpty();
            var observed = new java.util.concurrent.atomic.AtomicBoolean();
            doAnswer(a -> {
                assertThat(f.actuator.activePlan()).isEmpty();
                assertThat(f.actuator.transitionQueueNode("f")).contains("a");
                assertThat(f.actuator.transitionQueueNode("g")).contains("a");
                assertThat(f.actuator.transitionQueueNode("unknown")).isEmpty();
                observed.set(true);
                return Optional.of(new ReplicaQuiesceToken(a.getArgument(0), a.getArgument(1),
                        new ReplicaAdmissionToken(UUID.randomUUID().toString())));
            }).when(f.control).pauseReplicaAdmission(any(), any());
            assertThat(f.actuator.activate("session", 2).block().status()).isEqualTo(PlanActivation.Status.PREPARED);
            assertThat(observed).isTrue();
            assertThat(f.actuator.transitionQueueNode("f")).isEmpty();
        }
    }

    @Test
    void unprovenPhysicalDrainKeepsMemoryReservedAndDoesNotGrowAnotherFunction() {
        try (var f = new Fixture()) {
            f.firstAllocation();
            f.occupied = true;
            f.actuator.prepare(f.outcome(2, 0, 2)).block();
            assertThat(f.actuator.activate("session", 2).block().status()).isEqualTo(PlanActivation.Status.FAILED);
            assertThat(f.counts).containsEntry("f", 2).containsEntry("g", 0);
            assertThat(f.mutations).containsExactly("f=2");
            assertThat(f.actuator.activePlan()).isEmpty();
            assertThat(f.admission).containsEntry("f", 0).containsEntry("g", 0);
        }
    }

    @Test
    void stopAndRestartDuringFinalValidationCannotPublishTheRetiredPlan() {
        try (var f = new Fixture()) {
            f.healthyClock = true;
            var resumed = new java.util.concurrent.atomic.AtomicBoolean();
            var readinessObserved = new java.util.concurrent.atomic.AtomicBoolean();
            var restarted = new java.util.concurrent.atomic.AtomicBoolean();
            when(f.control.resumeRetainedReplicas(any(), any(), any(), anyString())).thenAnswer(a -> { resumed.set(true);return true; });
            doAnswer(a -> {
                var name = ((ManagedDeploymentTarget) a.getArgument(0)).functionName();
                if (name.equals("g") && resumed.get()) readinessObserved.set(true);
                int count = f.counts.get(name);
                return ReplicaObservation.fresh(new ReplicaStatus(count, count), LABEL);
            }).when(f.control).observeReplicaStatus(any());
            doAnswer(a -> {
                if (readinessObserved.get() && restarted.compareAndSet(false, true)) {
                    f.actuator.stop();f.actuator.start();
                }
                return new FunctionGeneration(((RegisteredFunction) a.getArgument(0)).name(), 3);
            }).when(f.control).generationOf(any());
            f.actuator.prepare(f.outcome(1, 2, 0)).block();
            assertThat(f.actuator.activate("session", 1).block().status()).isEqualTo(PlanActivation.Status.FAILED);
            assertThat(restarted).isTrue();
            assertThat(f.actuator.activePlan()).isEmpty();
        }
    }
}
