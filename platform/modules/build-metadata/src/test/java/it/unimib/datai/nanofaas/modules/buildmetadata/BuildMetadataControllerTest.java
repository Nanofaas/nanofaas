package it.unimib.datai.nanofaas.modules.buildmetadata;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class BuildMetadataControllerTest {

    private static final BuildMetadata FIXED = new BuildMetadata(
            "0.4.0",
            "a".repeat(40),
            false,
            List.of("async-queue", "build-metadata"),
            new BuildMetadata.Build("jvm", "jvm-g1-c2", "c2",
                    new BuildMetadata.BaseImages("eclipse-temurin:25-jdk", "gcr.io/distroless/base-debian13:nonroot")),
            new BuildMetadata.Runtime("arm64", "6.8.0-52-generic", "25.0.1",
                    "OpenJDK 64-Bit Server VM", List.of("G1 Concurrent GC", "G1 Young Generation")));

    @Test
    void describeReturnsTheProvidersMetadata() {
        BuildMetadataController controller = new BuildMetadataController(() -> FIXED);

        assertThat(controller.describe()).isSameAs(FIXED);
    }

    @Test
    void httpResponseExposesExactFieldNamesAndNullableValues() {
        BuildMetadata sparse = new BuildMetadata(null, null, null, null,
                new BuildMetadata.Build(null, null, null, new BuildMetadata.BaseImages(null, null)),
                new BuildMetadata.Runtime(null, null, null, null, null));
        BuildMetadataController controller = new BuildMetadataController(() -> sparse);
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
        BuildMetadataController controller = new BuildMetadataController(() -> FIXED);
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
