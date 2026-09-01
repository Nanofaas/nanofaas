package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Restores the persisted catalog before Spring Boot publishes readiness. Each managed function is
 * reconciled against its exact recorded backend and replica target, the refreshed records replace the
 * catalog in one durable write, and every restored function replays its registration listeners.
 * Failures propagate out of {@link #run(ApplicationArguments)} so startup fails rather than serving a
 * partially restored control plane.
 */
@Component
final class FunctionCatalogRestorer implements ApplicationRunner {
    private final FunctionRegistry registry;
    private final DeploymentProviderResolver resolver;
    private final List<FunctionRegistrationListener> listeners;

    FunctionCatalogRestorer(FunctionRegistry registry,
                            DeploymentProviderResolver resolver,
                            @Autowired(required = false) List<FunctionRegistrationListener> listeners) {
        this.registry = registry;
        this.resolver = resolver;
        this.listeners = listeners == null ? List.of() : listeners;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        List<RegisteredFunction> restored = registry.listRegistered().stream()
                .sorted(Comparator.comparing(RegisteredFunction::name))
                .map(this::reconcileIfManaged)
                .toList();
        registry.replaceAllDurably(restored);
        for (RegisteredFunction function : restored) {
            for (FunctionRegistrationListener listener : listeners) {
                listener.onRegister(function.spec());
            }
        }
    }

    private RegisteredFunction reconcileIfManaged(RegisteredFunction function) {
        ManagedDeploymentTarget target = function.managedDeploymentTarget().orElse(null);
        if (target == null) {
            return function;
        }
        ManagedDeploymentProvider provider = resolver.requireBackend(target.backendId());
        ProvisionResult result = provider.reconcile(
                function.spec(), function.desiredReplicas(), function.deploymentMetadata().deploymentObjects());
        return function.withProvisionResult(result);
    }
}
