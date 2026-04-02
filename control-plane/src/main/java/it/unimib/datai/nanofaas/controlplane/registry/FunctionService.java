package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
public class FunctionService {
    private static final Logger log = LoggerFactory.getLogger(FunctionService.class);

    private final FunctionRegistry registry;
    private final FunctionSpecResolver resolver;
    private final DeploymentProviderResolver deploymentProviderResolver;
    private final ManagedDeploymentCoordinator managedDeploymentCoordinator;
    private final ImageValidator imageValidator;
    private final List<FunctionRegistrationListener> listeners;
    private final ConcurrentHashMap<String, LockEntry> functionLocks = new ConcurrentHashMap<>();

    public FunctionService(FunctionRegistry registry,
                           FunctionDefaults defaults,
                           ImageValidator imageValidator,
                           @Autowired(required = false) List<FunctionRegistrationListener> listeners,
                           DeploymentProviderResolver deploymentProviderResolver) {
        this(registry, defaults, imageValidator, listeners, deploymentProviderResolver, null);
    }

    @Autowired
    public FunctionService(FunctionRegistry registry,
                           FunctionDefaults defaults,
                           ImageValidator imageValidator,
                           @Autowired(required = false) List<FunctionRegistrationListener> listeners,
                           DeploymentProviderResolver deploymentProviderResolver,
                           @Autowired(required = false) ManagedDeploymentCoordinator managedDeploymentCoordinator) {
        this.registry = registry;
        this.resolver = new FunctionSpecResolver(defaults);
        this.deploymentProviderResolver = deploymentProviderResolver;
        this.managedDeploymentCoordinator = managedDeploymentCoordinator == null
                ? new ManagedDeploymentCoordinator(deploymentProviderResolver)
                : managedDeploymentCoordinator;
        this.imageValidator = imageValidator;
        this.listeners = listeners == null ? List.of() : listeners;
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
        FunctionSpec initialResolved = resolver.resolve(spec);

        return withFunctionLock(initialResolved.name(), () -> {
            if (registry.getRegistered(initialResolved.name()).isPresent()) {
                return Optional.empty();
            }

            RegisteredFunction registered;
            try {
                imageValidator.validate(initialResolved);
                registered = resolveRegistration(initialResolved);
            } catch (RuntimeException e) {
                throw e;
            }

            try {
                notifyRegisterListeners(registered.spec());
                registry.put(registered);
                return Optional.of(registered);
            } catch (RuntimeException e) {
                rollbackProvisionedRegistration(registered, e);
                throw e;
            }
        });
    }

    /**
     * Sets the replica count for a DEPLOYMENT-mode function.
     * Returns the new replica count, or empty if function not found.
     * Throws IllegalArgumentException if function is not in DEPLOYMENT mode.
     * Throws IllegalStateException if the effective deployment provider is not available.
     */
    public Optional<Integer> setReplicas(String name, int replicas) {
        return withFunctionLock(name, () -> {
            RegisteredFunction function = registry.getRegistered(name).orElse(null);
            if (function == null) {
                return Optional.empty();
            }
            if (function.deploymentMetadata().effectiveExecutionMode() != ExecutionMode.DEPLOYMENT) {
                throw new IllegalArgumentException("Function '" + name + "' is not in DEPLOYMENT mode");
            }
            managedDeploymentCoordinator.setReplicas(function, replicas);
            log.info("Set replicas for function {} to {}", name, replicas);
            return Optional.of(replicas);
        });
    }

    public Optional<FunctionSpec> remove(String name) {
        return withFunctionLock(name, () -> {
            RegisteredFunction existing = registry.removeRegistered(name);
            if (existing != null) {
                List<FunctionRegistrationListener> notified = new ArrayList<>();
                try {
                    for (FunctionRegistrationListener listener : listeners) {
                        listener.onRemove(name);
                        notified.add(listener);
                    }
                    if (existing.deploymentMetadata().effectiveExecutionMode() == ExecutionMode.DEPLOYMENT) {
                        managedDeploymentCoordinator.deprovision(existing);
                    }
                } catch (RuntimeException e) {
                    rollbackRemovalListeners(existing.spec(), notified, e);
                    registry.put(existing);
                    throw e;
                }
                return Optional.of(existing.spec());
            }
            return Optional.empty();
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
                        provisionResult.endpointUrl()
                )
        );
    }

    private FunctionSpec withEffectiveProvisioning(FunctionSpec spec, ProvisionResult provisionResult) {
        return new FunctionSpec(
                spec.name(),
                spec.image(),
                spec.command(),
                spec.env(),
                spec.resources(),
                spec.timeoutMs(),
                spec.concurrency(),
                spec.queueSize(),
                spec.maxRetries(),
                provisionResult.endpointUrl(),
                provisionResult.effectiveExecutionMode(),
                spec.runtimeMode(),
                spec.runtimeCommand(),
                spec.scalingConfig(),
                spec.imagePullSecrets()
        );
    }

    private void rollbackProvisionedRegistration(RegisteredFunction function, RuntimeException failure) {
        if (!managedDeploymentCoordinator.isManagedDeployment(function)) {
            return;
        }
        try {
            managedDeploymentCoordinator.deprovision(function);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void notifyRegisterListeners(FunctionSpec spec) {
        List<FunctionRegistrationListener> notified = new ArrayList<>();
        try {
            for (FunctionRegistrationListener listener : listeners) {
                listener.onRegister(spec);
                notified.add(listener);
            }
        } catch (RuntimeException e) {
            rollbackRegistrationListeners(spec.name(), notified, e);
            throw e;
        }
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

    private <T> T withFunctionLock(String functionName, Supplier<T> action) {
        LockEntry lockEntry = acquireLockEntry(functionName);
        lockEntry.lock.lock();
        try {
            return action.get();
        } finally {
            lockEntry.lock.unlock();
            releaseLockEntry(functionName, lockEntry);
        }
    }

    private LockEntry acquireLockEntry(String functionName) {
        return functionLocks.compute(functionName, (ignored, existing) -> {
            LockEntry entry = existing == null ? new LockEntry() : existing;
            entry.users++;
            return entry;
        });
    }

    private void releaseLockEntry(String functionName, LockEntry lockEntry) {
        functionLocks.computeIfPresent(functionName, (ignored, existing) -> {
            if (existing != lockEntry) {
                return existing;
            }
            existing.users--;
            return existing.users == 0 ? null : existing;
        });
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }
}
