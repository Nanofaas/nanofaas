package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.containerdeployment.ContainerInstanceSpec;
import it.unimib.datai.nanofaas.containerdeployment.ManagedContainer;
import it.unimib.datai.nanofaas.containerdeployment.ManagedFunctionProxy;
import it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxyFactory;
import it.unimib.datai.nanofaas.containerdeployment.EndpointProbe;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ResourceQuantity;
import it.unimib.datai.nanofaas.common.model.ResourceSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerLocalDeploymentProviderTest {

    @Test
    void reconcileAdoptsOwnedLegacyPrefixAndKeepsItForScaleUp() {
        var adapter = new RecordingContainerRuntimeAdapter();
        adapter.managedContainers(List.of(new ManagedContainer("nanofaas-echo-r1", 1, "http://127.0.0.1:19001", true)));
        var proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        var provider = provider(adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(), new FixedPortAllocator(19002), name -> proxy);
        var result = provider.reconcile(spec("echo", 1), 1,
                Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, "nanofaas-echo"));
        provider.setReplicas("echo", 2);
        assertThat(result.deploymentObjects()).containsEntry(ProvisionResult.CONTAINER_NAME_PREFIX, "nanofaas-echo");
        assertThat(adapter.startedSpecs()).extracting(ContainerInstanceSpec::containerName)
                .containsExactly("nanofaas-echo-r2");
        assertThat(adapter.removedContainers()).isEmpty();
        var restarted = provider(adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(), new FixedPortAllocator(19003), name -> proxy);
        var recovered = restarted.reconcile(spec("echo", 2), 2, result.deploymentObjects());
        assertThat(recovered.deploymentObjects()).isEqualTo(result.deploymentObjects());
        assertThat(adapter.startedSpecs()).hasSize(1);
    }

    @Test
    void distinctFunctionNamesDoNotReplaceEachOther() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        var provider = provider(adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(), new FixedPortAllocator(19001, 19002, 19003, 19004, 19005, 19006),
                name -> new RecordingProxy("http://127.0.0.1:19090/invoke"));
        for (String name : List.of("Echo", "echo", "foo_bar", "foo-bar", "Écho", "écho")) {
            provider.provision(spec(name, 1));
        }
        assertThat(adapter.startedSpecs().stream().map(ContainerInstanceSpec::containerName)).doesNotHaveDuplicates();
        assertThat(adapter.removedContainers()).isEmpty();
    }

    @Test
    void provision_startsMinReplicasAndReturnsStableProxyEndpoint() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(19001, 19002),
                functionName -> proxy
        );

        ProvisionResult result = provider.provision(spec("echo", 2));

        assertThat(result.backendId()).isEqualTo("container-local");
        assertThat(result.endpointUrl()).isEqualTo("http://127.0.0.1:19090/invoke");
        assertThat(adapter.startedPorts()).containsExactly(19001, 19002);
        assertThat(proxy.backends()).containsExactly(
                "http://127.0.0.1:19001",
                "http://127.0.0.1:19002"
        );
    }

    @Test
    void provision_pushesFunctionTimeoutAndAdmissionBoundToProxy() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(19001, 19002),
                functionName -> proxy
        );

        // spec("echo", 2) → concurrency 4, timeout 30_000 ms, 2 replicas.
        provider.provision(spec("echo", 2));

        // Admission bound = replicas * per-replica concurrency; single-hop timeout = function timeout.
        assertThat(proxy.maxInFlight()).isEqualTo(8);
        assertThat(proxy.singleHopTimeout()).isEqualTo(Duration.ofMillis(30_000));
    }

    @Test
    void updateSpec_pushesTheNewTimeoutAndConcurrencyToTheProxy() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(19001, 19002),
                functionName -> proxy
        );
        provider.provision(spec("echo", 2));

        // A PATCH raises the function's own timeout and per-replica concurrency. The proxy is
        // where this hop actually runs: keeping the provisioning-time values would cut the call
        // at 30 s while the caller is still well inside its new budget — the fixed-timeout 504
        // this provider was changed to stop producing.
        provider.updateSpec(spec("echo", 2, null, 120_000, 8));

        assertThat(proxy.singleHopTimeout()).isEqualTo(Duration.ofMillis(120_000));
        assertThat(proxy.maxInFlight()).isEqualTo(16);
    }

    @Test
    void updateSpec_ofAnUnknownFunctionIsIgnored() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(19001, 19002),
                functionName -> proxy
        );

        assertThat(catchThrowable(() -> provider.updateSpec(spec("never-provisioned", 1)))).isNull();
    }

    @Test
    void setReplicas_refreshesAdmissionBoundAndKeepsFunctionTimeout() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(19001, 19002, 19003),
                functionName -> proxy
        );

        provider.provision(spec("echo", 1));
        assertThat(proxy.maxInFlight()).isEqualTo(4);

        provider.setReplicas("echo", 3);

        assertThat(proxy.backends()).hasSize(3);
        assertThat(proxy.maxInFlight()).isEqualTo(12);
        assertThat(proxy.singleHopTimeout()).isEqualTo(Duration.ofMillis(30_000));
    }

    @Test
    void provision_onDockerNetworkUsesContainerDnsWithoutAllocatingHostPorts() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties(
                        "docker-java",
                        "127.0.0.1",
                        Duration.ofSeconds(5),
                        Duration.ofMillis(10),
                        "http://control-plane:8080/v1/internal/executions",
                        "nanofaas"
                ),
                new ReadyEndpointProbe(),
                () -> {
                    throw new AssertionError("networked replicas must not allocate host ports");
                },
                functionName -> proxy
        );

        provider.provision(spec("Word_Stats", 2));

        assertThat(adapter.startedPorts()).containsExactly(null, null);
        assertThat(proxy.backends()).containsExactly(
                "http://nanofaas-word-stats-f1153f98e2d7b875-r1:8080",
                "http://nanofaas-word-stats-f1153f98e2d7b875-r2:8080"
        );
    }

    @Test
    void provision_reportsThePrefixTheReplicaContainersAreActuallyNamedWith() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(19001, 19002),
                functionName -> proxy
        );

        // An upper-case name proves the prefix carries the provider's normalisation,
        // which is the part a client cannot reproduce from the function name alone.
        ProvisionResult result = provider.provision(spec("Word_Stats", 2));

        String prefix = result.deploymentObjects().get(ProvisionResult.CONTAINER_NAME_PREFIX);
        assertThat(prefix).isEqualTo("nanofaas-word-stats-f1153f98e2d7b875");
        assertThat(adapter.startedSpecs().stream().map(ContainerInstanceSpec::containerName))
                .containsExactly(prefix + "-r1", prefix + "-r2");
    }

    @Test
    void setReplicas_scalesUpAndDownAndTracksReadyReplicas() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        MutableEndpointProbe probe = new MutableEndpointProbe();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                probe,
                new FixedPortAllocator(19001, 19002, 19003),
                functionName -> proxy
        );

        provider.provision(spec("echo", 1));
        probe.markReady("http://127.0.0.1:19001");

        provider.setReplicas("echo", 3);
        probe.markReady("http://127.0.0.1:19002");
        probe.markReady("http://127.0.0.1:19003");

        assertThat(provider.getReadyReplicas("echo")).isEqualTo(3);
        assertThat(proxy.backends()).containsExactly(
                "http://127.0.0.1:19001",
                "http://127.0.0.1:19002",
                "http://127.0.0.1:19003"
        );

        provider.setReplicas("echo", 1);

        assertThat(adapter.removedContainers()).containsExactly("nanofaas-echo-092c79e8f80e559e-r3", "nanofaas-echo-092c79e8f80e559e-r2");
        assertThat(proxy.backends()).containsExactly("http://127.0.0.1:19001");
        assertThat(provider.getReadyReplicas("echo")).isEqualTo(1);
    }

    @Test
    void replicaStatus_distinguishesDesiredFromReadyReplicas() {
        MutableEndpointProbe probe = new MutableEndpointProbe();
        ContainerLocalDeploymentProvider provider = provider(
                new RecordingContainerRuntimeAdapter(),
                new ContainerLocalProperties(
                        "docker",
                        "127.0.0.1",
                        Duration.ofSeconds(5),
                        Duration.ofMillis(10),
                        null
                ),
                probe,
                new FixedPortAllocator(19001, 19002),
                functionName -> new RecordingProxy("http://127.0.0.1:19090/invoke")
        );
        provider.provision(spec("echo", 2));
        probe.markNotReady("http://127.0.0.1:19002");

        assertThat(provider.getReplicaStatus("echo"))
                .isEqualTo(new ReplicaStatus(2, 1));
    }

    @Test
    void provisionWithZeroMinReplicas_scalesToOneAndReportsReadyStatus() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        MutableEndpointProbe probe = new MutableEndpointProbe();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                probe,
                new FixedPortAllocator(19001),
                functionName -> proxy
        );

        ProvisionResult result = provider.provision(spec("echo", 0));

        assertThat(result.endpointUrl()).isEqualTo("http://127.0.0.1:19090/invoke");
        assertThat(adapter.startedSpecs()).isEmpty();
        assertThat(proxy.backends()).isEmpty();
        assertThat(proxy.isClosed()).isFalse();

        provider.setReplicas("echo", 1);
        probe.markReady("http://127.0.0.1:19001");

        assertThat(adapter.startedPorts()).containsExactly(19001);
        assertThat(proxy.backends()).containsExactly("http://127.0.0.1:19001");
        assertThat(provider.getReplicaStatus("echo")).isEqualTo(new ReplicaStatus(1, 1));
    }

    @Test
    void provision_passesResourcesToEveryReplica() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(19001, 19002),
                functionName -> new RecordingProxy("http://127.0.0.1:19090/invoke")
        );
        ResourceSpec resources = new ResourceSpec(
                new ResourceQuantity(new BigDecimal("0.25"), 256),
                new ResourceQuantity(BigDecimal.ONE, 512)
        );

        provider.provision(spec("echo", 2, resources));

        assertThat(adapter.startedSpecs()).extracting(ContainerInstanceSpec::resources)
                .containsExactly(resources, resources);
    }

    @Test
    void supports_rejectsImagePullSecretsInFirstMilestone() {
        ContainerLocalDeploymentProvider provider = provider(
                new RecordingContainerRuntimeAdapter(),
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(19001),
                functionName -> new RecordingProxy("http://127.0.0.1:19090/invoke")
        );

        FunctionSpec spec = new FunctionSpec(
                "echo",
                "img:latest",
                List.of(),
                Map.of(),
                null,
                30_000,
                4,
                100,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                RuntimeMode.HTTP,
                null,
                null,
                List.of("regcred")
        );

        assertThat(provider.supports(spec)).isFalse();
    }

    @Test
    void provision_startFailure_closesFailedProxyAndAllowsRetry() {
        FailOnceContainerRuntimeAdapter adapter = new FailOnceContainerRuntimeAdapter();
        RecordingProxy firstProxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        RecordingProxy secondProxy = new RecordingProxy("http://127.0.0.1:19091/invoke");
        AtomicInteger proxyCreations = new AtomicInteger();
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(19001, 19002),
                functionName -> proxyCreations.getAndIncrement() == 0 ? firstProxy : secondProxy
        );

        FunctionSpec provisionSpec = spec("echo", 1);
        assertThatThrownBy(() -> provider.provision(provisionSpec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        assertThat(firstProxy.isClosed()).isTrue();
        assertThat(provider.provision(spec("echo", 1)).endpointUrl()).isEqualTo("http://127.0.0.1:19091/invoke");
        assertThat(proxyCreations.get()).isEqualTo(2);
    }

    @Test
    void provision_startFailureAfterCreation_removesContainer() {
        FailAfterStartContainerRuntimeAdapter adapter = new FailAfterStartContainerRuntimeAdapter();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(19001),
                functionName -> proxy
        );

        FunctionSpec provisionSpec = spec("echo", 1);
        assertThatThrownBy(() -> provider.provision(provisionSpec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("start result lost");

        assertThat(adapter.removedContainers()).containsExactly("nanofaas-echo-092c79e8f80e559e-r1");
        assertThat(proxy.isClosed()).isTrue();
    }

    @Test
    void provision_readinessFailure_removesContainerAndClosesProxy() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new FailingEndpointProbe("probe timeout"),
                new FixedPortAllocator(19001),
                functionName -> proxy
        );

        FunctionSpec provisionSpec = spec("echo", 1);
        assertThatThrownBy(() -> provider.provision(provisionSpec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe timeout");

        assertThat(adapter.removedContainers()).containsExactly("nanofaas-echo-092c79e8f80e559e-r1");
        assertThat(proxy.isClosed()).isTrue();
    }

    @Test
    void provision_secondReplicaFailure_removesAllStartedContainersAndAllowsRetry() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        RecordingProxy firstProxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        RecordingProxy secondProxy = new RecordingProxy("http://127.0.0.1:19091/invoke");
        AtomicInteger proxyCreations = new AtomicInteger();
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new FailSecondOnceEndpointProbe(),
                new FixedPortAllocator(19001, 19002, 19003, 19004),
                functionName -> proxyCreations.getAndIncrement() == 0 ? firstProxy : secondProxy
        );

        FunctionSpec provisionSpec = spec("echo", 2);
        assertThatThrownBy(() -> provider.provision(provisionSpec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("second replica failed");

        assertThat(adapter.removedContainers())
                .containsExactly("nanofaas-echo-092c79e8f80e559e-r2", "nanofaas-echo-092c79e8f80e559e-r1");
        assertThat(firstProxy.isClosed()).isTrue();
        assertThat(provider.provision(spec("echo", 2)).endpointUrl())
                .isEqualTo("http://127.0.0.1:19091/invoke");
        assertThat(proxyCreations.get()).isEqualTo(2);
    }

    @Test
    void provision_failedReplicaCleanup_preservesProvisioningFailure() {
        FailingRemoveContainerRuntimeAdapter adapter = new FailingRemoveContainerRuntimeAdapter();
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new FailSecondOnceEndpointProbe(),
                new FixedPortAllocator(19001, 19002),
                functionName -> new RecordingProxy("http://127.0.0.1:19090/invoke")
        );

        Throwable failure = catchThrowable(() -> provider.provision(spec("echo", 2)));

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("second replica failed");
        assertThat(failure.getSuppressed())
                .extracting(Throwable::getMessage)
                .containsExactly("remove failed");
        assertThat(adapter.removedContainers()).containsExactly("nanofaas-echo-092c79e8f80e559e-r1");
    }

    @Test
    void provision_injectsConfiguredCallbackUrl() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties(
                        "docker",
                        "127.0.0.1",
                        Duration.ofSeconds(5),
                        Duration.ofMillis(10),
                        "http://control-plane.local:8080/v1/internal/executions"
                ),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(19001),
                functionName -> new RecordingProxy("http://127.0.0.1:19090/invoke")
        );

        provider.provision(spec("echo", 1));

        assertThat(adapter.startedSpecs())
                .singleElement()
                .satisfies(startedSpec -> assertThat(startedSpec.env())
                        .containsEntry("CALLBACK_URL", "http://control-plane.local:8080/v1/internal/executions"));
    }

    @Test
    void reconcile_adoptsHealthyOwnedContainersAndCreatesOnlyMissingReplicas() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        adapter.managedContainers(List.of(
                new ManagedContainer("nanofaas-echo-r1", 1, "http://127.0.0.1:31001", true),
                new ManagedContainer("nanofaas-echo-r2", 2, "http://127.0.0.1:31002", true)));
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(),
                functionName -> proxy
        );

        ProvisionResult result = provider.reconcile(spec("echo", 2), 2,
                Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, "nanofaas-echo"));

        assertThat(adapter.removedContainers()).isEmpty();
        assertThat(adapter.startedSpecs()).isEmpty();
        assertThat(provider.getReplicaStatus("echo").readyReplicas()).isEqualTo(2);
        assertThat(result.endpointUrl()).isNotBlank();
        assertThat(proxy.backends()).containsExactly(
                "http://127.0.0.1:31001",
                "http://127.0.0.1:31002"
        );
    }

    @Test
    void reconcile_replacesOnlyUnhealthyOrMissingReplicas() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        adapter.managedContainers(List.of(
                new ManagedContainer("nanofaas-echo-r1", 1, "http://127.0.0.1:31001", true),
                new ManagedContainer("nanofaas-echo-r2", 2, null, false)));
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(31002, 31003),
                functionName -> proxy
        );

        provider.reconcile(spec("echo", 3), 3,
                Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, "nanofaas-echo"));

        assertThat(adapter.removedContainers()).containsExactly("nanofaas-echo-r2");
        assertThat(adapter.startedSpecs().stream().map(ContainerInstanceSpec::containerName))
                .containsExactly("nanofaas-echo-r2", "nanofaas-echo-r3");
        assertThat(provider.getReplicaStatus("echo").readyReplicas()).isEqualTo(3);
        assertThat(proxy.backends()).containsExactly(
                "http://127.0.0.1:31001",
                "http://127.0.0.1:31002",
                "http://127.0.0.1:31003"
        );
    }

    @Test
    void reconcile_removesOnlyReplicasAboveThePersistedTarget() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        adapter.managedContainers(List.of(
                new ManagedContainer("nanofaas-echo-r1", 1, "http://127.0.0.1:31001", true),
                new ManagedContainer("nanofaas-echo-r2", 2, "http://127.0.0.1:31002", true),
                new ManagedContainer("nanofaas-echo-r3", 3, "http://127.0.0.1:31003", true)));
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(),
                functionName -> proxy
        );

        provider.reconcile(spec("echo", 1), 1,
                Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, "nanofaas-echo"));

        assertThat(adapter.removedContainers()).containsExactly("nanofaas-echo-r2", "nanofaas-echo-r3");
        assertThat(adapter.startedSpecs()).isEmpty();
        assertThat(proxy.backends()).containsExactly("http://127.0.0.1:31001");
        assertThat(provider.getReplicaStatus("echo").readyReplicas()).isEqualTo(1);
    }

    @Test
    void deprovision_removesDiscoveredOwnedContainersWhenRestoreDidNotBuildState() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        adapter.managedContainers(List.of(
                new ManagedContainer("nanofaas-echo-r1", 1, "http://127.0.0.1:31001", true),
                new ManagedContainer("nanofaas-echo-r2", 2, "http://127.0.0.1:31002", true)));
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(19001),
                functionName -> new RecordingProxy("http://127.0.0.1:19090/invoke")
        );

        provider.deprovision("echo");

        assertThat(adapter.removedContainers()).containsExactly("nanofaas-echo-r1", "nanofaas-echo-r2");
    }

    @Test
    void reconcile_creationFailure_removesOnlyContainersCreatedDuringReconcile() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        adapter.managedContainers(List.of(
                new ManagedContainer("nanofaas-echo-r1", 1, "http://127.0.0.1:31001", true)));
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new FailNthOnceEndpointProbe(3, "third replica failed"),
                new FixedPortAllocator(31002, 31003),
                functionName -> proxy
        );

        Throwable failure = catchThrowable(() -> provider.reconcile(spec("echo", 3), 3,
                Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, "nanofaas-echo")));

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("third replica failed");
        assertThat(adapter.removedContainers())
                .containsExactly("nanofaas-echo-r3", "nanofaas-echo-r2");
        assertThat(proxy.isClosed()).isTrue();
    }

    @Test
    void reconcile_wrongPersistedPrefix_failsWithoutRemovingAdoptedContainers() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        adapter.managedContainers(List.of(
                new ManagedContainer("nanofaas-echo-r1", 1, "http://127.0.0.1:31001", true)));
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(),
                functionName -> proxy
        );

        FunctionSpec functionSpec = spec("echo", 1);
        Map<String, String> wrongPrefix = Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, "nanofaas-other");
        assertThatThrownBy(() -> provider.reconcile(functionSpec, 1, wrongPrefix))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(adapter.removedContainers()).isEmpty();
        assertThat(adapter.startedSpecs()).isEmpty();
        assertThat(proxy.isClosed()).isFalse();
    }

    @Test
    void reconcile_duplicateReplicaIndex_failsWithoutRemovingAdoptedContainers() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        adapter.managedContainers(List.of(
                new ManagedContainer("nanofaas-echo-r1", 1, "http://127.0.0.1:31001", true),
                new ManagedContainer("nanofaas-echo-r1", 1, "http://127.0.0.1:31002", true)));
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = provider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(),
                functionName -> proxy
        );

        FunctionSpec functionSpec = spec("echo", 2);
        Map<String, String> objects = Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, "nanofaas-echo");
        assertThatThrownBy(() -> provider.reconcile(functionSpec, 2, objects))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(adapter.removedContainers()).isEmpty();
        assertThat(adapter.startedSpecs()).isEmpty();
        assertThat(proxy.isClosed()).isFalse();
    }

    private static ContainerLocalDeploymentProvider provider(RecordingContainerRuntimeAdapter adapter,
            ContainerLocalProperties properties, EndpointProbe probe, PortAllocator ports,
            Function<String, ManagedFunctionProxy> proxies) {
        adapter.ports = ports;
        adapter.properties = properties;
        RoundRobinFunctionProxyFactory factory = mock(RoundRobinFunctionProxyFactory.class);
        when(factory.create(anyString())).thenAnswer(invocation -> proxies.apply(invocation.getArgument(0)));
        return new ContainerLocalDeploymentProvider(adapter, properties, probe, factory);
    }

    private static FunctionSpec spec(String name, int minReplicas) {
        return spec(name, minReplicas, null);
    }

    private static FunctionSpec spec(String name, int minReplicas, ResourceSpec resources) {
        return spec(name, minReplicas, resources, 30_000, 4);
    }

    private static FunctionSpec spec(String name, int minReplicas, ResourceSpec resources,
                                     int timeoutMs, int concurrency) {
        return new FunctionSpec(
                name,
                "img:latest",
                List.of("java", "-jar", "app.jar"),
                new LinkedHashMap<>(Map.of("APP_MODE", "dev")),
                resources,
                timeoutMs,
                concurrency,
                100,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                RuntimeMode.HTTP,
                null,
                new ScalingConfig(ScalingStrategy.INTERNAL, minReplicas, 5, List.of(new ScalingMetric("queue_depth", "5", null)))
        );
    }

    @Test
    void getReadyReplicas_otherFunction_doesNotBlockDuringSlowProvision() throws Exception {
        CountDownLatch provisionBlocker = new CountDownLatch(1);
        CountDownLatch provisionStarted = new CountDownLatch(1);
        BlockingEndpointProbe probe = new BlockingEndpointProbe(provisionStarted, provisionBlocker);
        RecordingProxy slowProxy = new RecordingProxy("http://127.0.0.1:19090/invoke");

        ContainerLocalDeploymentProvider provider = provider(
                new RecordingContainerRuntimeAdapter(),
                new ContainerLocalProperties("docker", "127.0.0.1",
                        Duration.ofSeconds(10), Duration.ofMillis(10), null),
                probe,
                new FixedPortAllocator(19001),
                functionName -> slowProxy
        );

        // Start provisioning "slow" in background — will block on the probe
        Thread provisionThread = Thread.ofVirtual()
                .start(() -> {
                    try {
                        provider.provision(spec("slow", 1));
                    } catch (Exception _) {
                        // ignore: a provision failure in this background thread is expected,
                        // because the latch wait and the timing checks below are the real assertions.
                    }
                });

        // Wait until provision is actually inside awaitReady (holding the lock)
        assertThat(provisionStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // Run getReadyReplicas("other") in its own thread so we can time it without deadlocking.
        // If it blocks (because provision holds this-monitor), the future times out and we fail.
        CompletableFuture<Long> readyFuture = CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            provider.getReadyReplicas("other");
            return (System.nanoTime() - start) / 1_000_000;
        });

        try {
            long elapsedMs = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> readyFuture.get(500, TimeUnit.MILLISECONDS),
                    "getReadyReplicas for a different function blocked for more than 500 ms while provision held the lock");
            assertThat(elapsedMs).as("getReadyReplicas for a different function must not block during provision of another")
                    .isLessThan(500);
        } finally {
            // Always release so provision thread can finish and no threads leak.
            provisionBlocker.countDown();
        }

        provisionThread.join(3_000);
    }

    private static class RecordingContainerRuntimeAdapter implements ContainerRuntimeAdapter {
        private final List<ContainerInstanceSpec> started = new ArrayList<>();
        private final List<Integer> startedPorts = new ArrayList<>();
        private PortAllocator ports;
        private ContainerLocalProperties properties;
        private final List<String> removed = new ArrayList<>();
        private List<ManagedContainer> managedContainers = List.of();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void pullImage(String image) {
            // no-op: the recording adapter assumes images are already present
        }

        @Override
        public ManagedContainer runContainer(ContainerInstanceSpec spec) {
            started.add(spec);
            Integer hostPort = properties.networkName() == null ? ports.nextPort() : null;
            startedPorts.add(hostPort);
            String url = hostPort == null ? "http://" + spec.containerName() + ":8080"
                    : "http://" + properties.bindHost() + ":" + hostPort;
            var container = new ManagedContainer(spec.containerName(),
                    ContainerLocalDeploymentProvider.replicaIndex(spec.containerName()), url, true);
            var updated = new ArrayList<>(managedContainers);
            updated.add(container);
            managedContainers = List.copyOf(updated);
            return container;
        }

        @Override
        public void removeContainer(String containerName) {
            removed.add(containerName);
            managedContainers = managedContainers.stream().filter(c -> !c.name().equals(containerName)).toList();
        }

        @Override
        public List<ManagedContainer> listManagedContainers(String functionName) {
            return managedContainers;
        }

        void managedContainers(List<ManagedContainer> containers) {
            this.managedContainers = List.copyOf(containers);
        }

        List<Integer> startedPorts() {
            return startedPorts;
        }

        List<ContainerInstanceSpec> startedSpecs() {
            return started;
        }

        List<String> removedContainers() {
            return removed;
        }
    }

    private static final class FailOnceContainerRuntimeAdapter extends RecordingContainerRuntimeAdapter {
        private boolean failNext = true;

        @Override
        public ManagedContainer runContainer(ContainerInstanceSpec spec) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("boom");
            }
            return super.runContainer(spec);
        }
    }

    private static final class FailingRemoveContainerRuntimeAdapter extends RecordingContainerRuntimeAdapter {
        @Override
        public void removeContainer(String containerName) {
            if (containerName.endsWith("-r2")) {
                throw new IllegalStateException("remove failed");
            }
            super.removeContainer(containerName);
        }
    }

    private static final class FailAfterStartContainerRuntimeAdapter extends RecordingContainerRuntimeAdapter {
        @Override
        public ManagedContainer runContainer(ContainerInstanceSpec spec) {
            super.runContainer(spec);
            throw new IllegalStateException("start result lost");
        }
    }

    private static final class ReadyEndpointProbe implements EndpointProbe {
        @Override
        public void awaitReady(String baseUrl, Duration timeout, Duration pollInterval) {
            // No-op for deterministic unit tests.
        }

        @Override
        public boolean isReady(String baseUrl) {
            return true;
        }
    }

    private static final class MutableEndpointProbe implements EndpointProbe {
        private final Set<String> readyEndpoints = new LinkedHashSet<>();

        void markReady(String baseUrl) {
            readyEndpoints.add(baseUrl);
        }

        void markNotReady(String baseUrl) {
            readyEndpoints.remove(baseUrl);
        }

        @Override
        public void awaitReady(String baseUrl, Duration timeout, Duration pollInterval) {
            readyEndpoints.add(baseUrl);
        }

        @Override
        public boolean isReady(String baseUrl) {
            return readyEndpoints.contains(baseUrl);
        }
    }

    private record FailingEndpointProbe(String message) implements EndpointProbe {
        @Override
        public void awaitReady(String baseUrl, Duration timeout, Duration pollInterval) {
            throw new IllegalStateException(message);
        }

        @Override
        public boolean isReady(String baseUrl) {
            return false;
        }
    }

    private static final class FailSecondOnceEndpointProbe implements EndpointProbe {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void awaitReady(String baseUrl, Duration timeout, Duration pollInterval) {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("second replica failed");
            }
        }

        @Override
        public boolean isReady(String baseUrl) {
            return true;
        }
    }

    private static final class FailNthOnceEndpointProbe implements EndpointProbe {
        private final int failOnCall;
        private final String message;
        private final AtomicInteger calls = new AtomicInteger();

        FailNthOnceEndpointProbe(int failOnCall, String message) {
            this.failOnCall = failOnCall;
            this.message = message;
        }

        @Override
        public void awaitReady(String baseUrl, Duration timeout, Duration pollInterval) {
            if (calls.incrementAndGet() == failOnCall) {
                throw new IllegalStateException(message);
            }
        }

        @Override
        public boolean isReady(String baseUrl) {
            return true;
        }
    }

    private static final class BlockingEndpointProbe implements EndpointProbe {
        private final CountDownLatch started;
        private final CountDownLatch blocker;

        private BlockingEndpointProbe(CountDownLatch started, CountDownLatch blocker) {
            this.started = started;
            this.blocker = blocker;
        }

        @Override
        public void awaitReady(String baseUrl, Duration timeout, Duration pollInterval) {
            started.countDown();
            try {
                blocker.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean isReady(String baseUrl) {
            return false;
        }
    }

    private static final class FixedPortAllocator implements PortAllocator {
        private final List<Integer> ports;

        private FixedPortAllocator(Integer... ports) {
            this.ports = new ArrayList<>(List.of(ports));
        }

        @Override
        public int nextPort() {
            return ports.removeFirst();
        }
    }

    private static final class RecordingProxy implements ManagedFunctionProxy {
        private final String endpointUrl;
        private List<String> backends = List.of();
        private boolean closed;
        private int maxInFlight = -1;
        private Duration singleHopTimeout;

        private RecordingProxy(String endpointUrl) {
            this.endpointUrl = endpointUrl;
        }

        @Override
        public String endpointUrl() {
            return endpointUrl;
        }

        @Override
        public void updateBackends(List<String> backendBaseUrls) {
            this.backends = List.copyOf(backendBaseUrls);
        }

        @Override
        public void updateLimits(int maxInFlight, Duration singleHopTimeout) {
            this.maxInFlight = maxInFlight;
            this.singleHopTimeout = singleHopTimeout;
        }

        @Override
        public void close() {
            closed = true;
        }

        List<String> backends() {
            return backends;
        }

        int maxInFlight() {
            return maxInFlight;
        }

        Duration singleHopTimeout() {
            return singleHopTimeout;
        }

        boolean isClosed() {
            return closed;
        }
    }
}
