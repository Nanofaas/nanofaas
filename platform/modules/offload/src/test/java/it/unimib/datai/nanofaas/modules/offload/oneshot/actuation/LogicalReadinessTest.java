package it.unimib.datai.nanofaas.modules.offload.oneshot.actuation;

import it.unimib.datai.nanofaas.controlplane.deployment.*;
import it.unimib.datai.nanofaas.controlplane.registry.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.Assignment;
import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.*;
import it.unimib.datai.nanofaas.p2papi.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class LogicalReadinessTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sellerInstallsInboundBeforeAckAndLostReplyDoesNotInvalidateTheGrant(boolean permanent) {
        var network = new ConcurrentHashMap<String, ReplicaPlanActuatorTest.Wire>();
        var sellerRef = new AtomicReference<ReplicaPlanActuator>();
        var lostReply = new AtomicBoolean();
        var wireA = new ReplicaPlanActuatorTest.Wire("a", network);
        var a = new PeerTransport() {
            @Override public Optional<PeerEndpoint> localEndpoint() { return wireA.localEndpoint(); }
            @Override public List<PeerEndpoint> activeNeighbors() { return wireA.activeNeighbors(); }
            @Override public PeerSubscription subscribe(String topic, PeerReceiver receiver) { return wireA.subscribe(topic, receiver); }
            @Override public Mono<byte[]> request(String peer, String topic, byte[] bytes, Duration timeout) {
                var batch = new AuctionCodec().decodeLogical(bytes).batch();
                return wireA.request(peer, topic, bytes, timeout).flatMap(reply -> {
                    if (batch.phase() == AuctionCodec.Phase.READY_ACK && reply[0] == 1) {
                        var inbound = sellerRef.get().activePlan().orElseThrow().functions().get("f").inbound();
                        assertThat(inbound).hasSize(1);
                        assertThat(inbound.getFirst().quantity()).isEqualTo(1);
                        if (lostReply.compareAndSet(false, true) || permanent) return Mono.error(new IllegalStateException("lost reply"));
                    }
                    return Mono.just(reply);
                });
            }
        };
        var b = new ReplicaPlanActuatorTest.Wire("b", network);
        var label = Instant.EPOCH;
        var grant = new Assignment("grant", "a", "a-run", "b", "b-run", "f", "v", 7, 3, 1, 2, false);
        try (var buyer = new ReplicaPlanActuator(control(3, 2), () -> List.of(ReplicaPlanActuatorTest.registered("f", 1)),
                a, () -> label.plusSeconds(3600), () -> false, Duration.ofSeconds(2), Duration.ofSeconds(1));
             var seller = new ReplicaPlanActuator(control(7, 1), () -> List.of(ReplicaPlanActuatorTest.registered("f", 1)),
                     b, () -> label.minusSeconds(3600), () -> false, Duration.ofSeconds(2), Duration.ofSeconds(1))) {
            sellerRef.set(seller);buyer.start();seller.start();
            buyer.prepare(logical(ReplicaPlanActuatorTest.assignedOutcome("a", label, grant))).block();
            seller.prepare(logical(ReplicaPlanActuatorTest.assignedOutcome("b", label, grant))).block();
            var results = Mono.zip(buyer.activate("session", 1), seller.activate("session", 1)).block(Duration.ofSeconds(5));
            assertThat(lostReply).isTrue();
            assertThat(results.getT1().status()).isEqualTo(PlanActivation.Status.DEGRADED);
            var diagnostic = results.getT1().diagnostics().getFirst();
            assertThat(diagnostic.readyReceived()).isTrue();
            assertThat(diagnostic.ackSent()).isTrue();
            assertThat(diagnostic.ackResponseReceived()).isEqualTo(!permanent);
            // No reply is not proof that the seller failed to receive our ACK.
            assertThat(diagnostic.ackReceived()).isFalse();
            if (permanent) {
                assertThat(results.getT1().plan().functions().get("f").outbound()).isEmpty();
                assertThat(results.getT1().plan().functions().get("f").cloudRate()).isGreaterThan(0);
                assertThat(diagnostic.reason()).isEqualTo(PlanActivation.ConfirmationReason.TRANSPORT_ERROR);
                assertThat(diagnostic.confirmedQuantity()).isZero();
            } else {
                assertThat(results.getT1().plan().functions().get("f").outbound()).hasSize(1);
                assertThat(results.getT1().plan().functions().get("f").outbound().getFirst().quantity()).isEqualTo(1);
                assertThat(diagnostic.reason()).isEqualTo(PlanActivation.ConfirmationReason.PARTIAL_READY);
            }
            assertThat(buyer.activate("session", 1).block()).isEqualTo(results.getT1());
            assertThat(seller.activePlan()).isPresent();
        }
    }

    private static EpochOutcome logical(EpochOutcome base) {
        return new EpochOutcome(base.epoch(), base.status(), base.snapshot(), base.auctionNanos(), base.solverNanos(),
                base.rounds(), base.reason(), base.input(), EpochTiming.capture("session", base.snapshot().validFrom(),
                base.snapshot().validUntil(), Duration.ofSeconds(5), System::nanoTime));
    }
    private static ManagedReplicaControl control(long generation, int ready) {
        var control = ReplicaPlanActuatorTest.control(generation, ready);
        when(control.supportsEpochReplicaControl(any())).thenReturn(true);
        when(control.pauseReplicaAdmission(any(), any())).thenAnswer(a -> Optional.of(new ReplicaQuiesceToken(
                a.getArgument(0), a.getArgument(1), new ReplicaAdmissionToken(UUID.randomUUID().toString()))));
        when(control.resumeRetainedReplicas(any(), any(), any(), anyString())).thenReturn(true);
        return control;
    }
}
