package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberHint;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeHint;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.20.0 shipped a native image that started and then died in the catalog restorer, because
 * nothing registered the persisted records for reflection. Helm only reported a readiness
 * timeout. These assertions fail on the JVM, without a native build, if the hints go away.
 */
class FunctionCatalogRuntimeHintsTest {

    private static RuntimeHints registered() {
        RuntimeHints hints = new RuntimeHints();
        new FunctionCatalogRuntimeHints()
                .registerHints(hints, FunctionCatalogRuntimeHintsTest.class.getClassLoader());
        return hints;
    }

    private static Set<String> registeredMethods(RuntimeHints hints, Class<?> type) {
        TypeHint hint = hints.reflection().getTypeHint(type);
        assertThat(hint).as("%s is not registered for reflection at all", type.getSimpleName()).isNotNull();
        return hint.methods().map(MemberHint::getName).collect(Collectors.toSet());
    }

    @Test
    void registersTheSnapshotJacksonIntrospectsFirst() {
        assertThat(registeredMethods(registered(), FunctionCatalogSnapshot.class))
                .as("Class.getRecordComponents() is what threw UnsupportedFeatureError")
                .contains("schemaVersion", "functions");
    }

    @Test
    void reachesTheRecordsNestedInsideTheSnapshot() {
        RuntimeHints hints = registered();

        assertThat(registeredMethods(hints, RegisteredFunction.class)).contains("spec", "deploymentMetadata");
        assertThat(registeredMethods(hints, DeploymentMetadata.class)).contains("effectiveExecutionMode");
        assertThat(registeredMethods(hints, FunctionSpec.class)).contains("name");
    }

    @Test
    void registersEveryComponentOfEveryPersistedRecordSoANewFieldCannotBreakTheImage() {
        RuntimeHints hints = registered();

        for (Class<?> type : Set.of(FunctionCatalogSnapshot.class, RegisteredFunction.class,
                DeploymentMetadata.class, FunctionSpec.class)) {
            Set<String> accessors = Arrays.stream(type.getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toSet());

            assertThat(registeredMethods(hints, type))
                    .as("every record component accessor of %s must be in the reflection config",
                            type.getSimpleName())
                    .containsAll(accessors);
        }
    }
}
