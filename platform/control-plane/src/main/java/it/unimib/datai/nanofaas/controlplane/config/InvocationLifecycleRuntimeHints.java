package it.unimib.datai.nanofaas.controlplane.config;

import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.TypeReference;

import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/** Native metadata for invocation boundaries whose concrete types are erased from AOT signatures. */
public class InvocationLifecycleRuntimeHints implements RuntimeHintsRegistrar {

    /**
     * The admin runtime-config envelope, registered by name.
     *
     * <p>This is the same erasure as {@link InvocationResponse} below, one hop further out: the
     * endpoint answers through {@code Mono<ResponseEntity<Object>>}, so neither the envelope's own
     * type nor the snapshot nested inside it is visible to HTTP binding inference. Now that the
     * scheduling strategy is reachable through {@code PATCH /v1/admin/runtime-config/scheduler}
     * and published by {@code GET} on the same path (issue #208, Task 13), that envelope is part
     * of the contract a native image is expected to serve.
     *
     * <p>By name, not by class, because both types live in the {@code runtime-config} module
     * (package {@code it.unimib.datai.nanofaas.modules.runtimeconfig}), which the core sees at
     * runtime only — modules are a {@code runtimeOnly} dependency of {@code :control-plane},
     * which is also why {@code SchedulerConfiguration} orders itself after the queue modules by
     * class name. Accessors are what Jackson serialization needs; the request side
     * ({@code PatchRequest}) is a direct {@code @RequestBody} parameter and is inferred.
     */
    private static final List<String> ERASED_ADMIN_ENVELOPE_TYPES = List.of(
            "it.unimib.datai.nanofaas.modules.runtimeconfig.AdminRuntimeConfigController$PatchResponse",
            "it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigSnapshot");

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // ResponseEntity<Object> hides the envelope (and nested ErrorInfo) from HTTP binding inference.
        new BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(), InvocationResponse.class);
        // Metadata compiled into the image at build time, not runtime reflection. Nothing here
        // discovers a strategy by reflection or by a plugin scan: the strategies are plain beans
        // handed to StrategyRegistry's constructor, and an id absent from that list is refused.
        for (String type : ERASED_ADMIN_ENVELOPE_TYPES) {
            hints.reflection().registerType(TypeReference.of(type), MemberCategory.INVOKE_PUBLIC_METHODS);
        }
        // The bean is declared as ScheduledExecutorService; Spring destroys the concrete pool reflectively.
        // Register the override and the parent target observed in native shutdown stacks, not every pool method.
        for (Class<?> pool : List.of(ScheduledThreadPoolExecutor.class, ThreadPoolExecutor.class)) {
            hints.reflection().registerType(pool, type -> type.withMethod("shutdown", List.of(), ExecutableMode.INVOKE));
        }
    }
}
