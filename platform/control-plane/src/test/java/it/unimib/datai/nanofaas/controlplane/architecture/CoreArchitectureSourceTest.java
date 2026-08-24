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
            "jar:file:/repo/platform/control-plane/build/libs/control-plane-0.19.0-plain.jar!/Example.class"
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
}
