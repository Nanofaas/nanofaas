package it.unimib.datai.nanofaas.controlplane.config.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
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
    void getReturnsCurrentSnapshot() {
        webTestClient.get().uri("/v1/admin/runtime-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.revision").isNumber()
                .jsonPath("$.rateMaxPerSecond").isNumber()
                .jsonPath("$.syncQueueEnabled").isBoolean();
    }

    @Test
    void validateAcceptsValidPatch() {
        webTestClient.post().uri("/v1/admin/runtime-config/validate")
                .bodyValue("""
                        {"rateMaxPerSecond": 500}
                        """)
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.valid").isEqualTo(true);
    }

    @Test
    void validateRejectsInvalidPatch() {
        webTestClient.post().uri("/v1/admin/runtime-config/validate")
                .bodyValue("""
                        {"rateMaxPerSecond": -1}
                        """)
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.errors").isArray();
    }

    @Test
    void patchUpdatesConfigAndReturnsNewRevision() {
        long currentRevision = configService.getSnapshot().revision();

        webTestClient.patch().uri("/v1/admin/runtime-config")
                .bodyValue("""
                        {"expectedRevision": %d, "rateMaxPerSecond": 777}
                        """.formatted(currentRevision))
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.revision").isEqualTo(currentRevision + 1)
                .jsonPath("$.effectiveConfig.rateMaxPerSecond").isEqualTo(777);
    }

    @Test
    void patchReturns409OnRevisionMismatch() {
        webTestClient.patch().uri("/v1/admin/runtime-config")
                .bodyValue("""
                        {"expectedRevision": 9999, "rateMaxPerSecond": 500}
                        """)
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").isNotEmpty();
    }

    @Test
    void patchReturns422OnInvalidValues() {
        long currentRevision = configService.getSnapshot().revision();

        webTestClient.patch().uri("/v1/admin/runtime-config")
                .bodyValue("""
                        {"expectedRevision": %d, "rateMaxPerSecond": -1}
                        """.formatted(currentRevision))
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isEqualTo(422);
    }

    @Test
    void patchRequiresExpectedRevision() {
        webTestClient.patch().uri("/v1/admin/runtime-config")
                .bodyValue("""
                        {"rateMaxPerSecond": 500}
                        """)
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void patchWithDurationFields() {
        long currentRevision = configService.getSnapshot().revision();

        webTestClient.patch().uri("/v1/admin/runtime-config")
                .bodyValue("""
                        {"expectedRevision": %d, "syncQueueMaxEstimatedWait": "PT10S", "syncQueueMaxQueueWait": "PT5S"}
                        """.formatted(currentRevision))
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.effectiveConfig.syncQueueMaxEstimatedWait").isEqualTo("PT10S")
                .jsonPath("$.effectiveConfig.syncQueueMaxQueueWait").isEqualTo("PT5S");
    }

    @Test
    void patchWithBooleanFields() {
        long currentRevision = configService.getSnapshot().revision();

        webTestClient.patch().uri("/v1/admin/runtime-config")
                .bodyValue("""
                        {"expectedRevision": %d, "syncQueueEnabled": false, "syncQueueAdmissionEnabled": false}
                        """.formatted(currentRevision))
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.effectiveConfig.syncQueueEnabled").isEqualTo(false)
                .jsonPath("$.effectiveConfig.syncQueueAdmissionEnabled").isEqualTo(false);
    }

    @Test
    void validateWithDurationFields() {
        webTestClient.post().uri("/v1/admin/runtime-config/validate")
                .bodyValue("""
                        {"syncQueueMaxEstimatedWait": "PT10S", "syncQueueRetryAfterSeconds": 5}
                        """)
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.valid").isEqualTo(true);
    }

    @Test
    void getReturnsAllFields() {
        webTestClient.get().uri("/v1/admin/runtime-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.syncQueueMaxEstimatedWait").isNotEmpty()
                .jsonPath("$.syncQueueMaxQueueWait").isNotEmpty()
                .jsonPath("$.syncQueueRetryAfterSeconds").isNumber()
                .jsonPath("$.syncQueueAdmissionEnabled").isBoolean();
    }
}
