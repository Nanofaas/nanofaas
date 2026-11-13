package it.unimib.datai.nanofaas.containerdeployment;

import it.unimib.datai.nanofaas.common.model.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Dispatch to replicas that are already serving must not wait for slow lifecycle work. */
class DispatchDuringLifecycleTest {
    private final ContainerRuntimeAdapter adapter = mock(ContainerRuntimeAdapter.class);
    private final EndpointProbe probe = mock(EndpointProbe.class);

    DispatchDuringLifecycleTest() {
        when(probe.isReady(anyString())).thenReturn(true);
        when(adapter.runContainer(any())).thenAnswer(call -> {
            ContainerInstanceSpec spec = call.getArgument(0);
            int index = LocalManagedDeploymentProvider.replicaIndex(spec.containerName());
            return new ManagedContainer(spec.containerName(), index, "http://replica" + index, true);
        });
    }

    @Test void scaleUpDoesNotStallDispatchToTheReplicaAlreadyReady() throws Exception {
        var coldStart = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        doAnswer(call -> {
            if ("http://replica2".equals(call.getArgument(0))) { started.countDown(); coldStart.await(); }
            return null;
        }).when(probe).awaitReady(anyString(), any(), any());
        try (var factory = new ManagedReplicaPoolFactory(); var provider = provider(factory)) {
            var deployment = provider.provision(spec());
            var scaling = CompletableFuture.runAsync(() -> provider.setReplicas("f", 2));
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                var target = CompletableFuture.supplyAsync(() ->
                        provider.acquireInvocationTarget("f", deployment.deploymentObjects(), "during", "1").target())
                        .get(1, TimeUnit.SECONDS);
                assertThat(target.replicaId()).isEqualTo("http://replica1");
                assertThat(target.markDispatched()).isTrue();
                target.close();
            } finally {
                coldStart.countDown();
                scaling.get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test void statusProbeDoesNotStallDispatch() throws Exception {
        var slowHealth = new CountDownLatch(1);
        var probing = new CountDownLatch(1);
        try (var factory = new ManagedReplicaPoolFactory(); var provider = provider(factory)) {
            var deployment = provider.provision(spec());
            when(probe.isReady(anyString())).thenAnswer(call -> { probing.countDown(); slowHealth.await(); return true; });
            var polling = CompletableFuture.supplyAsync(() -> provider.getReplicaStatus("f"));
            try {
                assertThat(probing.await(2, TimeUnit.SECONDS)).isTrue();
                var target = CompletableFuture.supplyAsync(() ->
                        provider.acquireInvocationTarget("f", deployment.deploymentObjects(), "during", "1").target())
                        .get(1, TimeUnit.SECONDS);
                assertThat(target).isNotNull();
                target.close();
            } finally {
                slowHealth.countDown();
                assertThat(polling.get(2, TimeUnit.SECONDS).readyReplicas()).isEqualTo(1);
            }
        }
    }

    @Test void statusReadDoesNotWaitForAScaleInProgress() throws Exception {
        var coldStart = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        doAnswer(call -> {
            if ("http://replica2".equals(call.getArgument(0))) { started.countDown(); coldStart.await(); }
            return null;
        }).when(probe).awaitReady(anyString(), any(), any());
        try (var factory = new ManagedReplicaPoolFactory(); var provider = provider(factory)) {
            provider.provision(spec());
            var scaling = CompletableFuture.runAsync(() -> provider.setReplicas("f", 2));
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                var status = CompletableFuture.supplyAsync(() -> provider.getReplicaStatus("f")).get(1, TimeUnit.SECONDS);
                // The replica still starting is not counted until it is tracked.
                assertThat(status.desiredReplicas()).isEqualTo(1);
                assertThat(status.readyReplicas()).isEqualTo(1);
            } finally {
                coldStart.countDown();
                scaling.get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test void aStatusPollOlderThanAScaleUpDoesNotHideTheNewReplica() throws Exception {
        var slowHealth = new CountDownLatch(1);
        var probing = new CountDownLatch(1);
        try (var factory = new ManagedReplicaPoolFactory(); var provider = provider(factory)) {
            var deployment = provider.provision(spec());
            when(probe.isReady(anyString())).thenAnswer(call -> { probing.countDown(); slowHealth.await(); return true; });
            var stalePoll = CompletableFuture.supplyAsync(() -> provider.getReplicaStatus("f"));
            assertThat(probing.await(2, TimeUnit.SECONDS)).isTrue();
            // The scale-up completes while the poll, which saw one replica, is still probing it.
            CompletableFuture.runAsync(() -> provider.setReplicas("f", 2)).get(1, TimeUnit.SECONDS);
            slowHealth.countDown();
            stalePoll.get(2, TimeUnit.SECONDS);

            var a = provider.acquireInvocationTarget("f", deployment.deploymentObjects(), "a", "1").target();
            var b = provider.acquireInvocationTarget("f", deployment.deploymentObjects(), "b", "1").target();
            var c = provider.acquireInvocationTarget("f", deployment.deploymentObjects(), "c", "1").target();
            assertThat(c).as("the new replica is dispatchable: two per replica, three in flight").isNotNull();
            assertThat(new HashSet<>(List.of(a.replicaId(), b.replicaId(), c.replicaId())))
                    .containsExactlyInAnyOrder("http://replica1", "http://replica2");
            a.close(); b.close(); c.close();
        }
    }

    @Test void removingAFunctionLetsInFlightInvocationsFinishFirst() throws Exception {
        try (var factory = new ManagedReplicaPoolFactory(); var provider = provider(factory)) {
            var deployment = provider.provision(spec());
            var inFlight = provider.acquireInvocationTarget("f", deployment.deploymentObjects(), "in-flight", "1").target();
            assertThat(inFlight.markDispatched()).isTrue();

            var removal = CompletableFuture.runAsync(() -> provider.deprovision("f"));
            assertThatThrownBy(() -> removal.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            verify(adapter, never()).removeContainer(anyString());
            assertThat(provider.acquireInvocationTarget("f", deployment.deploymentObjects(), "late", "1").target())
                    .as("a function being removed admits nothing new").isNull();

            inFlight.close();
            removal.get(2, TimeUnit.SECONDS);
            verify(adapter).removeContainer(anyString());
        }
    }

    @Test void removalDoesNotWaitLongerThanTheFunctionTimeoutForAStuckInvocation() throws Exception {
        try (var factory = new ManagedReplicaPoolFactory(); var provider = provider(factory)) {
            var deployment = provider.provision(spec());   // timeoutMs = 1000
            var stuck = provider.acquireInvocationTarget("f", deployment.deploymentObjects(), "stuck", "1").target();
            assertThat(stuck.markDispatched()).isTrue();

            CompletableFuture.runAsync(() -> provider.deprovision("f")).get(3, TimeUnit.SECONDS);

            verify(adapter).removeContainer(anyString());
            stuck.close();
        }
    }

    private LocalManagedDeploymentProvider provider(ManagedReplicaPoolFactory factory) {
        return new LocalManagedDeploymentProvider("container-local",
                new LocalDeploymentSettings(null, Duration.ofSeconds(5), Duration.ofMillis(1)), adapter, probe, factory);
    }

    private static FunctionSpec spec() {
        return new FunctionSpec("f", "image", null, Map.of(), null, 1000, 2, 10, 3, null,
                ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, null);
    }
}
