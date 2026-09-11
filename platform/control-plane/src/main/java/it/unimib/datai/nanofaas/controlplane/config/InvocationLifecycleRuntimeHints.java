package it.unimib.datai.nanofaas.controlplane.config;

import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.ExecutableMode;

import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/** Native metadata for invocation boundaries whose concrete types are erased from AOT signatures. */
public class InvocationLifecycleRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // ResponseEntity<Object> hides the envelope (and nested ErrorInfo) from HTTP binding inference.
        new BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(), InvocationResponse.class);
        // The bean is declared as ScheduledExecutorService; Spring destroys the concrete pool reflectively.
        // Register the override and the parent target observed in native shutdown stacks, not every pool method.
        for (Class<?> pool : List.of(ScheduledThreadPoolExecutor.class, ThreadPoolExecutor.class)) {
            hints.reflection().registerType(pool, type -> type.withMethod("shutdown", List.of(), ExecutableMode.INVOKE));
        }
    }
}
