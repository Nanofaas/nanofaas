package it.unimib.datai.nanofaas.modules.k8s.deployment;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import it.unimib.datai.nanofaas.modules.k8s.dispatch.KubernetesResourceManager;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubernetesManagedDeploymentProviderTest {

    @Test
    void provider_reportsK8sBackendAndDelegatesToResourceManager() {
        KubernetesResourceManager resourceManager = mock(KubernetesResourceManager.class);
        KubernetesManagedDeploymentProvider provider = new KubernetesManagedDeploymentProvider(resourceManager);
        FunctionSpec spec = new FunctionSpec(
                "echo",
                "img:latest",
                null,
                Map.of(),
                null,
                30_000,
                4,
                100,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );
        when(resourceManager.provision(spec)).thenReturn("http://fn-echo.default.svc.cluster.local:8080/invoke");
        when(resourceManager.getReadyReplicas("echo")).thenReturn(3);

        ProvisionResult result = provider.provision(spec);

        assertThat(provider.backendId()).isEqualTo("k8s");
        assertThat(provider.isAvailable()).isTrue();
        assertThat(provider.supports(spec)).isTrue();
        assertThat(result.endpointUrl()).isEqualTo("http://fn-echo.default.svc.cluster.local:8080/invoke");
        assertThat(result.backendId()).isEqualTo("k8s");
        assertThat(provider.getReadyReplicas("echo")).isEqualTo(3);

        provider.setReplicas("echo", 2);
        provider.deprovision("echo");

        verify(resourceManager).setReplicas("echo", 2);
        verify(resourceManager).deprovision("echo");
    }
}
