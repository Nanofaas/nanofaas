package it.unimib.datai.nanofaas.modules.buildmetadata;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class BuildMetadataControllerTest {

    private static BuildMetadataProvider fixedProvider() {
        Properties props = new Properties();
        props.setProperty("version", "0.4.0");
        props.setProperty("revision", "a".repeat(40));
        props.setProperty("dirty", "false");
        props.setProperty("modules", "async-queue,build-metadata");
        props.setProperty("type", "jvm");
        props.setProperty("variant", "jvm-g1-c2");
        props.setProperty("optimization", "c2");
        Map<String, String> env = Map.of(
                "NANOFAAS_BUILD_BASE_IMAGE", "eclipse-temurin:25-jdk",
                "NANOFAAS_RUNTIME_BASE_IMAGE", "gcr.io/distroless/base-debian13:nonroot");
        Map<String, String> systemProps = Map.of(
                "os.arch", "aarch64",
                "os.version", "6.8.0-52-generic",
                "java.version", "25.0.1",
                "java.vm.name", "OpenJDK 64-Bit Server VM");
        return new BuildMetadataProvider(props, env, systemProps, List.of("G1 Young Generation", "G1 Concurrent GC"));
    }

    private static BuildMetadataProvider emptyProvider() {
        return new BuildMetadataProvider(new Properties(), Map.of(), Map.of(), List.of());
    }

    @Test
    void describeReturnsTheProvidersMetadata() {
        BuildMetadataProvider provider = fixedProvider();
        BuildMetadataController controller = new BuildMetadataController(provider);

        assertThat(controller.describe()).isSameAs(provider.get());
    }

    @Test
    void httpResponseExposesExactFieldNamesAndNullableValues() {
        BuildMetadataController controller = new BuildMetadataController(emptyProvider());
        WebTestClient client = WebTestClient.bindToController(controller).build();

        client.get().uri("/modules/build-metadata").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.version").isEqualTo(null)
                .jsonPath("$.revision").isEqualTo(null)
                .jsonPath("$.dirty").isEqualTo(null)
                .jsonPath("$.modules").isEqualTo(null)
                .jsonPath("$.build.type").isEqualTo(null)
                .jsonPath("$.build.variant").isEqualTo(null)
                .jsonPath("$.build.optimization").isEqualTo(null)
                .jsonPath("$.build.baseImages.builder").isEqualTo(null)
                .jsonPath("$.build.baseImages.runtime").isEqualTo(null)
                .jsonPath("$.runtime.architecture").isEqualTo(null)
                .jsonPath("$.runtime.kernelVersion").isEqualTo(null)
                .jsonPath("$.runtime.javaVersion").isEqualTo(null)
                .jsonPath("$.runtime.vm").isEqualTo(null)
                .jsonPath("$.runtime.garbageCollectors").isEqualTo(null);
    }

    @Test
    void httpResponseSerializesRealValues() {
        BuildMetadataController controller = new BuildMetadataController(fixedProvider());
        WebTestClient client = WebTestClient.bindToController(controller).build();

        client.get().uri("/modules/build-metadata").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.version").isEqualTo("0.4.0")
                .jsonPath("$.revision").isEqualTo("a".repeat(40))
                .jsonPath("$.dirty").isEqualTo(false)
                .jsonPath("$.modules[0]").isEqualTo("async-queue")
                .jsonPath("$.modules[1]").isEqualTo("build-metadata")
                .jsonPath("$.build.type").isEqualTo("jvm")
                .jsonPath("$.build.variant").isEqualTo("jvm-g1-c2")
                .jsonPath("$.build.optimization").isEqualTo("c2")
                .jsonPath("$.build.baseImages.builder").isEqualTo("eclipse-temurin:25-jdk")
                .jsonPath("$.build.baseImages.runtime").isEqualTo("gcr.io/distroless/base-debian13:nonroot")
                .jsonPath("$.runtime.architecture").isEqualTo("arm64")
                .jsonPath("$.runtime.kernelVersion").isEqualTo("6.8.0-52-generic")
                .jsonPath("$.runtime.javaVersion").isEqualTo("25.0.1")
                .jsonPath("$.runtime.vm").isEqualTo("OpenJDK 64-Bit Server VM")
                .jsonPath("$.runtime.garbageCollectors[0]").isEqualTo("G1 Concurrent GC")
                .jsonPath("$.runtime.garbageCollectors[1]").isEqualTo("G1 Young Generation");
    }
}
