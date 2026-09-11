package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class InvocationNativeHintsTest {
    private RuntimeHints applicationHints() throws ReflectiveOperationException {
        RuntimeHints hints = new RuntimeHints();
        for (var registrar : ControlPlaneApplication.class.getAnnotation(ImportRuntimeHints.class).value()) {
            registrar.getDeclaredConstructor().newInstance().registerHints(hints, getClass().getClassLoader());
        }
        return hints;
    }

    @Test
    void erasedHttpResponseRetainsEveryRecordAccessorForNativeJackson() throws Exception {
        RuntimeHints hints = applicationHints();
        for (var component : InvocationResponse.class.getRecordComponents()) {
            assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(component.getAccessor()).test(hints))
                    .as("native Jackson record accessor %s", component.getName()).isTrue();
        }
    }

    @Test
    void concreteWakeUpSchedulerDestroyMethodIsInvocableInNativeImage() throws Exception {
        ScheduledExecutorService scheduler = new DeploymentWakeUpConfiguration().deploymentWakeUpTimeoutScheduler();
        try {
            // The native stack resolves this parent method when concrete override metadata is absent.
            var nativeDestroyMethod = java.util.concurrent.ThreadPoolExecutor.class.getMethod("shutdown");
            assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(nativeDestroyMethod).test(applicationHints()))
                    .as("native shutdown stack target %s", nativeDestroyMethod).isTrue();
            var destroyMethod = scheduler.getClass().getMethod("shutdown");
            assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(destroyMethod).test(applicationHints()))
                    .as("Spring resolves the concrete inherited destroy method %s", destroyMethod).isTrue();
        } finally {
            scheduler.shutdownNow();
        }
    }
}
