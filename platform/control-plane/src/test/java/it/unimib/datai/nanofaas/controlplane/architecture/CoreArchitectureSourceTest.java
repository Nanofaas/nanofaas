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
     * The two predicates must not overlap: the contract library's path contains the core's name as
     * a prefix, so a substring check written the other way round would accept the SPI as core and
     * the namespace rule would stop distinguishing them.
     */
    @Test
    void theCoreAndContractPredicatesDoNotAcceptEachOthersSources() {
        URI core = URI.create("file:/repo/platform/control-plane/build/classes/java/main/Example.class");
        URI contract = URI.create("file:/repo/platform/control-plane-spi/build/classes/java/main/Example.class");

        assertThat(CoreArchitectureTest.isCoreSource(contract)).isFalse();
        assertThat(CoreArchitectureTest.isContractSource(core)).isFalse();
    }
}
