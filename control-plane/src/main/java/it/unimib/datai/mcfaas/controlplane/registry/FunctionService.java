package com.mcfaas.controlplane.registry;

import com.mcfaas.common.model.FunctionSpec;
import com.mcfaas.controlplane.queue.QueueManager;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;

@Service
public class FunctionService {
    private final FunctionRegistry registry;
    private final QueueManager queueManager;
    private final FunctionSpecResolver resolver;

    public FunctionService(FunctionRegistry registry, QueueManager queueManager, FunctionDefaults defaults) {
        this.registry = registry;
        this.queueManager = queueManager;
        this.resolver = new FunctionSpecResolver(defaults);
    }

    public Collection<FunctionSpec> list() {
        return registry.list();
    }

    public Optional<FunctionSpec> get(String name) {
        return registry.get(name);
    }

    public Optional<FunctionSpec> register(FunctionSpec spec) {
        FunctionSpec resolved = resolver.resolve(spec);
        // Atomic check-and-put: returns null if successful, or existing value if already present
        FunctionSpec existing = registry.putIfAbsent(resolved);
        if (existing != null) {
            // Function already exists
            return Optional.empty();
        }
        // Registration succeeded - create the queue
        queueManager.getOrCreate(resolved);
        return Optional.of(resolved);
    }

    public Optional<FunctionSpec> remove(String name) {
        FunctionSpec removed = registry.remove(name);
        if (removed != null) {
            queueManager.remove(name);
            return Optional.of(removed);
        }
        return Optional.empty();
    }
}
