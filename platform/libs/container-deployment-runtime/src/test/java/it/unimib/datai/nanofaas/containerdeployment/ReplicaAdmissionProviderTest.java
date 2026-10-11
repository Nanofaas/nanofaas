package it.unimib.datai.nanofaas.containerdeployment;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaAdmissionToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReplicaAdmissionProviderTest {
    @Test
    void unchangedAllocationRetainsOccupiedLeaseAndDoesNotRecreateContainers() {
        var fixture = new Fixture();
        var old = fixture.proxy.slots.tryAcquire("old", "1").orElseThrow();
        old.markDispatched();
        var token = fixture.provider.pauseReplicaAdmission("f").orElseThrow();
        assertThat(fixture.proxy.slots.tryAcquire("closed")).isEmpty();
        assertThat(fixture.provider.resumeReplicas("f", token, token.admissionEpoch())).isFalse();
        assertThat(fixture.provider.resumeRetainedReplicas("f", token, token.admissionEpoch())).isTrue();
        assertThat(fixture.proxy.slots.occupied()).containsExactly(old);
        assertThat(fixture.proxy.slots.tryAcquire("full", "1")).isEmpty();
        assertThat(fixture.provider.resumeRetainedReplicas("f", token, token.admissionEpoch())).isFalse();
        verify(fixture.adapter, times(1)).runContainer(any());
        verify(fixture.adapter, never()).removeContainer(anyString());
    }

    @Test
    void strictQuiesceFailureLeavesPhysicalOwnershipAndAdmissionClosed() {
        var fixture = new Fixture();
        var old = fixture.proxy.slots.tryAcquire("old", "1").orElseThrow();
        old.markDispatched();
        assertThat(fixture.provider.quiesceReplicas("f", Duration.ofMillis(1))).isEmpty();
        assertThat(fixture.proxy.slots.tryAcquire("closed")).isEmpty();
        assertThat(fixture.proxy.slots.occupied()).containsExactly(old);
        assertThat(old.markReleased(new ExecutionObservation("RELEASED", old.incarnation(), "old", "1", true))).isTrue();
        var token = fixture.provider.quiesceReplicas("f", Duration.ofMillis(1)).orElseThrow();
        assertThat(fixture.provider.resumeReplicas("f", token, token.admissionEpoch())).isTrue();
        verify(fixture.adapter, never()).removeContainer(anyString());
    }

    @Test
    void changedAllocationOrWrongCapabilityCannotReopenAdmission() {
        var fixture = new Fixture();
        var token = fixture.provider.pauseReplicaAdmission("f").orElseThrow();
        assertThat(fixture.provider.resumeRetainedReplicas("f", new ReplicaAdmissionToken("foreign"), "foreign")).isFalse();
        assertThat(fixture.provider.resumeRetainedReplicas("f", token, "invented")).isFalse();
        fixture.provider.setReplicas("f", 2);
        assertThat(fixture.provider.resumeRetainedReplicas("f", token, token.admissionEpoch())).isFalse();
        assertThat(fixture.proxy.slots.tryAcquire("closed", "1")).isEmpty();
        var fresh = fixture.provider.pauseReplicaAdmission("f").orElseThrow();
        assertThat(fixture.provider.resumeRetainedReplicas("f", fresh, fresh.admissionEpoch())).isTrue();
    }

    @Test
    void missingAndLegacyFunctionsCannotIssueEpochCapabilities() {
        var fixture = new Fixture();
        assertThat(fixture.provider.supportsEpochReplicaControl("missing")).isFalse();
        assertThat(fixture.provider.pauseReplicaAdmission("missing")).isEmpty();
        assertThat(fixture.provider.quiesceReplicas("missing", Duration.ofMillis(1))).isEmpty();
        fixture.provider.provision(spec("legacy", Map.of()));
        assertThat(fixture.provider.supportsEpochReplicaControl("legacy")).isFalse();
        assertThat(fixture.provider.pauseReplicaAdmission("legacy")).isEmpty();
    }

    private static FunctionSpec spec(String name, Map<String, String> env) {
        return new FunctionSpec(name, "image", List.of(), env, null, 30_000, 4, 100, 0,
                null, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 5, List.of(new ScalingMetric("queue_depth", "5", null))));
    }

    private static final class Fixture {
        final ContainerRuntimeAdapter adapter = mock(ContainerRuntimeAdapter.class);
        final SlotProxy proxy = new SlotProxy();
        final LocalManagedDeploymentProvider provider;

        Fixture() {
            var factory = mock(RoundRobinFunctionProxyFactory.class);
            when(factory.create(anyString())).thenReturn(proxy);
            when(adapter.runContainer(any())).thenReturn(
                    new ManagedContainer("f-r1", 1, "http://a", true),
                    new ManagedContainer("f-r2", 2, "http://b", true));
            provider = new LocalManagedDeploymentProvider("local",
                    new LocalDeploymentSettings(null, Duration.ofMillis(10), Duration.ofMillis(1)),
                    adapter, mock(EndpointProbe.class), factory);
            provider.provision(spec("f", Map.of("NANOFAAS_ONE_SHOT_PROFILE", "true", "NANOFAAS_MAX_CONCURRENT_HANDLERS", "1")));
        }
    }

    private static final class SlotProxy implements ManagedFunctionProxy {
        final ReplicaSlots slots = new ReplicaSlots();
        @Override public String endpointUrl() { return "http://proxy/invoke"; }
        @Override public void updateBackends(List<String> urls) { slots.update(urls.stream().collect(Collectors.toMap(url -> url, url -> "inc:" + url))); }
        @Override public void enablePhysicalSlots() { }
        @Override public ReplicaSlots.QuiesceToken beginQuiesce() { return slots.beginQuiesce(); }
        @Override public boolean awaitQuiesced(Duration timeout) { return slots.quiesced(); }
        @Override public boolean resume(ReplicaSlots.QuiesceToken token, String epoch) { return slots.resume(token, epoch); }
        @Override public boolean resumeRetained(ReplicaSlots.QuiesceToken token, String epoch) { return slots.resumeRetained(token, epoch); }
        @Override public void close() { }
    }
}
