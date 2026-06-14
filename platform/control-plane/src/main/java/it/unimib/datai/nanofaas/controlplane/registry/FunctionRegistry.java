package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class FunctionRegistry {
    private final Map<String, RegisteredFunction> functions = new ConcurrentHashMap<>();

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
        RegisteredFunction previous = functions.put(spec.name(), RegisteredFunction.nonManaged(spec));
        return previous == null ? null : previous.spec();
    }

    public RegisteredFunction put(RegisteredFunction function) {
        return functions.put(function.name(), function);
    }

    /**
     * Atomically puts the spec if no mapping exists for the given name.
     *
     * @param spec the function spec to put
     * @return the previous value if one existed, or null if the put succeeded
     */
    public FunctionSpec putIfAbsent(FunctionSpec spec) {
        RegisteredFunction previous = functions.putIfAbsent(spec.name(), RegisteredFunction.nonManaged(spec));
        return previous == null ? null : previous.spec();
    }

    public RegisteredFunction putIfAbsent(RegisteredFunction function) {
        return functions.putIfAbsent(function.name(), function);
    }

    public FunctionSpec remove(String name) {
        RegisteredFunction previous = functions.remove(name);
        return previous == null ? null : previous.spec();
    }

    public RegisteredFunction removeRegistered(String name) {
        return functions.remove(name);
    }
}
