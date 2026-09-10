package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FunctionRegistry {
    private final FunctionCatalog catalog;
    private volatile Map<String, RegisteredFunction> functions; // NOSONAR: replaced wholesale with an immutable snapshot, never mutated in place
    private final FunctionApplicationState applicationState;

    public FunctionRegistry() {
        this.catalog = null;
        this.functions = Map.of();
        this.applicationState = new FunctionApplicationState(functions.keySet());
    }

    @Autowired
    public FunctionRegistry(FunctionCatalog catalog) {
        this.catalog = catalog;
        this.functions = immutableByName(catalog.load());
        this.applicationState = new FunctionApplicationState(functions.keySet());
    }

    public Collection<FunctionSpec> list() {
        return functions.values().stream()
                .map(RegisteredFunction::spec)
                .toList();
    }

    public Collection<RegisteredFunction> listRegistered() {
        return functions.values().stream().toList();
    }

    public Optional<FunctionSpec> get(String name) {
        return getRegistered(name).map(RegisteredFunction::spec);
    }

    public Optional<RegisteredFunction> getRegistered(String name) {
        return Optional.ofNullable(functions.get(name));
    }

    public FunctionSpec put(FunctionSpec spec) {
        RegisteredFunction previous = put(RegisteredFunction.nonManaged(spec));
        return previous == null ? null : previous.spec();
    }

    public synchronized RegisteredFunction put(RegisteredFunction function) {
        RegisteredFunction current = functions.get(function.name());
        if (function.equals(current)) {
            return current;
        }
        Map<String, RegisteredFunction> next = new HashMap<>(functions);
        RegisteredFunction previous = next.put(function.name(), function);
        saveAndPublish(next);
        return previous;
    }

    /**
     * Atomically puts the spec if no mapping exists for the given name.
     *
     * @param spec the function spec to put
     * @return the previous value if one existed, or null if the put succeeded
     */
    public FunctionSpec putIfAbsent(FunctionSpec spec) {
        RegisteredFunction previous = putIfAbsent(RegisteredFunction.nonManaged(spec));
        return previous == null ? null : previous.spec();
    }

    public synchronized RegisteredFunction putIfAbsent(RegisteredFunction function) {
        RegisteredFunction previous = functions.get(function.name());
        if (previous != null) {
            return previous;
        }
        Map<String, RegisteredFunction> next = new HashMap<>(functions);
        next.put(function.name(), function);
        saveAndPublish(next);
        return null;
    }

    public FunctionSpec remove(String name) {
        RegisteredFunction previous = removeRegistered(name);
        return previous == null ? null : previous.spec();
    }

    public synchronized RegisteredFunction removeRegistered(String name) {
        RegisteredFunction previous = functions.get(name);
        if (previous == null) {
            return null;
        }
        Map<String, RegisteredFunction> next = new HashMap<>(functions);
        next.remove(name);
        saveAndPublish(next);
        return previous;
    }

    synchronized RegisteredFunction detach(String name) {
        Map<String, RegisteredFunction> next = new HashMap<>(functions);
        RegisteredFunction detached = next.remove(name);
        functions = Map.copyOf(next);
        return detached;
    }

    synchronized void restoreDetached(RegisteredFunction function) {
        if (function != null) {
            Map<String, RegisteredFunction> next = new HashMap<>(functions);
            next.put(function.name(), function);
            functions = Map.copyOf(next);
        }
    }

    synchronized void restoreDetachedPendingRemoval(RegisteredFunction function,
                                                     Collection<String> remainingResources) {
        applicationState.retainName(function.name());
        applicationState.markPartialRemoval(function.name(), List.copyOf(remainingResources));
        restoreDetached(function);
    }

    synchronized void restoreDetachedUnavailable(RegisteredFunction function, String reason) {
        applicationState.retainName(function.name());
        applicationState.markUnavailable(function.name(), reason);
        restoreDetached(function);
    }

    synchronized void persistCurrentSnapshot() {
        if (catalog != null) {
            catalog.save(functions.values());
        }
        publishDurable(functions);
    }

    synchronized void replaceAllDurably(Collection<RegisteredFunction> replacement) {
        Map<String, RegisteredFunction> next = immutableByName(replacement);
        if (catalog != null) {
            catalog.save(next.values());
        }
        publishDurable(next);
    }

    private void saveAndPublish(Map<String, RegisteredFunction> next) {
        if (catalog != null) {
            catalog.save(next.values());
        }
        publishDurable(next);
    }

    FunctionApplicationState applicationState() {
        return applicationState;
    }

    private void publishDurable(Map<String, RegisteredFunction> next) {
        Map<String, RegisteredFunction> snapshot = Map.copyOf(next);
        functions = snapshot;
        applicationState.retainOnly(snapshot.keySet());
    }

    private static Map<String, RegisteredFunction> immutableByName(Collection<RegisteredFunction> registered) {
        Map<String, RegisteredFunction> byName = new HashMap<>();
        for (RegisteredFunction function : registered) {
            if (byName.put(function.name(), function) != null) {
                throw new IllegalArgumentException("Duplicate function name: " + function.name());
            }
        }
        return Map.copyOf(byName);
    }
}
