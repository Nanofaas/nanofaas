package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Restores the persisted catalog before Spring Boot publishes readiness. Each managed function is
 * reconciled against its exact recorded backend and replica target, the refreshed records replace the
 * catalog in one durable write, and only successfully reconciled functions replay their registration
 * listeners. A failed function keeps its durable recovery record but remains explicitly unavailable.
 * Listener or final persistence failures propagate out of {@link #run(ApplicationArguments)}.
 */
@Component
final class FunctionCatalogRestorer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FunctionCatalogRestorer.class);

    private final FunctionRegistry registry;
    private final DeploymentProviderResolver resolver;
    private final List<FunctionRegistrationListener> listeners;
    private final FunctionRestoreGate gate;

    @Autowired
    FunctionCatalogRestorer(FunctionRegistry registry,
                            DeploymentProviderResolver resolver,
                            @Autowired(required = false) List<FunctionRegistrationListener> listeners,
                            FunctionRestoreGate gate) {
        this.registry = registry;
        this.resolver = resolver;
        this.listeners = listeners == null ? List.of() : listeners;
        this.gate = gate;
    }

    FunctionCatalogRestorer(FunctionRegistry registry,
                            DeploymentProviderResolver resolver,
                            List<FunctionRegistrationListener> listeners) {
        this(registry, resolver, listeners, FunctionRestoreGate.open());
    }

    @Override
    public void run(ApplicationArguments arguments) {
        FunctionApplicationState applicationState = registry.applicationState();
        applicationState.clearAll();
        List<RegisteredFunction> restored = new ArrayList<>();
        List<RegisteredFunction> available = new ArrayList<>();
        for (RegisteredFunction function : registry.listRegistered().stream()
                .sorted(Comparator.comparing(RegisteredFunction::name)).toList()) {
            try {
                RegisteredFunction reconciled = reconcileIfManaged(function);
                restored.add(reconciled);
                available.add(reconciled);
            } catch (RuntimeException failure) {
                // Degrade per-function: a transient backend failure must not block startup.
                // Keep the persisted record (unreconciled) so it is not dropped from the catalog.
                log.warn("Skipping reconcile of function '{}' during restore: {}", function.name(), failure.getMessage());
                restored.add(function);
                applicationState.markUnavailable(function.name(), failure.getMessage());
            }
        }
        registry.replaceAllDurably(restored);
        for (RegisteredFunction function : available) {
            for (FunctionRegistrationListener listener : listeners) {
                listener.onRegister(function.spec());
            }
        }
        gate.markReady();
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
