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
    @Test
    void runFailureDoesNotRemoveForeignResource() {
        var adapter = mock(ContainerRuntimeAdapter.class);
        var proxy = mock(ManagedFunctionProxy.class);
        when(adapter.runContainer(any())).thenThrow(new IllegalStateException("name occupied"));
        when(adapter.listManagedContainers("echo")).thenReturn(List.of());
        var provider = new LocalManagedDeploymentProvider("test-runtime",
                new LocalDeploymentSettings(null, Duration.ofSeconds(1), Duration.ofMillis(10)),
                adapter, mock(EndpointProbe.class), factoryReturning(proxy));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.provision(spec()))
                .isInstanceOf(IllegalStateException.class);
        verify(adapter, org.mockito.Mockito.never()).removeContainer(anyString());
    }

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

    @Test
    void oneShotDownscaleCannotRemoveContainerWithoutPhysicalDrain() {
        var adapter=mock(ContainerRuntimeAdapter.class); var probe=mock(EndpointProbe.class); var proxy=mock(ManagedFunctionProxy.class);
        when(adapter.runContainer(any())).thenReturn(new ManagedContainer("nanofaas-echo-r1",1,"http://127.0.0.1:1234",true));
        when(proxy.endpointUrl()).thenReturn("http://127.0.0.1:19090/invoke");
        var base=spec();
        var spec=new FunctionSpec(base.name(),base.image(),base.command(),Map.of("NANOFAAS_ONE_SHOT_PROFILE","true","NANOFAAS_MAX_CONCURRENT_HANDLERS","1"),base.resources(),base.timeoutMs(),4,base.queueSize(),base.maxRetries(),null,base.executionMode(),base.runtimeMode(),null,base.scalingConfig());
        var provider=new LocalManagedDeploymentProvider("test-runtime",new LocalDeploymentSettings(null,Duration.ofMillis(100),Duration.ofMillis(10)),adapter,probe,factoryReturning(proxy));
        provider.provision(spec);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.setReplicas("echo",0)).isInstanceOf(IllegalStateException.class);
        verify(proxy).beginDrain("http://127.0.0.1:1234");
        verify(adapter,org.mockito.Mockito.never()).removeContainer(anyString());
        when(proxy.awaitDrained(anyString(),any())).thenReturn(true);
        provider.setReplicas("echo",0); verify(adapter).removeContainer("nanofaas-echo-r1");
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
