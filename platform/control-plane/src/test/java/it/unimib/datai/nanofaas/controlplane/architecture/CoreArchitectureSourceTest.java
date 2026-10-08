package it.unimib.datai.nanofaas.controlplane.architecture;

import it.unimib.datai.nanofaas.controlplane.api.FunctionController;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class CoreArchitectureSourceTest {

    @ParameterizedTest
    @CsvSource({
            "file:/repo/platform/control-plane/build/classes/java/main/a/B.class, file:/repo/platform/control-plane/build/classes/java/main/, true",
            "file:/repo/platform/libs/control-plane-spi/build/classes/java/main/a/B.class, file:/repo/platform/libs/control-plane-spi/build/classes/java/main/, true",
            "file:/repo%20copy/core/a/B.class, file:/repo%20copy/core/, true",
            "jar:file:/repo%20copy/spi.jar!/a/B.class, file:/repo%20copy/spi.jar, true",
            "jar:file:/repo/runtime.jar!/a/B.class, file:/repo/runtime.jar, true",
            "file:/repo/core-extra/a/B.class, file:/repo/core/, false",
            "jar:file:/repo/other.jar!/a/B.class, file:/repo/spi.jar, false",
            "jar:file:/repo/spi.jar-extra!/a/B.class, file:/repo/spi.jar, false",
            "file:/repo/core/../other/a/B.class, file:/repo/core/, false"
    })
    void recognizesOnlyClassesInsideTheCodeSource(String source, String owner, boolean expected) {
        assertThat(CoreArchitectureTest.isFromCodeSource(URI.create(source), URI.create(owner)))
                .isEqualTo(expected);
    }

    @Test
    void rejectsMissingLocations() {
        assertThat(CoreArchitectureTest.isFromCodeSource(null, URI.create("file:/repo/core/"))).isFalse();
        assertThat(CoreArchitectureTest.isFromCodeSource(URI.create("file:/repo/core/a/B.class"), null)).isFalse();
    }

    @Test
    void theRealClassSourcesBelongToExactlyOneArtifact() throws Exception {
        URI core = sourceOf(FunctionController.class);
        URI contract = sourceOf(FunctionCatalogView.class);
        URI runtime = sourceOf(ExecutionRecord.class);

        assertThat(CoreArchitectureTest.isCoreSource(core)).isTrue();
        assertThat(CoreArchitectureTest.isContractSource(contract)).isTrue();
        assertThat(CoreArchitectureTest.isRuntimeSource(runtime)).isTrue();
        assertThat(CoreArchitectureTest.isCoreSource(contract)).isFalse();
        assertThat(CoreArchitectureTest.isCoreSource(runtime)).isFalse();
        assertThat(CoreArchitectureTest.isContractSource(core)).isFalse();
        assertThat(CoreArchitectureTest.isContractSource(runtime)).isFalse();
        assertThat(CoreArchitectureTest.isRuntimeSource(core)).isFalse();
        assertThat(CoreArchitectureTest.isRuntimeSource(contract)).isFalse();
    }

    private static URI sourceOf(Class<?> type) throws Exception {
        return type.getResource("/" + type.getName().replace('.', '/') + ".class").toURI();
    }
}
