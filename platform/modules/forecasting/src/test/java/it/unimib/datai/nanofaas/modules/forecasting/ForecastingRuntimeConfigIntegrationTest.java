package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.forecastingapi.ForecastQuery;
import it.unimib.datai.nanofaas.forecastingapi.ForecastSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The provider is chosen through the runtime-config module's admin API, as the scheduler is. */
@SpringBootTest(classes = ControlPlaneApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.admin.runtime-config.enabled=true",
                "nanofaas.forecasting.enabled=true",
                "nanofaas.forecasting.node-id=edge",
                "nanofaas.forecasting.window=10s",
                "nanofaas.forecasting.max-age=1m",
                "sync-queue.enabled=false",
                "spring.autoconfigure.exclude=it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration,it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration,it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueRuntimeConfigAutoConfiguration"
        })
@AutoConfigureWebTestClient
class ForecastingRuntimeConfigIntegrationTest {
    @Autowired WebTestClient web;
    @Autowired ForecastSource source;

    @Test
    void providerAndParametersSwitchThroughTheAdminApi() {
        var now = Instant.now();
        var query = new ForecastQuery("edge", "f", 1, now, now.plusSeconds(60));
        assertThat(source.forecast(query).provider()).isEqualTo("ewma");
        web.get().uri("/v1/admin/runtime-config/forecasting").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.provider").isEqualTo("ewma")
                .jsonPath("$.available").isArray()
                .jsonPath("$.persistence").isEqualTo("restart");
        long revision = revision();

        web.patch().uri("/v1/admin/runtime-config/forecasting")
                .bodyValue(Map.of("expectedRevision", revision, "values", Map.of("provider", "arima")))
                .exchange().expectStatus().isEqualTo(422);
        web.patch().uri("/v1/admin/runtime-config/forecasting")
                .bodyValue(Map.of("expectedRevision", revision, "values", Map.of("provider", "ari", "ariOrder", 3)))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.effectiveConfig.namespaces.forecasting.ariOrder").isEqualTo(3);
        assertThat(source.forecast(query).provider()).isEqualTo("ari");

        web.patch().uri("/v1/admin/runtime-config/forecasting")
                .bodyValue(Map.of("expectedRevision", revision, "values", Map.of("provider", "holt")))
                .exchange().expectStatus().isEqualTo(409);
        web.patch().uri("/v1/admin/runtime-config/forecasting")
                .bodyValue(Map.of("expectedRevision", revision + 1, "values", Map.of("provider", "holt", "holtTrend", 0.2)))
                .exchange().expectStatus().isOk();
        assertThat(source.forecast(query).provider()).isEqualTo("holt");
        web.get().uri("/v1/admin/runtime-config/forecasting").exchange()
                .expectBody().jsonPath("$.holtTrend").isEqualTo(0.2);
    }

    private long revision() {
        var body = web.get().uri("/v1/admin/runtime-config").exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return ((Number) body.get("revision")).longValue();
    }
}
