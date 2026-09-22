package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.containerdeployment.EndpointProbe;
import it.unimib.datai.nanofaas.containerdeployment.ManagedFunctionProxy;
import it.unimib.datai.nanofaas.containerdeployment.ManagedFunctionProxyFactory;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerdDeploymentProviderTest {
    private final ContainerdDeploymentProvider provider = new ContainerdDeploymentProvider(
            mock(ContainerRuntimeAdapter.class),
            ContainerdProperties.defaults(Map.of("HOME", "/home/service", "XDG_RUNTIME_DIR", "/run/user/1000")),
            mock(EndpointProbe.class), mock(ManagedFunctionProxyFactory.class));

    @Test
    void namesAreDeterministicBoundedAndAvoidNormalizationCollisions() {
        String longName = "Very Long Function Name With Punctuation_and-many-more-segments".repeat(3);
        assertThat(provider.containerName(longName, Integer.MAX_VALUE)).hasSizeLessThanOrEqualTo(76);
        assertThat(provider.containerName("hello.world", 1)).isNotEqualTo(provider.containerName("hello-world", 1));
        assertThat(provider.containerName("hello.world", 1)).endsWith("-r1");
        assertThat(provider.containerName("hello.world", 2)).endsWith("-r2");
        assertThat(provider.containerName("hello.world", 1)).isEqualTo(provider.containerName("hello.world", 1));
        assertThat(provider.backendId()).isEqualTo("containerd");
    }

    @Test
    void supportsOnlyDeploymentWithoutPullSecrets() {
        assertThat(provider.supports(spec(ExecutionMode.DEPLOYMENT, null))).isTrue();
        assertThat(provider.supports(spec(ExecutionMode.LOCAL, null))).isFalse();
        assertThat(provider.supports(spec(ExecutionMode.DEPLOYMENT, java.util.List.of("secret")))).isFalse();
    }

    @Test
    void provisionPersistsTheActualHashedNamePrefix() {
        ManagedFunctionProxyFactory factory = mock(ManagedFunctionProxyFactory.class);
        ManagedFunctionProxy proxy = mock(ManagedFunctionProxy.class);
        when(factory.create("hello.world")).thenReturn(proxy);
        when(proxy.endpointUrl()).thenReturn("http://127.0.0.1:9000");
        ContainerdDeploymentProvider deployment = new ContainerdDeploymentProvider(
                mock(ContainerRuntimeAdapter.class),
                ContainerdProperties.defaults(Map.of("HOME", "/home/service", "XDG_RUNTIME_DIR", "/run/user/1000")),
                mock(EndpointProbe.class), factory);
        FunctionSpec zeroReplicas = new FunctionSpec("hello.world", "echo:1", null, null, null,
                null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null,
                new ScalingConfig(null, 0, 0, null));

        ProvisionResult result = deployment.provision(zeroReplicas);

        assertThat(result.deploymentObjects()).containsEntry(ProvisionResult.CONTAINER_NAME_PREFIX,
                deployment.containerName("hello.world", 1).replace("-r1", ""));
    }

    private static FunctionSpec spec(ExecutionMode mode, java.util.List<String> secrets) {
        return new FunctionSpec("echo", "echo:1", null, null, null, null, null,
                null, null, null, mode, null, null, null, secrets);
    }
}
