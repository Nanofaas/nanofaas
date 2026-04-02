package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerLocalDeploymentProviderTest {

    @Test
    void provision_startsMinReplicasAndReturnsStableProxyEndpoint() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = new ContainerLocalDeploymentProvider(
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
    void setReplicas_scalesUpAndDownAndTracksReadyReplicas() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        MutableEndpointProbe probe = new MutableEndpointProbe();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = new ContainerLocalDeploymentProvider(
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

        assertThat(adapter.removedContainers()).containsExactly("nanofaas-echo-r3", "nanofaas-echo-r2");
        assertThat(proxy.backends()).containsExactly("http://127.0.0.1:19001");
        assertThat(provider.getReadyReplicas("echo")).isEqualTo(1);
    }

    @Test
    void supports_rejectsImagePullSecretsInFirstMilestone() {
        ContainerLocalDeploymentProvider provider = new ContainerLocalDeploymentProvider(
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
        ContainerLocalDeploymentProvider provider = new ContainerLocalDeploymentProvider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new ReadyEndpointProbe(),
                new FixedPortAllocator(19001, 19002),
                functionName -> proxyCreations.getAndIncrement() == 0 ? firstProxy : secondProxy
        );

        assertThatThrownBy(() -> provider.provision(spec("echo", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        assertThat(firstProxy.isClosed()).isTrue();
        assertThat(provider.provision(spec("echo", 1)).endpointUrl()).isEqualTo("http://127.0.0.1:19091/invoke");
        assertThat(proxyCreations.get()).isEqualTo(2);
    }

    @Test
    void provision_readinessFailure_removesContainerAndClosesProxy() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = new ContainerLocalDeploymentProvider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10), null),
                new FailingEndpointProbe("probe timeout"),
                new FixedPortAllocator(19001),
                functionName -> proxy
        );

        assertThatThrownBy(() -> provider.provision(spec("echo", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe timeout");

        assertThat(adapter.removedContainers()).containsExactly("nanofaas-echo-r1");
        assertThat(proxy.isClosed()).isTrue();
    }

    @Test
    void provision_injectsConfiguredCallbackUrl() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        ContainerLocalDeploymentProvider provider = new ContainerLocalDeploymentProvider(
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

    private static FunctionSpec spec(String name, int minReplicas) {
        return new FunctionSpec(
                name,
                "img:latest",
                List.of("java", "-jar", "app.jar"),
                new LinkedHashMap<>(Map.of("APP_MODE", "dev")),
                null,
                30_000,
                4,
                100,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                RuntimeMode.HTTP,
                null,
                new ScalingConfig(ScalingStrategy.INTERNAL, minReplicas, 5, List.of(new ScalingMetric("queue_depth", "5", null)))
        );
    }

    private static class RecordingContainerRuntimeAdapter implements ContainerRuntimeAdapter {
        private final List<ContainerInstanceSpec> started = new ArrayList<>();
        private final List<String> removed = new ArrayList<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void runContainer(ContainerInstanceSpec spec) {
            started.add(spec);
        }

        @Override
        public void removeContainer(String containerName) {
            removed.add(containerName);
        }

        List<Integer> startedPorts() {
            return started.stream().map(ContainerInstanceSpec::hostPort).toList();
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
        public void runContainer(ContainerInstanceSpec spec) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("boom");
            }
            super.runContainer(spec);
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
        public void close() {
            closed = true;
        }

        List<String> backends() {
            return backends;
        }

        boolean isClosed() {
            return closed;
        }
    }
}
