package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.rate.maxPerSecond=1000",
                "nanofaas.defaults.timeoutMs=2000",
                "nanofaas.defaults.concurrency=2",
                "nanofaas.defaults.queueSize=10",
                "nanofaas.defaults.maxRetries=3",
                "sync-queue.enabled=false",
                "nanofaas.admin.runtime-config.enabled=false"
        })
@AutoConfigureWebTestClient
class CoreOnlyApiTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private FunctionService functionService;

    @Autowired
    private InvocationEnqueuer invocationEnqueuer;

    @BeforeEach
    void setup() {
        functionService.register(new FunctionSpec(
                "echo",
                "local",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                3,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        ));
    }

    @Test
    void coreProfileInjectsDisabledNoOpInvocationEnqueuer() {
        Assumptions.assumeFalse(invocationEnqueuer.enabled());
        assertThat(invocationEnqueuer).isSameAs(InvocationEnqueuer.noOp());
        assertThat(invocationEnqueuer.enabled()).isFalse();
        assertThatThrownBy(() -> invocationEnqueuer.enqueue(null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Async queue module not loaded");
    }

    @Test
    void asyncEnqueueReturns501WhenAsyncQueueModuleIsNotLoaded() {
        Assumptions.assumeFalse(invocationEnqueuer.enabled());
        webTestClient.post()
                .uri("/v1/functions/echo:enqueue")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isEqualTo(501);
    }
}
