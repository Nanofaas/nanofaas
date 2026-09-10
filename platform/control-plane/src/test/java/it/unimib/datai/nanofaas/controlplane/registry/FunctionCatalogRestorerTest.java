package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FunctionCatalogRestorerTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyCatalogPerformsNoWork() {
        FunctionRegistry registry = new FunctionRegistry();
        DeploymentProviderResolver resolver = mock(DeploymentProviderResolver.class);
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);

        new FunctionCatalogRestorer(registry, resolver, List.of(listener))
                .run(new DefaultApplicationArguments());

        verifyNoInteractions(resolver);
        verifyNoInteractions(listener);
        assertThat(registry.listRegistered()).isEmpty();
    }

    @Test
    void marksRestoreGateReadyAfterSuccessfulRun() {
        FunctionRegistry registry = new FunctionRegistry();
        DeploymentProviderResolver resolver = mock(DeploymentProviderResolver.class);
        FunctionRestoreGate gate = new FunctionRestoreGate();

        new FunctionCatalogRestorer(registry, resolver, List.of(), gate)
                .run(new DefaultApplicationArguments());

        assertThat(gate.isReady()).isTrue();
    }

    @Test
    void processesFunctionsInNameOrder() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.put(managedFunction("zebra", "container-local", 1));
        registry.put(managedFunction("alpha", "container-local", 1));
        ManagedDeploymentProvider provider = provider("container-local");
        DeploymentProviderResolver resolver = resolverReturning(provider, "container-local");
        when(provider.reconcile(any(), eq(1), anyMap())).thenReturn(reconciledResult("container-local"));
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);

        new FunctionCatalogRestorer(registry, resolver, List.of(listener))
                .run(new DefaultApplicationArguments());

        InOrder order = inOrder(provider, listener);
        order.verify(provider).reconcile(argThat(spec -> spec.name().equals("alpha")), eq(1), anyMap());
        order.verify(provider).reconcile(argThat(spec -> spec.name().equals("zebra")), eq(1), anyMap());
        order.verify(listener).onRegister(argThat(spec -> spec.name().equals("alpha")));
        order.verify(listener).onRegister(argThat(spec -> spec.name().equals("zebra")));
    }

    @Test
    void nonManagedRecordsReplayListenersWithoutProviderCalls() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.put(RegisteredFunction.nonManaged(externalSpec("external-fn")));
        DeploymentProviderResolver resolver = mock(DeploymentProviderResolver.class);
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);

        new FunctionCatalogRestorer(registry, resolver, List.of(listener))
                .run(new DefaultApplicationArguments());

        verify(listener).onRegister(argThat(spec -> spec.name().equals("external-fn")));
        verifyNoInteractions(resolver);
    }

    @Test
    void restoresExactBackendBeforeReplacingCatalogAndReplayingListeners() {
        FunctionRegistry registry = spy(new FunctionRegistry());
        registry.put(managedFunction("echo", "container-local", 0));
        ManagedDeploymentProvider provider = provider("container-local");
        DeploymentProviderResolver resolver = resolverReturning(provider, "container-local");
        when(provider.reconcile(any(), eq(0), anyMap())).thenReturn(reconciledResult("container-local"));
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);

        new FunctionCatalogRestorer(registry, resolver, List.of(listener))
                .run(new DefaultApplicationArguments());

        InOrder order = inOrder(provider, registry, listener);
        order.verify(provider).reconcile(any(), eq(0), anyMap());
        order.verify(registry).replaceAllAfterRestore(anyCollection(), anyCollection());
        order.verify(listener).onRegister(any());
        verify(registry, times(1)).replaceAllAfterRestore(anyCollection(), anyCollection());
        verify(resolver, never()).resolveAndProvision(any(), any());
        verify(resolver, never()).resolve(any(), any());
        verify(provider, never()).provision(any());
        verify(provider, never()).deprovision(anyString());
    }

    @Test
    void missingBackendDegradesThatFunction() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.put(managedFunction("echo", "container-local", 0));
        DeploymentProviderResolver resolver =
                new DeploymentProviderResolver(List.of(), new DeploymentProperties(null));
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);

        new FunctionCatalogRestorer(registry, resolver, List.of(listener))
                .run(new DefaultApplicationArguments());

        assertThat(registry.listRegistered()).isEmpty();
        assertThat(registry.listRegisteredForRecovery()).extracting(RegisteredFunction::name)
                .containsExactly("echo");
        assertThat(registry.applicationState().isUnavailable("echo")).isTrue();
        verify(listener, never()).onRegister(any());
    }

    @Test
    void reconcileErrorDegradesThatFunction() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.put(managedFunction("echo", "container-local", 0));
        ManagedDeploymentProvider provider = provider("container-local");
        DeploymentProviderResolver resolver = resolverReturning(provider, "container-local");
        when(provider.reconcile(any(), eq(0), anyMap()))
                .thenThrow(new IllegalStateException("reconcile failure"));
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);

        new FunctionCatalogRestorer(registry, resolver, List.of(listener))
                .run(new DefaultApplicationArguments());

        assertThat(registry.listRegistered()).isEmpty();
        assertThat(registry.listRegisteredForRecovery()).extracting(RegisteredFunction::name)
                .containsExactly("echo");
        assertThat(registry.applicationState().isUnavailable("echo")).isTrue();
        verify(listener, never()).onRegister(any());
    }

    @Test
    void listenerErrorEscapesRun() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.put(managedFunction("echo", "container-local", 0));
        ManagedDeploymentProvider provider = provider("container-local");
        DeploymentProviderResolver resolver = resolverReturning(provider, "container-local");
        when(provider.reconcile(any(), eq(0), anyMap())).thenReturn(reconciledResult("container-local"));
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);
        doThrow(new IllegalStateException("listener failure")).when(listener).onRegister(any());

        FunctionCatalogRestorer restorer = new FunctionCatalogRestorer(registry, resolver, List.of(listener));
        DefaultApplicationArguments arguments = new DefaultApplicationArguments();
        assertThatThrownBy(() -> restorer.run(arguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("listener failure");
    }

    @Test
    void reconcileReturningChangedBackendIsKeptUnreconciled() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.put(managedFunction("echo", "container-local", 0));
        ManagedDeploymentProvider provider = provider("container-local");
        DeploymentProviderResolver resolver = resolverReturning(provider, "container-local");
        when(provider.reconcile(any(), eq(0), anyMap()))
                .thenReturn(new ProvisionResult("http://other/invoke", "k8s"));
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);

        new FunctionCatalogRestorer(registry, resolver, List.of(listener))
                .run(new DefaultApplicationArguments());

        assertThat(registry.listRegistered()).isEmpty();
        RegisteredFunction kept = registry.listRegisteredForRecovery().iterator().next();
        assertThat(kept.name()).isEqualTo("echo");
        assertThat(kept.deploymentMetadata().deploymentBackend()).isEqualTo("container-local");
        assertThat(registry.applicationState().isUnavailable("echo")).isTrue();
        verify(listener, never()).onRegister(any());
    }

    @Test
    void reconcileReturningDegradedExecutionModeIsKeptUnreconciled() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.put(managedFunction("echo", "container-local", 0));
        ManagedDeploymentProvider provider = provider("container-local");
        DeploymentProviderResolver resolver = resolverReturning(provider, "container-local");
        when(provider.reconcile(any(), eq(0), anyMap()))
                .thenReturn(new ProvisionResult("http://external/invoke", "container-local",
                        ExecutionMode.EXTERNAL, "degraded", Map.of()));
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);

        new FunctionCatalogRestorer(registry, resolver, List.of(listener))
                .run(new DefaultApplicationArguments());

        assertThat(registry.listRegistered()).isEmpty();
        RegisteredFunction kept = registry.listRegisteredForRecovery().iterator().next();
        assertThat(kept.name()).isEqualTo("echo");
        assertThat(kept.deploymentMetadata().effectiveExecutionMode()).isEqualTo(ExecutionMode.DEPLOYMENT);
        assertThat(registry.applicationState().isUnavailable("echo")).isTrue();
        verify(listener, never()).onRegister(any());
    }

    @Test
    void finalSnapshotErrorEscapesRun() {
        FunctionServiceTest.ControllableCatalog catalog =
                new FunctionServiceTest.ControllableCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        registry.put(managedFunction("echo", "container-local", 0));
        ManagedDeploymentProvider provider = provider("container-local");
        DeploymentProviderResolver resolver = resolverReturning(provider, "container-local");
        when(provider.reconcile(any(), eq(0), anyMap())).thenReturn(reconciledResult("container-local"));
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);

        catalog.failSaves(true);

        FunctionCatalogRestorer restorer = new FunctionCatalogRestorer(registry, resolver, List.of(listener));
        DefaultApplicationArguments arguments = new DefaultApplicationArguments();
        assertThatThrownBy(() -> restorer.run(arguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("catalog failure");

        verify(listener, never()).onRegister(any());
    }

    private static DeploymentProviderResolver resolverReturning(ManagedDeploymentProvider provider,
                                                                String backendId) {
        DeploymentProviderResolver resolver = mock(DeploymentProviderResolver.class);
        when(resolver.requireBackend(backendId)).thenReturn(provider);
        return resolver;
    }

    private static ManagedDeploymentProvider provider(String backendId) {
        ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn(backendId);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.supports(any())).thenReturn(true);
        return provider;
    }

    private static ProvisionResult reconciledResult(String backendId) {
        return new ProvisionResult("http://" + backendId + "/invoke", backendId,
                Map.of("deployment", "d", "service", "s"));
    }

    private static RegisteredFunction managedFunction(String name, String backendId, int desiredReplicas) {
        FunctionSpec spec = new FunctionSpec(name, "img:latest", List.of(), Map.of(), null,
                1000, 1, 1, 0, null, ExecutionMode.DEPLOYMENT, null, null, null, null);
        return new RegisteredFunction(spec,
                new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, backendId, null))
                .withDesiredReplicas(desiredReplicas);
    }

    private static FunctionSpec externalSpec(String name) {
        return new FunctionSpec(name, "img:latest", List.of(), Map.of(), null,
                1000, 1, 1, 0, "http://external/invoke", ExecutionMode.EXTERNAL, null, null, null, null);
    }
}
