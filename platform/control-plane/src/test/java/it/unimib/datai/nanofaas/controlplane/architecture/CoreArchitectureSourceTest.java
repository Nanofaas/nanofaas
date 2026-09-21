package it.unimib.datai.nanofaas.controlplane.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class CoreArchitectureSourceTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "file:/repo/platform/control-plane/build/classes/java/main/Example.class",
            "jar:file:/repo/platform/control-plane/build/libs/control-plane-0.21.0-plain.jar!/Example.class"
    })
    void acceptsCoreSourcesFromClassesDirectoryAndJar(String source) {
        assertThat(CoreArchitectureTest.isCoreSource(URI.create(source))).isTrue();
    }

    @Test
    void rejectsSourcesFromSiblingModules() {
        URI source = URI.create(
                "file:/repo/platform/control-plane-modules/async-queue/build/classes/java/main/Example.class");

        assertThat(CoreArchitectureTest.isCoreSource(source)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:/repo/platform/control-plane-spi/build/classes/java/main/Example.class",
            "jar:file:/repo/platform/control-plane-spi/build/libs/control-plane-spi-0.21.0.jar!/Example.class"
    })
    void acceptsContractLibrarySourcesFromClassesDirectoryAndJar(String source) {
        assertThat(CoreArchitectureTest.isContractSource(URI.create(source))).isTrue();
    }

    /**
     * The three predicates must not overlap. Each of the other two paths contains a shorter
     * one's marker as a substring in some spelling ("control-plane" is a prefix of
     * "control-plane-spi"; "control-plane" is not, but "platform/control-plane" and
     * "platform/execution-runtime" are siblings whose only difference is the last segment), so a
     * substring check written the other way round would collapse two of them and the namespace
     * rule would stop distinguishing them.
     */
    @Test
    void theSourcePredicatesDoNotAcceptEachOthersSources() {
        URI core = URI.create("file:/repo/platform/control-plane/build/classes/java/main/Example.class");
        URI contract = URI.create("file:/repo/platform/control-plane-spi/build/classes/java/main/Example.class");
        URI runtime = URI.create("file:/repo/platform/execution-runtime/build/classes/java/main/Example.class");

        for (URI foreign : java.util.List.of(contract, runtime)) {
            assertThat(CoreArchitectureTest.isCoreSource(foreign)).isFalse();
        }
        for (URI foreign : java.util.List.of(core, runtime)) {
            assertThat(CoreArchitectureTest.isContractSource(foreign)).isFalse();
        }
        for (URI foreign : java.util.List.of(core, contract)) {
            assertThat(CoreArchitectureTest.isRuntimeSource(foreign)).isFalse();
        }
    }

    /**
     * Task 9 (issue #208) moved the execution store, capacity and input classes into
     * {@code :execution-runtime} and, one task later, the two scheduling strategies began
     * contributing module classes to the same {@code controlplane} namespace the R6 rule governs.
     * Without a predicate that recognises the runtime — and one that a module's own build
     * directory cannot satisfy — {@code controlplane_namespace_is_owned_by_core} would reject
     * every class of the mandatory runtime, or accept an optional module's copy of one.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "file:/repo/platform/execution-runtime/build/classes/java/main/Example.class",
            "jar:file:/repo/platform/execution-runtime/build/libs/execution-runtime-0.22.0.jar!/Example.class"
    })
    void acceptsExecutionRuntimeSourcesFromClassesDirectoryAndJar(String source) {
        assertThat(CoreArchitectureTest.isRuntimeSource(URI.create(source))).isTrue();
    }

    @Test
    void rejectsAnOptionalModulesDirectoryAsTheExecutionRuntime() {
        URI module = URI.create(
                "file:/repo/platform/modules/async-queue/build/classes/java/main/Example.class");

        assertThat(CoreArchitectureTest.isRuntimeSource(module)).isFalse();
    }
}
