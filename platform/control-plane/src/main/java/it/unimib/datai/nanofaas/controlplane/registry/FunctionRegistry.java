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
    private volatile RegistrySnapshot functions; // NOSONAR: replaced wholesale with an immutable snapshot, never mutated in place
    private final FunctionApplicationState applicationState;

    public FunctionRegistry() {
        this.catalog = null;
        this.functions = RegistrySnapshot.EMPTY;
        this.applicationState = new FunctionApplicationState(functions.recovery().keySet());
    }

    @Autowired
    public FunctionRegistry(FunctionCatalog catalog) {
        this.catalog = catalog;
        Map<String, RegisteredFunction> recovered = immutableByName(catalog.load());
        this.functions = new RegistrySnapshot(recovered, startupPublicView(recovered));
        this.applicationState = new FunctionApplicationState(recovered.keySet());
    }

    public Collection<FunctionSpec> list() {
        return functions.publicView().values().stream()
                .map(RegisteredFunction::spec)
                .toList();
    }

    public Collection<RegisteredFunction> listRegistered() {
        return functions.publicView().values().stream().toList();
    }

    public Optional<FunctionSpec> get(String name) {
        return getRegistered(name).map(RegisteredFunction::spec);
    }

    public Optional<RegisteredFunction> getRegistered(String name) {
        return Optional.ofNullable(functions.publicView().get(name));
    }

    public FunctionSpec put(FunctionSpec spec) {
        RegisteredFunction previous = put(RegisteredFunction.nonManaged(spec));
        return previous == null ? null : previous.spec();
    }

    public synchronized RegisteredFunction put(RegisteredFunction function) {
        RegistrySnapshot currentSnapshot = functions;
        RegisteredFunction current = currentSnapshot.recovery().get(function.name());
        if (function.equals(current)) {
            return current;
        }
        Map<String, RegisteredFunction> next = new HashMap<>(currentSnapshot.recovery());
        RegisteredFunction previous = next.put(function.name(), function);
        Map<String, RegisteredFunction> publicNext = new HashMap<>(currentSnapshot.publicView());
        publicNext.put(function.name(), function);
        saveAndPublish(next, publicNext);
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
        RegistrySnapshot currentSnapshot = functions;
        RegisteredFunction previous = currentSnapshot.recovery().get(function.name());
        if (previous != null) {
            return previous;
        }
        Map<String, RegisteredFunction> next = new HashMap<>(currentSnapshot.recovery());
        next.put(function.name(), function);
        Map<String, RegisteredFunction> publicNext = new HashMap<>(currentSnapshot.publicView());
        publicNext.put(function.name(), function);
        saveAndPublish(next, publicNext);
        return null;
    }

    public FunctionSpec remove(String name) {
        RegisteredFunction previous = removeRegistered(name);
        return previous == null ? null : previous.spec();
    }

    public synchronized RegisteredFunction removeRegistered(String name) {
        RegistrySnapshot currentSnapshot = functions;
        RegisteredFunction previous = currentSnapshot.recovery().get(name);
        if (previous == null) {
            return null;
        }
        Map<String, RegisteredFunction> next = new HashMap<>(currentSnapshot.recovery());
        next.remove(name);
        Map<String, RegisteredFunction> publicNext = new HashMap<>(currentSnapshot.publicView());
        publicNext.remove(name);
        saveAndPublish(next, publicNext);
        return previous;
    }

    synchronized RegisteredFunction detach(String name) {
        RegistrySnapshot currentSnapshot = functions;
        Map<String, RegisteredFunction> next = new HashMap<>(currentSnapshot.recovery());
        RegisteredFunction detached = next.remove(name);
        Map<String, RegisteredFunction> publicNext = new HashMap<>(currentSnapshot.publicView());
        publicNext.remove(name);
        functions = snapshot(next, publicNext);
        return detached;
    }

    synchronized void restoreDetached(RegisteredFunction function) {
        if (function != null) {
            RegistrySnapshot currentSnapshot = functions;
            Map<String, RegisteredFunction> next = new HashMap<>(currentSnapshot.recovery());
            next.put(function.name(), function);
            Map<String, RegisteredFunction> publicNext = new HashMap<>(currentSnapshot.publicView());
            publicNext.put(function.name(), function);
            functions = snapshot(next, publicNext);
        }
    }

    synchronized void restoreDetachedPendingRemoval(RegisteredFunction function,
                                                     Collection<String> remainingResources) {
        applicationState.retainName(function.name());
        applicationState.markPartialRemoval(function.name(), List.copyOf(remainingResources));
        restoreDetachedForRecovery(function);
    }

    synchronized void restoreDetachedUnavailable(RegisteredFunction function, String reason) {
        applicationState.retainName(function.name());
        applicationState.markUnavailable(function.name(), reason);
        restoreDetachedForRecovery(function);
    }

    synchronized void persistCurrentSnapshot() {
        RegistrySnapshot currentSnapshot = functions;
        if (catalog != null) {
            catalog.save(currentSnapshot.recovery().values());
        }
        publishDurable(currentSnapshot.recovery(), currentSnapshot.publicView());
    }

    synchronized void replaceAllDurably(Collection<RegisteredFunction> replacement) {
        Map<String, RegisteredFunction> next = immutableByName(replacement);
        if (catalog != null) {
            catalog.save(next.values());
        }
        publishDurable(next, next);
    }

    synchronized void replaceAllAfterRestore(Collection<RegisteredFunction> replacement,
                                             Collection<RegisteredFunction> available) {
        Map<String, RegisteredFunction> next = immutableByName(replacement);
        Map<String, RegisteredFunction> publicNext = immutableByName(available);
        if (!next.entrySet().containsAll(publicNext.entrySet())) {
            throw new IllegalArgumentException("Available restored functions must be recovery records");
        }
        if (catalog != null) {
            catalog.save(next.values());
        }
        publishDurable(next, publicNext);
    }

    Collection<RegisteredFunction> listRegisteredForRecovery() {
        return functions.recovery().values().stream().toList();
    }

    private void saveAndPublish(Map<String, RegisteredFunction> next,
                                Map<String, RegisteredFunction> publicNext) {
        if (catalog != null) {
            catalog.save(next.values());
        }
        publishDurable(next, publicNext);
    }

    FunctionApplicationState applicationState() {
        return applicationState;
    }

    private void publishDurable(Map<String, RegisteredFunction> next,
                                Map<String, RegisteredFunction> publicNext) {
        functions = snapshot(next, publicNext);
        applicationState.retainOnly(functions.recovery().keySet());
    }

    private void restoreDetachedForRecovery(RegisteredFunction function) {
        RegistrySnapshot currentSnapshot = functions;
        Map<String, RegisteredFunction> next = new HashMap<>(currentSnapshot.recovery());
        next.put(function.name(), function);
        Map<String, RegisteredFunction> publicNext = new HashMap<>(currentSnapshot.publicView());
        publicNext.remove(function.name());
        functions = snapshot(next, publicNext);
    }

    private static RegistrySnapshot snapshot(Map<String, RegisteredFunction> recovery,
                                             Map<String, RegisteredFunction> publicView) {
        return new RegistrySnapshot(Map.copyOf(recovery), Map.copyOf(publicView));
    }

    private static Map<String, RegisteredFunction> startupPublicView(
            Map<String, RegisteredFunction> recovered) {
        Map<String, RegisteredFunction> publicView = new HashMap<>();
        recovered.forEach((name, function) -> {
            if (function.managedDeploymentTarget().isEmpty()) {
                publicView.put(name, function);
            }
        });
        return Map.copyOf(publicView);
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

    private record RegistrySnapshot(Map<String, RegisteredFunction> recovery,
                                    Map<String, RegisteredFunction> publicView) {
        private static final RegistrySnapshot EMPTY = new RegistrySnapshot(Map.of(), Map.of());
    }
}
