package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.PartialDeprovisionException;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The control plane's half of the partial-deprovision contract (plan task P08, finding R6).
 *
 * <p>A backend that reports {@link PartialDeprovisionException} says resources were really lost
 * from the control plane's reach. The policy is pending removal: no rollback is faked, the catalog
 * keeps the entry so what is left stays traceable, nothing new is admitted under that generation,
 * and a retried delete resumes the cleanup.
 */
class FunctionServicePartialDeprovisionTest {

    private static final List<String> LEFTOVERS = List.of("nanofaas-fn-r2");

    private final FunctionDefaults defaults = new FunctionDefaults(30000, 4, 100, 3);

    @Test
    void partialDeprovision_holdsThePendingRemovalInsteadOfFakingARollback() {
        ManagedDeploymentProvider provider = provisioningProvider();
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);
        FunctionService service = service(provider, listener);
        service.register(deploymentSpec("fn"));
        failDeprovision(provider);

        assertThatThrownBy(() -> service.remove("fn"))
                .isInstanceOf(FunctionRemovalPendingException.class)
                .hasMessageContaining("nanofaas-fn-r2")
                .hasCauseInstanceOf(PartialDeprovisionException.class);

        // No rebuild was attempted, so no rollback may be claimed: the deployment is not back.
        verify(provider, never()).reconcile(any(), anyInt(), any());
        // The listeners stay removed: capacity, queues and meters retire with the generation that
        // owns the leftover resources. Replaying onRegister would mint a new one over them.
        verify(listener, times(1)).onRegister(any());
        verify(listener, times(1)).onRemove("fn");
        // The catalog still shows the function, because its resources still exist.
        assertThat(service.listRegistered()).extracting(RegisteredFunction::name).containsExactly("fn");
        assertThat(service.getRegistered("fn")).isPresent();
    }

    @Test
    void pendingRemoval_refusesInvocationLookupsAndChanges() {
        ManagedDeploymentProvider provider = provisioningProvider();
        FunctionService service = service(provider);
        service.register(deploymentSpec("fn"));
        failDeprovision(provider);
        assertThatThrownBy(() -> service.remove("fn")).isInstanceOf(FunctionRemovalPendingException.class);

        // The invocation path's lookup: an explicit refusal, not a dispatch into a closed endpoint.
        assertThatThrownBy(() -> service.get("fn"))
                .isInstanceOf(FunctionRemovalPendingException.class)
                .satisfies(thrown -> assertThat(((FunctionRemovalPendingException) thrown).remainingResources())
                        .isEqualTo(LEFTOVERS));
        assertThatThrownBy(() -> service.update("fn", new FunctionUpdateRequest(8, null, null, null)))
                .isInstanceOf(FunctionRemovalPendingException.class);
        assertThatThrownBy(() -> service.setReplicas("fn", 3))
                .isInstanceOf(FunctionRemovalPendingException.class);
        // Re-registering the name would adopt resources that are still owed a cleanup.
        FunctionSpec sameName = deploymentSpec("fn");
        assertThatThrownBy(() -> service.register(sameName))
                .isInstanceOf(FunctionRemovalPendingException.class);
        verify(provider, times(1)).provision(any());
    }

    @Test
    void aRetriedDeleteThatSucceedsEndsThePendingRemoval() {
        ManagedDeploymentProvider provider = provisioningProvider();
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);
        FunctionService service = service(provider, listener);
        service.register(deploymentSpec("fn"));
        failDeprovision(provider);
        assertThatThrownBy(() -> service.remove("fn")).isInstanceOf(FunctionRemovalPendingException.class);

        doNothing().when(provider).deprovision("fn");
        assertThat(service.remove("fn")).map(FunctionSpec::name).contains("fn");

        assertThat(service.listRegistered()).isEmpty();
        assertThat(service.get("fn")).isEmpty();
        verify(provider, times(2)).deprovision("fn");
        verify(listener, times(2)).onRemove("fn");
        // The name is free again once nothing is left of the old generation.
        assertThat(service.register(deploymentSpec("fn"))).isPresent();
    }

    @Test
    void aRegistrationRollbackThatCannotUndoItselfKeepsTheLeftoversTraceable() {
        ManagedDeploymentProvider provider = provisioningProvider();
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);
        doThrow(new IllegalStateException("listener failure")).when(listener).onRegister(any());
        FunctionService service = service(provider, listener);
        failDeprovision(provider);

        FunctionSpec spec = deploymentSpec("fn");
        assertThatThrownBy(() -> service.register(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("listener failure")
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .hasAtLeastOneElementOfType(PartialDeprovisionException.class));

        // Dropping the name here would orphan the containers the failed registration created.
        assertThat(service.listRegistered()).extracting(RegisteredFunction::name).containsExactly("fn");
        assertThatThrownBy(() -> service.get("fn")).isInstanceOf(FunctionRemovalPendingException.class);

        doNothing().when(provider).deprovision("fn");
        assertThat(service.remove("fn")).isPresent();
        assertThat(service.listRegistered()).isEmpty();
    }

    @Test
    void anInvocationLookupRacingTheRemovalIsRefusedBeforeAndAfterTheFailure() throws Exception {
        ManagedDeploymentProvider provider = provisioningProvider();
        FunctionService service = service(provider);
        service.register(deploymentSpec("fn"));

        CountDownLatch insideDeprovision = new CountDownLatch(1);
        CountDownLatch releaseDeprovision = new CountDownLatch(1);
        AtomicBoolean lookupSawTheFunction = new AtomicBoolean(true);
        doAnswer(invocation -> {
            insideDeprovision.countDown();
            assertThat(releaseDeprovision.await(5, TimeUnit.SECONDS)).isTrue();
            throw new PartialDeprovisionException("fn", "k8s", LEFTOVERS,
                    List.of(new IllegalStateException("engine unavailable")));
        }).when(provider).deprovision("fn");

        CompletableFuture<Void> removal = CompletableFuture.runAsync(() ->
                assertThatThrownBy(() -> service.remove("fn"))
                        .isInstanceOf(FunctionRemovalPendingException.class));

        assertThat(insideDeprovision.await(5, TimeUnit.SECONDS)).isTrue();
        // Mid-teardown the function is already detached: no invocation is admitted into a
        // deployment that is being deleted underneath it.
        lookupSawTheFunction.set(service.get("fn").isPresent());
        releaseDeprovision.countDown();
        removal.get(5, TimeUnit.SECONDS);

        assertThat(lookupSawTheFunction).isFalse();
        // And once the partial outcome lands, the refusal becomes an explicit one.
        assertThatThrownBy(() -> service.get("fn")).isInstanceOf(FunctionRemovalPendingException.class);
    }

    // --- fixtures ---------------------------------------------------------------------------

    private FunctionService service(ManagedDeploymentProvider provider,
                                    FunctionRegistrationListener... listeners) {
        return new FunctionService(
                new FunctionRegistry(),
                defaults,
                ImageValidator.noOp(),
                List.of(listeners),
                new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null))
        );
    }

    private static ManagedDeploymentProvider provisioningProvider() {
        ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn("k8s");
        when(provider.isAvailable()).thenReturn(true);
        when(provider.supports(any())).thenReturn(true);
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn-svc:8080/invoke", "k8s"));
        return provider;
    }

    private static void failDeprovision(ManagedDeploymentProvider provider) {
        doThrow(new PartialDeprovisionException("fn", "k8s", LEFTOVERS,
                List.of(new IllegalStateException("engine unavailable"))))
                .when(provider).deprovision(anyString());
    }

    private static FunctionSpec deploymentSpec(String name) {
        return new FunctionSpec(name, "img:latest", null, null, null, null, null, null, null, null,
                ExecutionMode.DEPLOYMENT, null, null, null);
    }
}
