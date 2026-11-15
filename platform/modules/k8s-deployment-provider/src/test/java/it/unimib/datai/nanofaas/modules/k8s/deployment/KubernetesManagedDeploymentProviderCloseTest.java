package it.unimib.datai.nanofaas.modules.k8s.deployment;

import io.fabric8.kubernetes.api.model.discovery.v1.EndpointBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedTargetResolution;
import it.unimib.datai.nanofaas.modules.k8s.config.KubernetesProperties;
import it.unimib.datai.nanofaas.modules.k8s.dispatch.KubernetesResourceManager;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Its own server: it is shut down before the provider closes the watch (see EndpointSliceWatcherTest). */
class KubernetesManagedDeploymentProviderCloseTest {

    @Test
    void closingStopsDispatchForEveryFunction() {
        var server = new KubernetesMockServer(new Context(), new MockWebServer(), new HashMap<>(), new KubernetesCrudDispatcher(), false);
        server.init();
        KubernetesClient client = server.createClient();
        @SuppressWarnings("unchecked")
        ObjectProvider<KubernetesClient> clients = mock(ObjectProvider.class);
        when(clients.getObject()).thenReturn(client);
        var provider = new KubernetesManagedDeploymentProvider(
                new KubernetesResourceManager(clients, new KubernetesProperties("default", null)), clients);
        var result = provider.provision(new FunctionSpec("echo", "img:latest", List.of(), Map.of(), null, 30_000, 4, 100, 3, null,
                ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10, List.of())));
        client.discovery().v1().endpointSlices().inNamespace("default").resource(new EndpointSliceBuilder()
                .withNewMetadata().withName("fn-echo-slice").addToLabels("kubernetes.io/service-name", "fn-echo").endMetadata()
                .withAddressType("IPv4")
                .withEndpoints(new EndpointBuilder().withAddresses("10.0.0.1").withNewConditions().withReady(true).endConditions().build())
                .build()).create();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (provider.acquireInvocationTarget("echo", result.deploymentObjects(), "probe", "1").target() == null
                && System.nanoTime() < deadline) Thread.onSpinWait();

        server.destroy();
        assertThatCode(provider::close).doesNotThrowAnyException();

        assertThat(provider.acquireInvocationTarget("echo", result.deploymentObjects(), "after", "1"))
                .isEqualTo(ManagedTargetResolution.UNAVAILABLE);
        client.close();
    }
}
