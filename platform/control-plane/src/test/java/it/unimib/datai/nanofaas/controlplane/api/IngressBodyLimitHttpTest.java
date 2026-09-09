package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.http.codecs.max-in-memory-size=64B",
                "nanofaas.rate.maxPerSecond=1000000",
                "nanofaas.registry.path=build/test-ingress-body-limit-functions.json",
                "sync-queue.enabled=false"
        })
@AutoConfigureWebTestClient(timeout = "3s")
class IngressBodyLimitHttpTest {

    private static final int BODY_LIMIT = 64;

    @Autowired
    private WebTestClient webClient;

    @Autowired
    private FunctionService functionService;

    @Autowired
    private RateLimiter rateLimiter;

    @Test
    void declaredContentLengthAboveTheLimitReturns413BeforeControllerParsing() {
        String body = invocationJsonWithLength(BODY_LIMIT + 1);

        webClient.post()
                .uri("/v1/functions/missing:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(413);

        assertThat(functionService.get("missing")).isEmpty();
    }

    @Test
    void boundedIngressPrecedesRateAndReplayShortcuts() {
        rateLimiter.setMaxPerSecond(0);
        try {
            webClient.post()
                    .uri("/v1/functions/missing:invoke")
                    .header("Idempotency-Key", "existing-or-new-is-irrelevant")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(invocationJsonWithLength(BODY_LIMIT + 1))
                    .exchange()
                    .expectStatus().isEqualTo(413);
        } finally {
            rateLimiter.setMaxPerSecond(1_000_000);
        }
    }

    @Test
    void chunkedBodyCrossingTheLimitReturns413AndTheConnectionRemainsUsable() {
        Flux<DataBuffer> body = Flux.concat(
                        Mono.just(buffer("{\"input\":\"" + "x".repeat(50))),
                        Mono.just(buffer("xxxxx")),
                        Mono.<DataBuffer>never());

        webClient.post()
                .uri("/v1/functions/missing:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromDataBuffers(body))
                .exchange()
                .expectStatus().isEqualTo(413);

        webClient.post()
                .uri("/v1/functions/missing:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(invocationJsonWithLength(BODY_LIMIT))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void bodyExactlyAtTheLimitReachesNormalRouting() {
        String body = invocationJsonWithLength(BODY_LIMIT);

        webClient.post()
                .uri("/v1/functions/missing:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void repeatedOversizedBodiesLeaveNoIngressStateThatRejectsTheNextBoundedBody() {
        String oversized = invocationJsonWithLength(BODY_LIMIT + 1);
        for (int request = 0; request < 32; request++) {
            webClient.post()
                    .uri("/v1/functions/missing:enqueue")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(oversized)
                    .exchange()
                    .expectStatus().isEqualTo(413);
        }

        webClient.post()
                .uri("/v1/functions/missing:enqueue")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(invocationJsonWithLength(BODY_LIMIT))
                .exchange()
                .expectStatus().isNotFound();
    }

    private static DataBuffer buffer(String value) {
        return DefaultDataBufferFactory.sharedInstance.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String invocationJsonWithLength(int byteLength) {
        String envelope = "{\"input\":\"\",\"metadata\":{}}";
        String body = envelope.replace("\"\",", "\"" + "x".repeat(byteLength - envelope.length()) + "\",");
        assertThat(body.getBytes(StandardCharsets.UTF_8)).hasSize(byteLength);
        return body;
    }
}
