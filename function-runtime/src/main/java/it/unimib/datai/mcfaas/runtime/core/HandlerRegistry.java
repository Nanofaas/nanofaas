package com.mcfaas.runtime.core;

import com.mcfaas.common.runtime.FunctionHandler;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class HandlerRegistry {
    private final ApplicationContext context;

    public HandlerRegistry(ApplicationContext context) {
        this.context = context;
    }

    public FunctionHandler resolve() {
        Map<String, FunctionHandler> handlers = context.getBeansOfType(FunctionHandler.class);
        if (handlers.isEmpty()) {
            throw new IllegalStateException("No FunctionHandler beans registered");
        }
        if (handlers.size() == 1) {
            return handlers.values().iterator().next();
        }
        String preferred = System.getenv("FUNCTION_HANDLER");
        if (preferred != null && handlers.containsKey(preferred)) {
            return handlers.get(preferred);
        }
        throw new IllegalStateException("Multiple FunctionHandler beans found; set FUNCTION_HANDLER env");
    }
}
