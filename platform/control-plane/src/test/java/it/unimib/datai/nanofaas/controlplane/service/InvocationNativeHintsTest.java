package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
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
        ScheduledExecutorService scheduler = new ManagedDeploymentOrchestration().deploymentWakeUpTimeoutScheduler();
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

    /**
     * Task 13a (issue #208): the admin runtime-config envelope is registered by NAME, because the
     * core cannot compile against the module that owns it. A typo in that name would silently
     * produce no hint at all — an ArchUnit-style rule cannot see it, and only a native build would
     * notice — so this pins the name against the real class: it must resolve, be the erased
     * envelope the controller actually answers with, and have its accessors covered.
     */
    @Test
    @EnabledIfSystemProperty(named = "nanofaas.selectedControlPlaneModules", matches = ".*\\bruntime-config\\b.*")
    void erasedAdminEnvelopeIsRegisteredByANameThatResolves() throws Exception {
        RuntimeHints hints = applicationHints();
        String envelope = "it.unimib.datai.nanofaas.modules.runtimeconfig"
                + ".AdminRuntimeConfigController$PatchResponse";
        Class<?> envelopeType = Class.forName(envelope);
        assertThat(envelopeType.getRecordComponents())
                .as("%s must still be the record the controller returns", envelope)
                .isNotEmpty();
        assertThat(RuntimeHintsPredicates.reflection().onType(TypeReference.of(envelope))
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS).test(hints))
                .as("native Jackson accessors for %s", envelope)
                .isTrue();
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(TypeReference.of("it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigSnapshot"))
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS).test(hints))
                .as("the snapshot nested inside that envelope")
                .isTrue();
    }
}
