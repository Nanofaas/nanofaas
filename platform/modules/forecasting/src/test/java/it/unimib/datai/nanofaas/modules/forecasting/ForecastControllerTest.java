package it.unimib.datai.nanofaas.modules.forecasting;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.time.*;
class ForecastControllerTest {
    final OracleForecastStore store = new OracleForecastStore(Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(10));
    final WebTestClient client = WebTestClient.bindToController(new ForecastController(store)).build();
    final String document = """
        {"schemaVersion":1,"nodeId":"edge","revision":1,"provider":"oracle","producedAt":"2026-10-04T12:00:00Z",
         "entries":[{"function":"f","generation":1,"start":"2026-10-04T12:00:00Z","end":"2026-10-04T12:05:00Z","rate":10,"unit":"requests/s"}]}
        """;
    @Test void aotIncludedControllerHonorsRuntimeDisable() {
        var disabled=WebTestClient.bindToController(new ForecastController(store,false)).build();
        disabled.get().uri("/v1/admin/forecasting/trace").exchange().expectStatus().isNotFound();
        disabled.put().uri("/v1/admin/forecasting/trace").header("If-Match","0").contentType(MediaType.APPLICATION_JSON).bodyValue(document).exchange().expectStatus().isNotFound();
        org.assertj.core.api.Assertions.assertThat(store.summary().revision()).isZero();
    }
    @Test void atomicTraceUploadUsesExpectedRevisionAndReportsConflicts() {
        client.put().uri("/v1/admin/forecasting/trace").header("If-Match", "0")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(document).exchange().expectStatus().isOk();
        client.get().uri("/v1/admin/forecasting/trace").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.revision").isEqualTo(1).jsonPath("$.entryCount").isEqualTo(1);
        client.put().uri("/v1/admin/forecasting/trace").header("If-Match", "0")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(document).exchange().expectStatus().isEqualTo(409);
    }
    @Test void missingRequiredFieldsAndUnknownVersionRejectEntireUpload() {
        client.put().uri("/v1/admin/forecasting/trace").header("If-Match", "0")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(document.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
                .exchange().expectStatus().isBadRequest();
        client.put().uri("/v1/admin/forecasting/trace").header("If-Match", "0")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isBadRequest();
        client.get().uri("/v1/admin/forecasting/trace").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.revision").isEqualTo(0);
    }
    @Test void oversizedUploadIsRejectedWithoutReplacingTrace() {
        client.put().uri("/v1/admin/forecasting/trace").header("If-Match", "0")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(" ".repeat(8 * 1024 * 1024 + 1))
                .exchange().expectStatus().isEqualTo(413);
        client.get().uri("/v1/admin/forecasting/trace").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.revision").isEqualTo(0);
    }
}
