package it.unimib.datai.nanofaas.containerdeployment;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Scaling by several replicas costs one cold start, not one per replica. */
class ParallelScaleUpTest {
    private final ContainerRuntimeAdapter adapter = mock(ContainerRuntimeAdapter.class);
    private final EndpointProbe probe = mock(EndpointProbe.class);

    ParallelScaleUpTest() {
        when(probe.isReady(anyString())).thenReturn(true);
        when(adapter.runContainer(any())).thenAnswer(call -> {
            ContainerInstanceSpec spec = call.getArgument(0);
            int index = LocalManagedDeploymentProvider.replicaIndex(spec.containerName());
            return new ManagedContainer(spec.containerName(), index, "http://replica" + index, true);
        });
    }

    @Test void newReplicasBecomeReadyConcurrently() throws Exception {
        var allWaiting = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        try (var factory = new ManagedReplicaPoolFactory(); var provider = provider(factory)) {
            provider.provision(spec());
            doAnswer(call -> { allWaiting.countDown(); release.await(); return null; })
                    .when(probe).awaitReady(anyString(), any(), any());
            var scaling = CompletableFuture.runAsync(() -> provider.setReplicas("f", 4));
            try {
                assertThat(allWaiting.await(2, TimeUnit.SECONDS))
                        .as("replicas 2, 3 and 4 wait for readiness at the same time").isTrue();
            } finally {
                release.countDown();
            }
            scaling.get(2, TimeUnit.SECONDS);
            assertThat(provider.getReplicaStatus("f")).isEqualTo(new ReplicaStatus(4, 4));
        }
    }

    @Test void aReplicaThatNeverBecomesReadyLeavesOnlyTheContiguousReadyOnes() {
        try (var factory = new ManagedReplicaPoolFactory(); var provider = provider(factory)) {
            var deployment = provider.provision(spec());
            doAnswer(call -> {
                if ("http://replica3".equals(call.getArgument(0))) throw new IllegalStateException("never ready");
                return null;
            }).when(probe).awaitReady(anyString(), any(), any());

            assertThatThrownBy(() -> provider.setReplicas("f", 4)).hasMessage("never ready");

            // Replica indexes stay contiguous: 4 became ready but sits after the failed 3.
            assertThat(provider.getReplicaStatus("f").desiredReplicas()).isEqualTo(2);
            verify(adapter).removeContainer(endsWith("-r3"));
            verify(adapter).removeContainer(endsWith("-r4"));
            verify(adapter, never()).removeContainer(endsWith("-r2"));
            var a = provider.acquireInvocationTarget("f", deployment.deploymentObjects(), "a", "1").target();
            var b = provider.acquireInvocationTarget("f", deployment.deploymentObjects(), "b", "1").target();
            assertThat(Set.of(a.replicaId(), b.replicaId())).containsExactlyInAnyOrder("http://replica1", "http://replica2");
            a.close(); b.close();
        }
    }

    private LocalManagedDeploymentProvider provider(ManagedReplicaPoolFactory factory) {
        return new LocalManagedDeploymentProvider("container-local",
                new LocalDeploymentSettings(null, Duration.ofSeconds(5), Duration.ofMillis(1)), adapter, probe, factory);
    }

    private static FunctionSpec spec() {
        return new FunctionSpec("f", "image", null, Map.of(), null, 1000, 1, 10, 3, null,
                ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, null);
    }
}
