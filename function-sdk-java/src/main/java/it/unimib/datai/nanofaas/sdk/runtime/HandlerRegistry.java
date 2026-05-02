package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Resolves the single active {@link FunctionHandler} bean that the runtime should execute.
 *
 * <p>The registry exists because the handler is discovered through Spring bean registration, but
 * the runtime needs exactly one active implementation at request time. When multiple handlers are
 * present, the {@code FUNCTION_HANDLER} setting selects the bean name to use.</p>
 */
@Component
public class HandlerRegistry {

    private final FunctionHandler resolved;

    public HandlerRegistry(Map<String, FunctionHandler> handlers, RuntimeSettings runtimeSettings) {
        if (handlers.isEmpty()) {
            throw new IllegalStateException("No FunctionHandler beans registered");
        }
        String name = runtimeSettings.functionHandler();
        if (name != null) {
            if (handlers.containsKey(name)) {
                this.resolved = handlers.get(name);
                return;
            }
            throw new IllegalStateException(
                    "Multiple FunctionHandler beans found: " + handlers.keySet()
                    + ". Set FUNCTION_HANDLER env to one of these names.");
        }
        if (handlers.size() == 1) {
            this.resolved = handlers.values().iterator().next();
            return;
        }
        throw new IllegalStateException(
                "Multiple FunctionHandler beans found: " + handlers.keySet()
                + ". Set FUNCTION_HANDLER env to one of these names.");
    }

    public FunctionHandler resolve() {
        return resolved;
    }
}
