package it.unimib.datai.nanofaas.modules.offload.oneshot.actuation;

import it.unimib.datai.nanofaas.controlplane.deployment.*;
import it.unimib.datai.nanofaas.controlplane.registry.ReplicaQuiesceToken;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.Assignment;
import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.*;
import it.unimib.datai.nanofaas.p2papi.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadinessObservationTest {
    @Test
    void confirmationDeadlineKeepsPublishedSafePlanAndCloudFallback() {
        var nano = new AtomicLong(100);
        var control = ReplicaPlanActuatorTest.control(3, 2);
        when(control.supportsEpochReplicaControl(any())).thenReturn(true);
        when(control.pauseReplicaAdmission(any(), any())).thenAnswer(a -> Optional.of(new ReplicaQuiesceToken(
                a.getArgument(0), a.getArgument(1), new ReplicaAdmissionToken(UUID.randomUUID().toString()))));
        when(control.resumeRetainedReplicas(any(), any(), any(), anyString())).thenReturn(true);
        var peers = mock(PeerTransport.class);
        var requestedTimeouts = new ArrayList<Duration>();
        var local = ReplicaPlanActuatorTest.peers("a");
        when(peers.localEndpoint()).thenAnswer(a -> local.localEndpoint());
        when(peers.subscribe(anyString(), any())).thenAnswer(a -> (PeerSubscription) () -> {});
        when(peers.request(anyString(), anyString(), any(), any())).thenAnswer(a -> {
            requestedTimeouts.add(a.getArgument(3));
            nano.addAndGet(Duration.ofSeconds(11).toNanos());
            return Mono.error(new IllegalStateException("peer transport unavailable"));
        });
        var label = Instant.EPOCH;
        var grant = new Assignment("grant", "a", "a-run", "b", "b-run", "f", "v", 7, 3, 1, 2, false);
        var base = ReplicaPlanActuatorTest.assignedOutcome("a", label, grant);
        var outcome = new EpochOutcome(base.epoch(), base.status(), base.snapshot(), base.auctionNanos(),
                base.solverNanos(), base.rounds(), base.reason(), base.input(),
                EpochTiming.capture("session", label, label.plusSeconds(300), Duration.ofSeconds(5), nano::get));
        try (var actuator = new ReplicaPlanActuator(control,
                () -> List.of(ReplicaPlanActuatorTest.registered("f", 1)), peers,
                () -> label, () -> false, Duration.ofSeconds(10), Duration.ofSeconds(1))) {
            actuator.start();
            assertThat(actuator.prepare(outcome, Duration.ofSeconds(10), Duration.ofSeconds(5)).block().status()).isEqualTo(PlanActivation.Status.NEGOTIATED);
            var result = actuator.activate("session", 1).block(Duration.ofSeconds(3));
            assertThat(requestedTimeouts.getFirst()).isGreaterThan(Duration.ofMillis(200));
            assertThat(requestedTimeouts.getFirst()).isLessThanOrEqualTo(Duration.ofSeconds(5));
            assertThat(result.status()).as(result.reason()).isEqualTo(PlanActivation.Status.DEGRADED);
            assertThat(result.plan()).isNotNull();
            assertThat(result.plan().functions().get("f").outbound()).isEmpty();
            assertThat(result.plan().functions().get("f").cloudRate()).isGreaterThan(0);
            assertThat(actuator.activePlan()).contains(result.plan());
            var diagnostics = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()
                    .valueToTree(result).path("diagnostics");
            assertThat(diagnostics.isArray()).isTrue();
            assertThat(diagnostics.size()).isEqualTo(1);
            assertThat(diagnostics.get(0).path("assignmentId").asText()).isEqualTo("grant");
            assertThat(diagnostics.get(0).path("negotiatedQuantity").asLong()).isEqualTo(2);
            assertThat(diagnostics.get(0).path("confirmedQuantity").asLong()).isZero();
            assertThat(diagnostics.get(0).path("reason").asText()).isEqualTo("READY_NOT_RECEIVED");
        }
    }
}
