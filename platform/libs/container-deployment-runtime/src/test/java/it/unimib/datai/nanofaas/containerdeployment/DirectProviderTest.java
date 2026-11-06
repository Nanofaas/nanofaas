package it.unimib.datai.nanofaas.containerdeployment;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectProviderTest {
    @Test void localProvidersPublishNoProxyAndFenceRecreatedOrRestoredDeployments() {
        for (String backend : List.of("container-local", "containerd")) {
            var adapter = mock(ContainerRuntimeAdapter.class);
            var probe = mock(EndpointProbe.class);
            when(probe.isReady(anyString())).thenReturn(true);
            when(adapter.runContainer(any())).thenAnswer(call -> {
                ContainerInstanceSpec spec = call.getArgument(0);
                int index = LocalManagedDeploymentProvider.replicaIndex(spec.containerName());
                return new ManagedContainer(spec.containerName(), index, "http://replica" + index, true);
            });
            try (var factory = new ManagedReplicaPoolFactory();
                 var provider = new LocalManagedDeploymentProvider(backend,
                         new LocalDeploymentSettings(null, Duration.ofMillis(20), Duration.ofMillis(1)), adapter, probe, factory)) {
                var first = provider.provision(spec("f")); assertThat(first.endpointUrl()).isNull();
                try (var target = provider.acquireInvocationTarget("f", first.deploymentObjects(), "one", "1").target()) {
                    assertThat(target.endpointUrl()).isEqualTo("http://replica1/invoke");
                    assertThat(target.markDispatched()).isTrue();
                }
                provider.setReplicas("f", 0);
                assertThat(provider.acquireInvocationTarget("f", first.deploymentObjects(), "zero", "1"))
                        .isEqualTo(ManagedTargetResolution.UNAVAILABLE);
                provider.setReplicas("f", 2);
                var a = provider.acquireInvocationTarget("f", first.deploymentObjects(), "a", "1").target();
                var b = provider.acquireInvocationTarget("f", first.deploymentObjects(), "b", "1").target();
                assertThat(Set.of(a.replicaId(), b.replicaId())).hasSize(2); a.close(); b.close();
                when(probe.isReady(anyString())).thenReturn(false);
                assertThat(provider.getReadyReplicas("f")).isZero();
                assertThat(provider.acquireInvocationTarget("f", first.deploymentObjects(), "unready", "1"))
                        .isEqualTo(ManagedTargetResolution.UNAVAILABLE);
                provider.deprovision("f");
                when(probe.isReady(anyString())).thenReturn(true);
                var replacement = provider.provision(spec("f"));
                assertThat(provider.acquireInvocationTarget("f", first.deploymentObjects(), "old", "1"))
                        .isEqualTo(ManagedTargetResolution.UNAVAILABLE);
                try (var target = provider.acquireInvocationTarget("f", replacement.deploymentObjects(), "new", "1").target()) {
                    assertThat(target).isNotNull();
                }
                when(adapter.listManagedContainers("f")).thenReturn(List.of(new ManagedContainer("nanofaas-f-r1", 1, "http://replica1", true)));
                int creates = mockingDetails(adapter).getInvocations().stream().filter(i -> i.getMethod().getName().equals("runContainer")).toList().size();
                var restored = provider.reconcile(spec("f"), 1, Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, "nanofaas-f"));
                assertThat(restored.endpointUrl()).isNull();
                assertThat(mockingDetails(adapter).getInvocations().stream().filter(i -> i.getMethod().getName().equals("runContainer")).count()).isEqualTo(creates);
                assertThat(provider.acquireInvocationTarget("f", replacement.deploymentObjects(), "stale", "1"))
                        .isEqualTo(ManagedTargetResolution.UNAVAILABLE);
            }
        }
    }
    @Test void statusPollDuringPauseDoesNotPoisonRetainedReplicaReadiness() throws Exception {
        var adapter = mock(ContainerRuntimeAdapter.class);
        var health = mock(EndpointProbe.class);
        when(health.isReady(anyString())).thenReturn(true);
        when(adapter.runContainer(any())).thenReturn(new ManagedContainer("f-r1", 1, "http://a", true));
        var probe = mock(RuntimeExecutionProbe.class);
        when(probe.eligibleIncarnation(any())).thenReturn("inc");
        when(probe.observe(any(), anyString())).thenReturn(ExecutionObservation.unknown());
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
             var pool = new ManagedReplicaPool(probe, executor, Duration.ofMillis(10))) {
            var factory = mock(ManagedReplicaPoolFactory.class);
            when(factory.create(anyString())).thenReturn(pool);
            try (var provider = new LocalManagedDeploymentProvider("container-local",
                    new LocalDeploymentSettings(null, Duration.ofMillis(20), Duration.ofMillis(1)), adapter, health, factory)) {
                var oneShot = new FunctionSpec("f", "image", null,
                        Map.of("NANOFAAS_ONE_SHOT_PROFILE", "true", "NANOFAAS_MAX_CONCURRENT_HANDLERS", "1"),
                        null, 1000, 1, 10, 0, null, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, null);
                var deployment = provider.provision(oneShot);
                var token = provider.pauseReplicaAdmission("f").orElseThrow();
                assertThat(provider.getReadyReplicas("f")).isZero();
                try (var accepted = provider.acquireInvocationTarget("f", deployment.deploymentObjects(), "accepted", "1").target()) {
                    assertThat(accepted).isNotNull();
                    assertThat(provider.resumeRetainedReplicas("f", token, token.admissionEpoch())).isTrue();
                    accepted.ready().toCompletableFuture().get(1, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(accepted.markDispatched()).isTrue();
                }
            }
        }
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "image", null, Map.of(), null, 1000, 2, 10, 3, null,
                ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, null);
    }
}
