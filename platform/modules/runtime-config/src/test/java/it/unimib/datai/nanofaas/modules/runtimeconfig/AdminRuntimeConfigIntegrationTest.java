package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(classes = ControlPlaneApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.admin.runtime-config.enabled=true",
                "sync-queue.enabled=false"
        })
@AutoConfigureWebTestClient
class AdminRuntimeConfigIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RuntimeConfigService configService;

    @Test
    void exposesNamespacedSnapshotAndPatch() {
        webTestClient.get().uri("/v1/admin/runtime-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.revision").isNumber()
                .jsonPath("$.namespaces.control-plane.rateMaxPerSecond").isNumber();

        long revision = configService.getSnapshot().revision();
        webTestClient.patch().uri("/v1/admin/runtime-config/control-plane")
                .bodyValue("{\"expectedRevision\":%d,\"values\":{\"rateMaxPerSecond\":777}}".formatted(revision))
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.revision").isEqualTo(revision + 1)
                .jsonPath("$.effectiveConfig.namespaces.control-plane.rateMaxPerSecond").isEqualTo(777);
    }

    @Test
    void validatesAndRejectsUnknownNamespace() {
        webTestClient.post().uri("/v1/admin/runtime-config/control-plane/validate")
                .bodyValue("{\"rateMaxPerSecond\":0}")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.errors").isArray();

        webTestClient.get().uri("/v1/admin/runtime-config/missing")
                .exchange()
                .expectStatus().isNotFound();
    }
}
