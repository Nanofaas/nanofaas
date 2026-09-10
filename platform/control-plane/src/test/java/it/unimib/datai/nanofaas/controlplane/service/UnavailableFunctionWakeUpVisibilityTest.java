package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalog;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogProperties;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionDefaults;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import jakarta.validation.Validation;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UnavailableFunctionWakeUpVisibilityTest {

    @TempDir
    Path tempDir;

    @Test
    void wakeUpCannotUseUnavailableRecordAfterDeleteRetryRollback() {
        FailingCatalog catalog = new FailingCatalog(tempDir.resolve("functions.json"));
        FunctionRegistry registry = new FunctionRegistry(catalog);
        ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn("k8s");
        when(provider.isAvailable()).thenReturn(true);
        when(provider.supports(any())).thenReturn(true);
        when(provider.provision(any())).thenReturn(new ProvisionResult("http://fn/invoke", "k8s"));
        FunctionService service = new FunctionService(
                registry, new FunctionDefaults(1_000, 1, 10, 0), ImageValidator.noOp(), List.of(),
                new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)));
        FunctionSpec function = function();
        service.register(function);

        catalog.failSaves = true;
        doNothing().doThrow(new IllegalStateException("retry deprovision unavailable"))
                .when(provider).deprovision("fn");
        when(provider.reconcile(any(), anyInt(), anyMap()))
                .thenThrow(new IllegalStateException("reconcile unavailable"));
        assertThatThrownBy(() -> service.remove("fn")).hasMessage("catalog failure");
        catalog.failSaves = false;
        assertThatThrownBy(() -> service.remove("fn")).hasMessage("retry deprovision unavailable");

        ManagedDeploymentCoordinator coordinator = mock(ManagedDeploymentCoordinator.class);
        when(coordinator.generationOf(any()))
                .thenThrow(new IllegalStateException("UNAVAILABLE_RECORD_USED"));
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            DeploymentWakeUpGate wakeUp = new DeploymentWakeUpGate(
                    registry, coordinator, new FunctionCapacityRegistry(), new DeploymentWakeUpProperties(),
                    Runnable::run, scheduler, mock(DeploymentWakeUpCoordinator.class));
            InvocationTask task = new InvocationTask(
                    "execution-fn", "fn", function, new InvocationRequest("payload", Map.of()),
                    null, null, Instant.now(), 1, InvocationKind.SYNC);

            assertThatThrownBy(() -> wakeUp.ensureReady(task).join())
                    .hasRootCauseMessage("DEPLOYMENT_WAKE_UP_TARGET_UNAVAILABLE");
            verifyNoInteractions(coordinator);
            wakeUp.close();
        }
    }

    private static FunctionSpec function() {
        return new FunctionSpec("fn", "example:latest", List.of(), Map.of(), null,
                1_000, 1, 10, 0, null, ExecutionMode.DEPLOYMENT, null, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, 0, 10, List.of()), null);
    }

    private static final class FailingCatalog extends FunctionCatalog {
        private boolean failSaves;

        private FailingCatalog(Path path) {
            super(new FunctionCatalogProperties(path), new ObjectMapper(),
                    Validation.buildDefaultValidatorFactory().getValidator());
        }

        @Override
        public void save(Collection<RegisteredFunction> functions) {
            if (failSaves) {
                throw new IllegalStateException("catalog failure");
            }
            super.save(functions);
        }
    }
}
