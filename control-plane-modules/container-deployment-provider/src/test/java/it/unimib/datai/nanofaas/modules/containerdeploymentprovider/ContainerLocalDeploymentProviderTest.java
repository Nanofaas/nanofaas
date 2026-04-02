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

import static org.assertj.core.api.Assertions.assertThat;

class ContainerLocalDeploymentProviderTest {

    @Test
    void provision_startsMinReplicasAndReturnsStableProxyEndpoint() {
        RecordingContainerRuntimeAdapter adapter = new RecordingContainerRuntimeAdapter();
        RecordingProxy proxy = new RecordingProxy("http://127.0.0.1:19090/invoke");
        ContainerLocalDeploymentProvider provider = new ContainerLocalDeploymentProvider(
                adapter,
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10)),
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
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10)),
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
                new ContainerLocalProperties("docker", "127.0.0.1", Duration.ofSeconds(5), Duration.ofMillis(10)),
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

    private static final class RecordingContainerRuntimeAdapter implements ContainerRuntimeAdapter {
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

        List<String> removedContainers() {
            return removed;
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
            // Nothing to do.
        }

        List<String> backends() {
            return backends;
        }
    }
}
