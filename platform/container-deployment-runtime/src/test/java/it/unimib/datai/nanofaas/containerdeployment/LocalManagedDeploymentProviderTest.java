package it.unimib.datai.nanofaas.containerdeployment;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalManagedDeploymentProviderTest {
    @ParameterizedTest
    @ValueSource(strings = {"http://10.90.0.2:8080", "http://nanofaas-echo-r1:8080"})
    void provisionUsesTheEndpointReturnedByTheAdapter(String endpoint) {
        ContainerRuntimeAdapter adapter = mock(ContainerRuntimeAdapter.class);
        EndpointProbe probe = mock(EndpointProbe.class);
        ManagedFunctionProxy proxy = mock(ManagedFunctionProxy.class);
        when(adapter.runContainer(any())).thenReturn(
                new ManagedContainer("nanofaas-echo-r1", 1, endpoint, true));
        when(proxy.endpointUrl()).thenReturn("http://127.0.0.1:19090/invoke");
        LocalManagedDeploymentProvider provider = new LocalManagedDeploymentProvider("test-runtime",
                new LocalDeploymentSettings(null, Duration.ofSeconds(5), Duration.ofMillis(10)),
                adapter, probe, factoryReturning(proxy));


        provider.provision(spec());

        verify(probe).awaitReady(endpoint, Duration.ofSeconds(5), Duration.ofMillis(10));
        verify(proxy).updateBackends(List.of(endpoint));
    }

    @Test
    void reconcileUsesDiscoveredEndpointAndTheBackendNamingHooks() {
        ContainerRuntimeAdapter adapter = mock(ContainerRuntimeAdapter.class);
        EndpointProbe probe = mock(EndpointProbe.class);
        ManagedFunctionProxy proxy = mock(ManagedFunctionProxy.class);
        when(proxy.endpointUrl()).thenReturn("http://127.0.0.1:19090/invoke");
        when(adapter.listManagedContainers("echo")).thenReturn(List.of(
                new ManagedContainer("owned-echo-instance1", 1, "http://10.90.0.5:8080", true)));
        LocalManagedDeploymentProvider provider = new LocalManagedDeploymentProvider("test-runtime",
                new LocalDeploymentSettings(null, Duration.ofSeconds(5), Duration.ofMillis(10)),
                adapter, probe, factoryReturning(proxy)) {
            @Override
            protected String containerNamePrefix(String functionName) {
                return "owned-" + functionName;
            }

            @Override
            protected String containerName(String functionName, int index) {
                return containerNamePrefix(functionName) + "-instance" + index;
            }
        };

        ProvisionResult result = provider.reconcile(spec(), 1,
                Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, "owned-echo"));

        assertThat(result.backendId()).isEqualTo("test-runtime");
        assertThat(result.deploymentObjects()).containsEntry(ProvisionResult.CONTAINER_NAME_PREFIX, "owned-echo");
        verify(probe).awaitReady("http://10.90.0.5:8080", Duration.ofSeconds(5), Duration.ofMillis(10));
        verify(proxy).updateBackends(List.of("http://10.90.0.5:8080"));
    }

    private static FunctionSpec spec() {
        return new FunctionSpec("echo", "img:latest", List.of(), Map.of(), null,
                30_000, 4, 100, 3, null, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 5,
                        List.of(new ScalingMetric("queue_depth", "5", null))));
    }

    private static RoundRobinFunctionProxyFactory factoryReturning(ManagedFunctionProxy proxy) {
        RoundRobinFunctionProxyFactory factory = mock(RoundRobinFunctionProxyFactory.class);
        when(factory.create(anyString())).thenReturn(proxy);
        return factory;
    }
}
