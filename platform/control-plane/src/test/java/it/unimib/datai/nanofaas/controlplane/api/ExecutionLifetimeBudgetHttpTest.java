package it.unimib.datai.nanofaas.controlplane.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * An execution is evicted {@code max-lifetime} after admission, whatever its function's timeout
 * says (see {@code MaxLifetimeOverridesTheAttemptTimeoutTest} in execution-runtime). A function
 * whose attempts cannot all finish inside that lifetime is therefore a promise the platform cannot
 * keep: every long run ends as {@code EXECUTION_EXPIRED}. Registration and PATCH must refuse it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.execution-store.max-lifetime=2s",
                "nanofaas.registry.path=build/test-execution-lifetime-budget-functions.json",
                "sync-queue.enabled=false"
        })
@AutoConfigureWebTestClient(timeout = "5s")
class ExecutionLifetimeBudgetHttpTest {

    /** The registry file outlives the test JVM: names must not collide with an earlier run's. */
    private static final String RUN = Long.toString(System.nanoTime(), 36);

    @Autowired
    private WebTestClient webClient;

    @Test
    void registrationRefusesATimeoutLongerThanTheExecutionLifetime() {
        register("too-long-single-attempt", 5_000, 0)
                .expectStatus().isBadRequest();
    }

    @Test
    void registrationCountsEveryRetryAttemptAgainstTheExecutionLifetime() {
        // 4 attempts x 600 ms = 2.4 s, past the 2 s lifetime: the last attempt could never run.
        register("too-long-with-retries", 600, 3)
                .expectStatus().isBadRequest();
    }

    @Test
    void registrationAcceptsAttemptsThatFitTheExecutionLifetime() {
        register("fits-the-lifetime", 400, 3)
                .expectStatus().isCreated();
    }

    @Test
    void patchRefusesATimeoutThatNoLongerFitsTheExecutionLifetime() {
        register("patched-too-long", 400, 0).expectStatus().isCreated();

        webClient.patch()
                .uri("/v1/functions/patched-too-long-" + RUN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("timeoutMs", 5_000))
                .exchange()
                .expectStatus().isBadRequest();
    }

    private WebTestClient.ResponseSpec register(String name, int timeoutMs, int maxRetries) {
        return webClient.post()
                .uri("/v1/functions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", name + "-" + RUN,
                        "image", "unused",
                        "executionMode", "EXTERNAL",
                        "endpointUrl", "http://127.0.0.1:9/invoke",
                        "timeoutMs", timeoutMs,
                        "maxRetries", maxRetries))
                .exchange();
    }
}
