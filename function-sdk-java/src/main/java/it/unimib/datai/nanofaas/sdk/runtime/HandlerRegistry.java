package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class HandlerRegistry {
    private final ApplicationContext context;
    private final RuntimeSettings runtimeSettings;
    private volatile FunctionHandler cached;

    public HandlerRegistry(ApplicationContext context, RuntimeSettings runtimeSettings) {
        this.context = context;
        this.runtimeSettings = runtimeSettings;
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
            String functionHandler = runtimeSettings.functionHandler();
            if (functionHandler != null && handlers.containsKey(functionHandler)) {
                cached = handlers.get(functionHandler);
                return cached;
            }
            throw new IllegalStateException("Multiple FunctionHandler beans found; set FUNCTION_HANDLER env");
        }
    }
}
