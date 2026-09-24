package it.unimib.datai.nanofaas.controlplane.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.annotation.ReflectiveRuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import java.lang.reflect.RecordComponent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These records leave the controllers through ResponseEntity&lt;Object&gt;, so AOT cannot infer
 * them from any signature. A native image then refuses to serialize them — GET
 * /v1/functions/{name}/replicas answered 500 with "Record components not available" — while the
 * JVM, which needs no metadata, serves them fine. Jackson reads a record through its component
 * accessors, so every accessor must be invocable.
 */
class ErasedResponseBodyHintsTest {

    private static RuntimeHints hints() {
        RuntimeHints hints = new RuntimeHints();
        new ReflectiveRuntimeHintsRegistrar()
                .registerRuntimeHints(hints, FunctionController.class, InvocationController.class);
        return hints;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "it.unimib.datai.nanofaas.controlplane.api.FunctionResponse",
            "it.unimib.datai.nanofaas.controlplane.api.ReplicaResponse",
            "it.unimib.datai.nanofaas.controlplane.api.ReplicaStatusResponse",
            "it.unimib.datai.nanofaas.controlplane.api.InvocationController$InvocationQuotaError"})
    void everyRecordAccessorOfAnErasedBodyIsInvocableInANativeImage(String typeName) throws Exception {
        RuntimeHints hints = hints();
        RecordComponent[] components = Class.forName(typeName).getRecordComponents();

        assertThat(components).isNotEmpty();
        for (RecordComponent component : components) {
            assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(component.getAccessor()))
                    .as("%s.%s()", typeName, component.getName())
                    .accepts(hints);
        }
    }
}
