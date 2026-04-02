package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FunctionServiceManagedDeploymentTest {

    private final FunctionDefaults defaults = new FunctionDefaults(30000, 4, 100, 3);

    @Test
    void register_providerBackedDeployment_persistsMetadataAndEndpoint() {
        ManagedDeploymentProvider provider = provider("k8s");
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080/invoke", "k8s"));

        FunctionService service = serviceWithProviders(provider);

        Optional<FunctionSpec> result = service.register(deploymentSpec("fn", null));

        assertThat(result)
                .isPresent()
                .get()
                .satisfies(registered -> {
                    assertThat(registered.executionMode()).isEqualTo(ExecutionMode.DEPLOYMENT);
                    assertThat(registered.endpointUrl()).isEqualTo("http://fn-svc:8080/invoke");
                });

        assertThat(service.getRegistered("fn"))
                .isPresent()
                .get()
                .satisfies(registered -> {
                    assertThat(registered.deploymentMetadata().requestedExecutionMode()).isEqualTo(ExecutionMode.DEPLOYMENT);
                    assertThat(registered.deploymentMetadata().effectiveExecutionMode()).isEqualTo(ExecutionMode.DEPLOYMENT);
                    assertThat(registered.deploymentMetadata().deploymentBackend()).isEqualTo("k8s");
                    assertThat(registered.deploymentMetadata().degradationReason()).isNull();
                    assertThat(registered.spec().endpointUrl()).isEqualTo("http://fn-svc:8080/invoke");
                });
    }

    @Test
    void register_withoutProviderAndWithEndpoint_degradesToPoolAndPersistsReason() {
        FunctionService service = serviceWithProviders();

        Optional<FunctionSpec> result = service.register(
                deploymentSpec("fn", "http://external:8080/invoke")
        );

        assertThat(result)
                .isPresent()
                .get()
                .satisfies(registered -> {
                    assertThat(registered.executionMode()).isEqualTo(ExecutionMode.POOL);
                    assertThat(registered.endpointUrl()).isEqualTo("http://external:8080/invoke");
                });

        assertThat(service.getRegistered("fn"))
                .isPresent()
                .get()
                .satisfies(registered -> {
                    assertThat(registered.deploymentMetadata().requestedExecutionMode()).isEqualTo(ExecutionMode.DEPLOYMENT);
                    assertThat(registered.deploymentMetadata().effectiveExecutionMode()).isEqualTo(ExecutionMode.POOL);
                    assertThat(registered.deploymentMetadata().deploymentBackend()).isNull();
                    assertThat(registered.deploymentMetadata().degradationReason())
                            .contains("No managed deployment provider");
                });
    }

    @Test
    void remove_usesEffectiveProviderForDeprovision() {
        ManagedDeploymentProvider provider = provider("k8s");
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080/invoke", "k8s"));

        FunctionService service = serviceWithProviders(provider);
        assertThat(service.register(deploymentSpec("fn", null))).isPresent();

        assertThat(service.remove("fn")).isPresent();

        verify(provider).deprovision("fn");
    }

    @Test
    void listenerFailure_rollsBackUsingProvisioningProvider() {
        ManagedDeploymentProvider provider = provider("k8s");
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080/invoke", "k8s"));

        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);
        doThrow(new IllegalStateException("listener failure")).when(listener).onRegister(any());

        FunctionService service = new FunctionService(
                new FunctionRegistry(),
                defaults,
                ImageValidator.noOp(),
                List.of(listener),
                new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null))
        );

        assertThatThrownBy(() -> service.register(deploymentSpec("fn", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("listener failure");

        verify(provider).deprovision("fn");
        assertThat(service.get("fn")).isEmpty();
        assertThat(service.getRegistered("fn")).isEmpty();
    }

    private FunctionService serviceWithProviders(ManagedDeploymentProvider... providers) {
        return new FunctionService(
                new FunctionRegistry(),
                defaults,
                ImageValidator.noOp(),
                List.of(),
                new DeploymentProviderResolver(List.of(providers), new DeploymentProperties(null))
        );
    }

    private static ManagedDeploymentProvider provider(String backendId) {
        ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn(backendId);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.supports(any())).thenReturn(true);
        return provider;
    }

    private static FunctionSpec deploymentSpec(String name, String endpointUrl) {
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
