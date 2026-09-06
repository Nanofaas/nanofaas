package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FunctionServiceManagedDeploymentTest {

    private final FunctionDefaults defaults = new FunctionDefaults(30000, 4, 100, 3);

    @TempDir
    Path tempDir;

    @Test
    void register_providerBackedDeployment_persistsMetadataAndEndpoint() {
        ManagedDeploymentProvider provider = provider("k8s");
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080/invoke", "k8s"));

        FunctionService service = serviceWithProviders(provider);

        Optional<RegisteredFunction> result = service.register(deploymentSpec("fn", null));

        assertThat(result)
                .isPresent()
                .get()
                .satisfies(registered -> {
                    assertThat(registered.spec().executionMode()).isEqualTo(ExecutionMode.DEPLOYMENT);
                    assertThat(registered.spec().endpointUrl()).isEqualTo("http://fn-svc:8080/invoke");
                });

        assertThat(service.getRegistered("fn"))
                .isPresent()
                .get()
                .satisfies(registered -> {
                    assertThat(registered.deploymentMetadata().requestedExecutionMode()).isEqualTo(ExecutionMode.DEPLOYMENT);
                    assertThat(registered.deploymentMetadata().effectiveExecutionMode()).isEqualTo(ExecutionMode.DEPLOYMENT);
                    assertThat(registered.deploymentMetadata().deploymentBackend()).isEqualTo("k8s");
                    assertThat(registered.deploymentMetadata().degradationReason()).isNull();
                    assertThat(registered.deploymentMetadata().effectiveEndpointUrl()).isEqualTo("http://fn-svc:8080/invoke");
                    assertThat(registered.spec().endpointUrl()).isEqualTo("http://fn-svc:8080/invoke");
                });
    }

    @Test
    void register_withoutProviderAndWithEndpoint_degradesToPoolAndPersistsReason() {
        FunctionService service = serviceWithProviders();

        Optional<RegisteredFunction> result = service.register(
                deploymentSpec("fn", "http://external:8080/invoke")
        );

        assertThat(result)
                .isPresent()
                .get()
                .satisfies(registered -> {
                    assertThat(registered.spec().executionMode()).isEqualTo(ExecutionMode.EXTERNAL);
                    assertThat(registered.spec().endpointUrl()).isEqualTo("http://external:8080/invoke");
                });

        assertThat(service.getRegistered("fn"))
                .isPresent()
                .get()
                .satisfies(registered -> {
                    assertThat(registered.deploymentMetadata().requestedExecutionMode()).isEqualTo(ExecutionMode.DEPLOYMENT);
                    assertThat(registered.deploymentMetadata().effectiveExecutionMode()).isEqualTo(ExecutionMode.EXTERNAL);
                    assertThat(registered.deploymentMetadata().deploymentBackend()).isNull();
                    assertThat(registered.deploymentMetadata().degradationReason())
                            .contains("No managed deployment provider");
                    assertThat(registered.deploymentMetadata().effectiveEndpointUrl()).isEqualTo("http://external:8080/invoke");
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

        FunctionSpec functionSpec = deploymentSpec("fn", null);
        assertThatThrownBy(() -> service.register(functionSpec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("listener failure");

        verify(provider).deprovision("fn");
        assertThat(service.get("fn")).isEmpty();
        assertThat(service.getRegistered("fn")).isEmpty();
    }

    @Test
    void remove_catalogFailure_reconcilesExactPersistedBackend() {
        FunctionServiceTest.ControllableCatalog catalog =
                new FunctionServiceTest.ControllableCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        ManagedDeploymentProvider provider = provider("k8s");
        Map<String, String> deploymentObjects = Map.of("deployment", "fn-deploy", "service", "fn-svc");
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080/invoke", "k8s", deploymentObjects));
        FunctionService service = new FunctionService(
                registry,
                defaults,
                ImageValidator.noOp(),
                List.of(),
                new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null))
        );

        assertThat(service.register(deploymentSpec("fn", null))).isPresent();
        assertThat(service.setReplicas("fn", 5)).contains(5);

        catalog.failSaves(true);

        assertThatThrownBy(() -> service.remove("fn"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("catalog failure");

        assertThat(service.get("fn")).isPresent();
        verify(provider).deprovision("fn");
        verify(provider).reconcile(
                argThat(spec -> spec.name().equals("fn")),
                eq(5),
                eq(deploymentObjects));
    }

    @Test
    void update_ofADeploymentFunction_pushesTheNewSpecToItsBackend() {
        ManagedDeploymentProvider provider = provider("k8s");
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080/invoke", "k8s"));
        FunctionService service = serviceWithProviders(provider);
        service.register(deploymentSpec("fn", null));

        service.update("fn", new FunctionUpdateRequest(8, 120_000, null, null));

        // Whatever the backend derived from the spec at provisioning time — the container proxy's
        // single-hop timeout and admission bound — has to follow the PATCH, or the deployment keeps
        // enforcing the values it was born with while the caller believes the new ones.
        verify(provider).updateSpec(argThat(spec ->
                spec.name().equals("fn") && spec.timeoutMs() == 120_000 && spec.concurrency() == 8));
    }

    @Test
    void update_ofANonDeploymentFunction_touchesNoBackend() {
        ManagedDeploymentProvider provider = provider("k8s");
        when(provider.supports(any())).thenReturn(false);
        FunctionService service = serviceWithProviders(provider);
        service.register(deploymentSpec("fn", "http://external:8080/invoke")); // degrades to EXTERNAL

        service.update("fn", new FunctionUpdateRequest(8, 120_000, null, null));

        verify(provider, never()).updateSpec(any());
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
