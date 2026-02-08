package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.dispatch.KubernetesResourceManager;
import it.unimib.datai.nanofaas.controlplane.queue.QueueManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;

@Service
public class FunctionService {
    private static final Logger log = LoggerFactory.getLogger(FunctionService.class);

    private final FunctionRegistry registry;
    private final QueueManager queueManager;
    private final FunctionSpecResolver resolver;
    private final KubernetesResourceManager resourceManager;

    public FunctionService(FunctionRegistry registry,
                           QueueManager queueManager,
                           FunctionDefaults defaults,
                           @Autowired(required = false) KubernetesResourceManager resourceManager) {
        this.registry = registry;
        this.queueManager = queueManager;
        this.resolver = new FunctionSpecResolver(defaults);
        this.resourceManager = resourceManager;
    }

    public Collection<FunctionSpec> list() {
        return registry.list();
    }

    public Optional<FunctionSpec> get(String name) {
        return registry.get(name);
    }

    public Optional<FunctionSpec> register(FunctionSpec spec) {
        FunctionSpec resolved = resolver.resolve(spec);

        // For DEPLOYMENT mode, provision K8s resources and set the endpoint URL
        if (resolved.executionMode() == ExecutionMode.DEPLOYMENT && resourceManager != null) {
            String serviceUrl = resourceManager.provision(resolved);
            resolved = new FunctionSpec(
                    resolved.name(),
                    resolved.image(),
                    resolved.command(),
                    resolved.env(),
                    resolved.resources(),
                    resolved.timeoutMs(),
                    resolved.concurrency(),
                    resolved.queueSize(),
                    resolved.maxRetries(),
                    serviceUrl,
                    resolved.executionMode(),
                    resolved.runtimeMode(),
                    resolved.runtimeCommand(),
                    resolved.scalingConfig()
            );
        }

        // Atomic check-and-put: returns null if successful, or existing value if already present
        FunctionSpec existing = registry.putIfAbsent(resolved);
        if (existing != null) {
            // Function already exists - deprovision what we just created
            if (resolved.executionMode() == ExecutionMode.DEPLOYMENT && resourceManager != null) {
                resourceManager.deprovision(resolved.name());
            }
            return Optional.empty();
        }
        // Registration succeeded - create the queue
        queueManager.getOrCreate(resolved);
        return Optional.of(resolved);
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
        FunctionSpec removed = registry.remove(name);
        if (removed != null) {
            queueManager.remove(name);
            if (removed.executionMode() == ExecutionMode.DEPLOYMENT && resourceManager != null) {
                resourceManager.deprovision(name);
            }
            return Optional.of(removed);
        }
        return Optional.empty();
    }
}
