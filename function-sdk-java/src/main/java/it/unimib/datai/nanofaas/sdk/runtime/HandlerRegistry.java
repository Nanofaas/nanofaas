package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class HandlerRegistry {
    private final ApplicationContext context;
    private final String envFunctionHandler = System.getenv("FUNCTION_HANDLER");
    private volatile FunctionHandler cached;

    public HandlerRegistry(ApplicationContext context) {
        this.context = context;
    }

    public FunctionHandler resolve() {
        FunctionHandler h = cached;
        if (h != null) {
            return h;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            Map<String, FunctionHandler> handlers = context.getBeansOfType(FunctionHandler.class);
            if (handlers.isEmpty()) {
                throw new IllegalStateException("No FunctionHandler beans registered");
            }
            if (handlers.size() == 1) {
                cached = handlers.values().iterator().next();
                return cached;
            }
            if (envFunctionHandler != null && handlers.containsKey(envFunctionHandler)) {
                cached = handlers.get(envFunctionHandler);
                return cached;
            }
            throw new IllegalStateException("Multiple FunctionHandler beans found; set FUNCTION_HANDLER env");
        }
    }
}
