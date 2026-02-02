package it.unimib.datai.mcfaas.controlplane.registry;

import it.unimib.datai.mcfaas.common.model.FunctionSpec;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class FunctionRegistry {
    private final Map<String, FunctionSpec> functions = new ConcurrentHashMap<>();

    public Collection<FunctionSpec> list() {
        return functions.values();
    }

    public Optional<FunctionSpec> get(String name) {
        return Optional.ofNullable(functions.get(name));
    }

    public FunctionSpec put(FunctionSpec spec) {
        return functions.put(spec.name(), spec);
    }

    /**
     * Atomically puts the spec if no mapping exists for the given name.
     *
     * @param spec the function spec to put
     * @return the previous value if one existed, or null if the put succeeded
     */
    public FunctionSpec putIfAbsent(FunctionSpec spec) {
        return functions.putIfAbsent(spec.name(), spec);
    }

    public FunctionSpec remove(String name) {
        return functions.remove(name);
    }
}
