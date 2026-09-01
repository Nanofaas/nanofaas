package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
public class FunctionService {
    private static final Logger log = LoggerFactory.getLogger(FunctionService.class);

    private final FunctionRegistry registry;
    private final FunctionSpecResolver resolver;
    private final DeploymentProviderResolver deploymentProviderResolver;
    private final ManagedDeploymentCoordinator managedDeploymentCoordinator;
    private final ImageValidator imageValidator;
    private final List<FunctionRegistrationListener> listeners;
    private final FunctionOperationLocks locks;
    private final FunctionRestoreGate restoreGate;

    public FunctionService(FunctionRegistry registry,
                           FunctionDefaults defaults,
                           ImageValidator imageValidator,
                           @Autowired(required = false) List<FunctionRegistrationListener> listeners,
                           DeploymentProviderResolver deploymentProviderResolver) {
        this(registry, defaults, imageValidator, listeners, deploymentProviderResolver,
                new FunctionOperationLocks(), null, null);
    }

    public FunctionService(FunctionRegistry registry,
                           FunctionDefaults defaults,
                           ImageValidator imageValidator,
                           @Autowired(required = false) List<FunctionRegistrationListener> listeners,
                           DeploymentProviderResolver deploymentProviderResolver,
                           FunctionOperationLocks locks,
                           ManagedDeploymentCoordinator managedDeploymentCoordinator) {
        this(registry, defaults, imageValidator, listeners, deploymentProviderResolver,
                locks, managedDeploymentCoordinator, null);
    }

    @Autowired
    public FunctionService(FunctionRegistry registry,
                           FunctionDefaults defaults,
                           ImageValidator imageValidator,
                           @Autowired(required = false) List<FunctionRegistrationListener> listeners,
                           DeploymentProviderResolver deploymentProviderResolver,
                           FunctionOperationLocks locks,
                           @Autowired(required = false) ManagedDeploymentCoordinator managedDeploymentCoordinator,
                           @Autowired(required = false) FunctionRestoreGate restoreGate) {
        this.registry = registry;
        this.resolver = new FunctionSpecResolver(defaults);
        this.deploymentProviderResolver = deploymentProviderResolver;
        this.locks = locks;
        this.managedDeploymentCoordinator = managedDeploymentCoordinator == null
                ? new ManagedDeploymentCoordinator(deploymentProviderResolver, registry, locks)
                : managedDeploymentCoordinator;
        this.imageValidator = imageValidator;
        this.listeners = listeners == null ? List.of() : listeners;
        this.restoreGate = restoreGate == null ? FunctionRestoreGate.open() : restoreGate;
    }

    private void ensureReady() {
        if (!restoreGate.isReady()) {
            throw new IllegalStateException("Control plane is still restoring the function catalog");
        }
    }

    public Collection<FunctionSpec> list() {
        return registry.list();
    }

    public Collection<RegisteredFunction> listRegistered() {
        return registry.listRegistered();
    }

    public Optional<FunctionSpec> get(String name) {
        return registry.get(name);
    }

    public Optional<RegisteredFunction> getRegistered(String name) {
        return registry.getRegistered(name);
    }

    public Optional<RegisteredFunction> register(FunctionSpec spec) {
        ensureReady();
        FunctionSpec initialResolved = resolver.resolve(spec);

        return locks.withLock(initialResolved.name(), () -> {
            if (registry.getRegistered(initialResolved.name()).isPresent()) {
                return Optional.empty();
            }

            imageValidator.validate(initialResolved);
            RegisteredFunction registered = resolveRegistration(initialResolved);

            List<FunctionRegistrationListener> notified = new ArrayList<>();
            try {
                for (FunctionRegistrationListener listener : listeners) {
                    listener.onRegister(registered.spec());
                    notified.add(listener);
                }
                registry.put(registered); // durable commit
                return Optional.of(registered);
            } catch (RuntimeException failure) {
                rollbackRegistrationListeners(registered.name(), notified, failure);
                rollbackProvisionedRegistration(registered, failure);
                throw failure;
            }
        });
    }

    /**
     * Applies a partial update to a registered function. Only control-plane knobs are mutable
     * (see {@link FunctionUpdateRequest}), so the deployment is left untouched and the function
     * keeps serving throughout.
     *
     * @return the updated function, or empty if it is not registered
     */
    public Optional<RegisteredFunction> update(String name, FunctionUpdateRequest request) {
        ensureReady();
        return locks.withLock(name, () -> {
            RegisteredFunction existing = registry.getRegistered(name).orElse(null);
            if (existing == null) {
                return Optional.empty();
            }

            FunctionSpec updatedSpec = resolver.resolve(request.applyTo(existing.spec()));
            RegisteredFunction updated = new RegisteredFunction(updatedSpec, existing.deploymentMetadata());
            registry.put(updated);
            // ponytail: no rollback on listener failure — the registration rollback path deletes the
            // function's queue, which is far worse than a listener missing one update. Listeners are
            // idempotent, so replaying the same PATCH converges.
            for (FunctionRegistrationListener listener : listeners) {
                listener.onRegister(updatedSpec);
            }
            log.info("Updated function {} (concurrency={}, timeoutMs={}, maxRetries={})",
                    name, updatedSpec.concurrency(), updatedSpec.timeoutMs(), updatedSpec.maxRetries());
            return Optional.of(updated);
        });
    }

    /**
     * Sets the replica count for a DEPLOYMENT-mode function.
     * Returns the new replica count, or empty if function not found.
     * Throws IllegalArgumentException if function is not in DEPLOYMENT mode.
     * Throws IllegalStateException if the effective deployment provider is not available.
     */
    public Optional<Integer> setReplicas(String name, int replicas) {
        ensureReady();
        RegisteredFunction function = registry.getRegistered(name).orElse(null);
        if (function == null) {
            return Optional.empty();
        }
        if (function.deploymentMetadata().effectiveExecutionMode() != ExecutionMode.DEPLOYMENT) {
            throw new IllegalArgumentException("Function '" + name + "' is not in DEPLOYMENT mode");
        }
        if (!managedDeploymentCoordinator.setReplicas(requireManagedDeploymentTarget(function), replicas)) {
            // A concurrent remove deleted the function between the lookup above and the
            // coordinator's own re-check under the lock; treat it as not-found.
            return Optional.empty();
        }
        log.info("Set replicas for function {} to {}", name, replicas);
        return Optional.of(replicas);
    }

    public Optional<ReplicaStatus> getReplicaStatus(String name) {
        return locks.withLock(name, () -> {
            RegisteredFunction function = registry.getRegistered(name).orElse(null);
            if (function == null) {
                return Optional.empty();
            }
            if (function.deploymentMetadata().effectiveExecutionMode() != ExecutionMode.DEPLOYMENT) {
                throw new IllegalArgumentException("Function '" + name + "' is not in DEPLOYMENT mode");
            }
            return Optional.of(managedDeploymentCoordinator.getReplicaStatus(requireManagedDeploymentTarget(function)));
        });
    }

    public Optional<FunctionSpec> remove(String name) {
        ensureReady();
        return locks.withLock(name, () -> {
            RegisteredFunction existing = registry.detach(name);
            if (existing == null) {
                return Optional.empty();
            }

            List<FunctionRegistrationListener> notified = new ArrayList<>();
            boolean deprovisioned = false;
            try {
                for (FunctionRegistrationListener listener : listeners) {
                    listener.onRemove(name);
                    notified.add(listener);
                }
                if (existing.managedDeploymentTarget().isPresent()) {
                    managedDeploymentCoordinator.deprovision(existing.managedDeploymentTarget().orElseThrow());
                    deprovisioned = true;
                }
                registry.persistCurrentSnapshot(); // durable delete commit happens last
                return Optional.of(existing.spec());
            } catch (RuntimeException failure) {
                RegisteredFunction restored = existing;
                if (deprovisioned) {
                    try {
                        restored = reconcile(existing);
                    } catch (RuntimeException rollback) {
                        failure.addSuppressed(rollback);
                    }
                }
                rollbackRemovalListeners(restored.spec(), notified, failure);
                registry.restoreDetached(restored); // memory-only: keep serving even if the catalog is unwritable
                try {
                    registry.persistCurrentSnapshot(); // best-effort durable re-save closes the detach window
                } catch (RuntimeException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        });
    }

    private RegisteredFunction resolveRegistration(FunctionSpec spec) {
        if (spec.executionMode() != ExecutionMode.DEPLOYMENT) {
            return RegisteredFunction.nonManaged(spec);
        }

        ProvisionResult provisionResult = deploymentProviderResolver.resolveAndProvision(spec, null);
        FunctionSpec effectiveSpec = withEffectiveProvisioning(spec, provisionResult);
        return new RegisteredFunction(
                effectiveSpec,
                new DeploymentMetadata(
                        spec.executionMode(),
                        provisionResult.effectiveExecutionMode(),
                        provisionResult.backendId(),
                        provisionResult.degradationReason(),
                        provisionResult.endpointUrl(),
                        provisionResult.deploymentObjects()
                )
        );
    }

    private FunctionSpec withEffectiveProvisioning(FunctionSpec spec, ProvisionResult provisionResult) {
        return spec.withEndpoint(provisionResult.endpointUrl(), provisionResult.effectiveExecutionMode());
    }

    private void rollbackProvisionedRegistration(RegisteredFunction function, RuntimeException failure) {
        Optional<ManagedDeploymentTarget> target = managedDeploymentTarget(function);
        if (target.isEmpty()) return;
        try {
            managedDeploymentCoordinator.deprovision(target.get());
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Restores a managed deployment that was already deprovisioned before a delete-catalog failure.
     * Reconciles only the exact persisted backend (never backend selection or degradation), driving
     * the provider's non-destructive reconcile toward the persisted spec and replica target.
     */
    private RegisteredFunction reconcile(RegisteredFunction existing) {
        ManagedDeploymentTarget target = existing.managedDeploymentTarget().orElseThrow(() -> new IllegalStateException(
                "Function '" + existing.name() + "' has no persisted managed deployment"));
        ProvisionResult result = managedDeploymentCoordinator.requireProvider(target)
                .reconcile(existing.spec(), existing.desiredReplicas(), existing.deploymentMetadata().deploymentObjects());
        return existing.withProvisionResult(result);
    }

    private ManagedDeploymentTarget requireManagedDeploymentTarget(RegisteredFunction function) {
        return managedDeploymentTarget(function).orElseThrow(() -> new IllegalStateException(
                "Function '" + function.name() + "' is not a managed deployment"));
    }

    private Optional<ManagedDeploymentTarget> managedDeploymentTarget(RegisteredFunction function) {
        if (function.deploymentMetadata().effectiveExecutionMode() != ExecutionMode.DEPLOYMENT) {
            return Optional.empty();
        }
        String backendId = function.deploymentMetadata().deploymentBackend();
        return backendId == null || backendId.isBlank()
                ? Optional.empty()
                : Optional.of(new ManagedDeploymentTarget(function.name(), backendId));
    }

    private void rollbackRegistrationListeners(String functionName,
                                               List<FunctionRegistrationListener> notified,
                                               RuntimeException failure) {
        for (int i = notified.size() - 1; i >= 0; i--) {
            try {
                notified.get(i).onRemove(functionName);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private void rollbackRemovalListeners(FunctionSpec spec,
                                          List<FunctionRegistrationListener> notified,
                                          RuntimeException failure) {
        for (int i = notified.size() - 1; i >= 0; i--) {
            try {
                notified.get(i).onRegister(spec);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

}
