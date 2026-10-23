package it.unimib.datai.nanofaas.modules.offload.oneshot.actuation;

import it.unimib.datai.nanofaas.containerdeployment.*;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import it.unimib.datai.nanofaas.controlplane.registry.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LogicalProviderTransitionTest {
    private static final class PhysicalProxy implements ManagedFunctionProxy {
        final ReplicaSlots slots = new ReplicaSlots();
        public String endpointUrl() { return "http://proxy/invoke"; }
        public void updateBackends(List<String> urls) { slots.update(urls.stream().collect(Collectors.toMap(url -> url, url -> "inc:" + url))); }
        public void enablePhysicalSlots() { }
        public boolean backendReady(String id) { return slots.routable(id); }
        public void beginDrain(String id) { slots.beginDrain(id); }
        public boolean awaitDrained(String id, Duration timeout) { return slots.drained(id); }
        public ReplicaSlots.QuiesceToken beginQuiesce() { return slots.beginQuiesce(); }
        public boolean resumeRetained(ReplicaSlots.QuiesceToken token, String epoch) { return slots.resumeRetained(token, epoch); }
        public void close() { }
    }

    @Test
    void providerReadinessAndRemovalRespectThePhysicalPoolAcrossTransitions() {
        var proxies = new HashMap<String, PhysicalProxy>();
        var factory = mock(RoundRobinFunctionProxyFactory.class);
        when(factory.create(anyString())).thenAnswer(a -> {
            var proxy = new PhysicalProxy();proxies.put(a.getArgument(0), proxy);return proxy;
        });
        var adapter = mock(ContainerRuntimeAdapter.class);
        var live = new HashSet<String>();
        var sequence = new AtomicInteger();
        when(adapter.runContainer(any())).thenAnswer(a -> {
            var spec = (ContainerInstanceSpec) a.getArgument(0);
            live.add(spec.containerName());
            assertThat(live).hasSizeLessThanOrEqualTo(2);
            return new ManagedContainer(spec.containerName(), sequence.incrementAndGet(), "http://" + spec.containerName(), true);
        });
        doAnswer(a -> { live.remove(a.getArgument(0));return null; }).when(adapter).removeContainer(anyString());
        var probe = mock(EndpointProbe.class);when(probe.isReady(anyString())).thenReturn(true);
        var provider = new LocalManagedDeploymentProvider("local",
                new LocalDeploymentSettings(null, Duration.ofMillis(10), Duration.ofMillis(1)), adapter, probe, factory);
        var functions = List.of(ReplicaPlanActuatorTest.registered("f", 1), ReplicaPlanActuatorTest.registered("g", 1));
        functions.forEach(f -> provider.provision(f.spec()));
        var control = mock(ManagedReplicaControl.class);
        when(control.generationOf(any())).thenAnswer(a -> new FunctionGeneration(((RegisteredFunction) a.getArgument(0)).name(), 3));
        when(control.acquireReplicaLease(any(), anyString(), any())).thenAnswer(a -> Optional.of(new ReplicaControlLease(a.getArgument(0), UUID.randomUUID().toString(), a.getArgument(1), Long.MAX_VALUE)));
        when(control.renewReplicaLease(any(), any())).thenAnswer(a -> Optional.of(a.getArgument(0)));
        when(control.ownsReplicaLease(any())).thenReturn(true);
        when(control.supportsPhysicalReplicaControl(any())).thenReturn(true);
        when(control.supportsEpochReplicaControl(any())).thenReturn(true);
        when(control.setReadyConcurrency(any(), anyInt())).thenReturn(true);
        when(control.pauseReplicaAdmission(any(), any())).thenAnswer(a -> {
            var target = (ManagedDeploymentTarget) a.getArgument(1);
            return provider.pauseReplicaAdmission(target.functionName()).map(token -> new ReplicaQuiesceToken(a.getArgument(0), target, token));
        });
        when(control.resumeRetainedReplicas(any(), any(), any(), anyString())).thenAnswer(a -> {
            var token = (ReplicaQuiesceToken) a.getArgument(2);
            return provider.resumeRetainedReplicas(token.target().functionName(), token.admission(), a.getArgument(3));
        });
        when(control.setReplicas(any(ReplicaControlLease.class), any(), anyInt())).thenAnswer(a -> {
            provider.setReplicas(((ManagedDeploymentTarget) a.getArgument(1)).functionName(), a.getArgument(2));return true;
        });
        when(control.observeReplicaStatus(any())).thenAnswer(a -> ReplicaObservation.fresh(
                provider.getReplicaStatus(((ManagedDeploymentTarget) a.getArgument(0)).functionName()), Instant.now()));
        try (var actuator = new ReplicaPlanActuator(control, () -> functions, ReplicaPlanActuatorTest.peers("a"),
                Instant::now, () -> false, Duration.ofSeconds(2), Duration.ofSeconds(1))) {
            actuator.start();
            actuator.prepare(outcome(1, 2, 0)).block();
            var first = actuator.activate("session", 1).block();
            assertThat(first.status()).as(first.reason()).isEqualTo(PlanActivation.Status.PREPARED);
            assertThat(first.readyReplicas()).containsEntry("f", 2);
            var executing = proxies.get("f").slots.tryAcquire("executing", "1").orElseThrow();
            executing.markDispatched();
            actuator.prepare(outcome(2, 0, 2)).block();
            assertThat(actuator.activate("session", 2).block().status()).isEqualTo(PlanActivation.Status.FAILED);
            assertThat(provider.getReplicaStatus("g").desiredReplicas()).isZero();
            assertThat(proxies.get("f").slots.occupied()).containsExactly(executing);
            assertThat(executing.markReleased(new ExecutionObservation("RELEASED", executing.incarnation(), "executing", "1", true))).isTrue();
            actuator.prepare(outcome(3, 0, 2)).block();
            var recovered = actuator.activate("session", 3).block();
            assertThat(recovered.status()).as(recovered.reason()).isEqualTo(PlanActivation.Status.PREPARED);
            assertThat(recovered.readyReplicas()).containsEntry("f", 0).containsEntry("g", 2);
            assertThat(live).hasSize(2);
        } finally {
            functions.forEach(f -> provider.deprovision(f.name()));
        }
    }

    private static EpochOutcome outcome(long epoch, double f, double g) {
        var from = Instant.EPOCH.plusSeconds(epoch * 300);
        var base = ReplicaPlanActuatorTest.memoryOutcome(epoch, from, f, g);
        return new EpochOutcome(base.epoch(), base.status(), base.snapshot(), base.auctionNanos(), base.solverNanos(),
                base.rounds(), base.reason(), base.input(), EpochTiming.capture("session", from, from.plusSeconds(300), Duration.ofSeconds(5), System::nanoTime));
    }
}
