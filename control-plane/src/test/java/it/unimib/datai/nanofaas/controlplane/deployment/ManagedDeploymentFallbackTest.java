package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedDeploymentFallbackTest {

    @Test
    void providerAvailable_returnsProvisionResult() {
        ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn("k8s");
        when(provider.isAvailable()).thenReturn(true);
        when(provider.supports(any())).thenReturn(true);
        when(provider.provision(any())).thenReturn(new ProvisionResult(
                "http://svc:8080/invoke",
                "k8s",
                ExecutionMode.DEPLOYMENT,
                null,
                Map.of()
        ));

        DeploymentProviderResolver resolver = new DeploymentProviderResolver(
                List.of(provider),
                new DeploymentProperties(null)
        );

        ProvisionResult result = resolver.resolveAndProvision(spec("fn", null), null);

        assertThat(result.endpointUrl()).isEqualTo("http://svc:8080/invoke");
        assertThat(result.backendId()).isEqualTo("k8s");
        assertThat(result.effectiveExecutionMode()).isEqualTo(ExecutionMode.DEPLOYMENT);
        assertThat(result.degradationReason()).isNull();
    }

    @Test
    void noProvider_withEndpoint_degradesToPool() {
        DeploymentProviderResolver resolver = new DeploymentProviderResolver(
                List.of(),
                new DeploymentProperties(null)
        );

        ProvisionResult result = resolver.resolveAndProvision(
                spec("fn", "http://external:8080/invoke"),
                null
        );

        assertThat(result.endpointUrl()).isEqualTo("http://external:8080/invoke");
        assertThat(result.backendId()).isNull();
        assertThat(result.effectiveExecutionMode()).isEqualTo(ExecutionMode.POOL);
        assertThat(result.degradationReason()).contains("No managed deployment provider");
    }

    @Test
    void noProvider_withoutEndpoint_rejectsRegistration() {
        DeploymentProviderResolver resolver = new DeploymentProviderResolver(
                List.of(),
                new DeploymentProperties(null)
        );

        assertThatThrownBy(() -> resolver.resolveAndProvision(spec("fn", null), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No managed deployment provider");
    }

    @Test
    void fallback_neverDegradesToLocal() {
        DeploymentProviderResolver resolver = new DeploymentProviderResolver(
                List.of(),
                new DeploymentProperties(null)
        );

        ProvisionResult result = resolver.resolveAndProvision(
                spec("fn", "http://external:8080/invoke"),
                null
        );

        assertThat(result.effectiveExecutionMode()).isNotEqualTo(ExecutionMode.LOCAL);
    }

    private static FunctionSpec spec(String name, String endpointUrl) {
        return new FunctionSpec(
                name,
                "img:latest",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                endpointUrl,
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );
    }
}
