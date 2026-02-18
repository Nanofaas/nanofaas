package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.dispatch.KubernetesResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
    private final KubernetesResourceManager resourceManager;
    private final ImageValidator imageValidator;
    private final List<FunctionRegistrationListener> listeners;
    private final ConcurrentHashMap<String, LockEntry> functionLocks = new ConcurrentHashMap<>();

    @Autowired
    public FunctionService(FunctionRegistry registry,
                           FunctionDefaults defaults,
                           @Autowired(required = false) KubernetesResourceManager resourceManager,
                           ImageValidator imageValidator,
                           @Autowired(required = false) List<FunctionRegistrationListener> listeners) {
        this.registry = registry;
        this.resolver = new FunctionSpecResolver(defaults);
        this.resourceManager = resourceManager;
        this.imageValidator = imageValidator;
        this.listeners = listeners == null ? List.of() : listeners;
    }

    public Collection<FunctionSpec> list() {
        return registry.list();
    }

    public Optional<FunctionSpec> get(String name) {
        return registry.get(name);
    }

    public Optional<FunctionSpec> register(FunctionSpec spec) {
        FunctionSpec initialResolved = resolver.resolve(spec);

        return withFunctionLock(initialResolved.name(), () -> {
            // Atomic winner selection happens before any side effects.
            FunctionSpec existing = registry.putIfAbsent(initialResolved);
            if (existing != null) {
                return Optional.empty();
            }

            FunctionSpec resolved;
            try {
                imageValidator.validate(initialResolved);
                resolved = initialResolved;
                if (initialResolved.executionMode() == ExecutionMode.DEPLOYMENT && resourceManager != null) {
                    String serviceUrl = resourceManager.provision(initialResolved);
                    resolved = new FunctionSpec(
                            initialResolved.name(),
                            initialResolved.image(),
                            initialResolved.command(),
                            initialResolved.env(),
                            initialResolved.resources(),
                            initialResolved.timeoutMs(),
                            initialResolved.concurrency(),
                            initialResolved.queueSize(),
                            initialResolved.maxRetries(),
                            serviceUrl,
                            initialResolved.executionMode(),
                            initialResolved.runtimeMode(),
                            initialResolved.runtimeCommand(),
                            initialResolved.scalingConfig(),
                            initialResolved.imagePullSecrets()
                    );
                    registry.put(resolved);
                }
            } catch (RuntimeException e) {
                registry.remove(initialResolved.name());
                throw e;
            }

            FunctionSpec registered = resolved;
            listeners.forEach(l -> l.onRegister(registered));
            return Optional.of(registered);
        });
    }

    /**
     * Sets the replica count for a DEPLOYMENT-mode function.
     * Returns the new replica count, or empty if function not found.
     * Throws IllegalArgumentException if function is not in DEPLOYMENT mode.
     * Throws IllegalStateException if KubernetesResourceManager is not available.
     */
    public Optional<Integer> setReplicas(String name, int replicas) {
        FunctionSpec spec = registry.get(name).orElse(null);
        if (spec == null) {
            return Optional.empty();
        }
        if (spec.executionMode() != ExecutionMode.DEPLOYMENT) {
            throw new IllegalArgumentException("Function '" + name + "' is not in DEPLOYMENT mode");
        }
        if (resourceManager == null) {
            throw new IllegalStateException("KubernetesResourceManager not available");
        }
        resourceManager.setReplicas(name, replicas);
        log.info("Set replicas for function {} to {}", name, replicas);
        return Optional.of(replicas);
    }

    public Optional<FunctionSpec> remove(String name) {
        return withFunctionLock(name, () -> {
            FunctionSpec removed = registry.remove(name);
            if (removed != null) {
                listeners.forEach(l -> l.onRemove(name));
                if (removed.executionMode() == ExecutionMode.DEPLOYMENT && resourceManager != null) {
                    resourceManager.deprovision(name);
                }
                return Optional.of(removed);
            }
            return Optional.empty();
        });
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
