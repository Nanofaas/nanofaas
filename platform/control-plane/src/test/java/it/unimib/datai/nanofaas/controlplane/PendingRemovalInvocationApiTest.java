package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.PartialDeprovisionException;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pending-removal 409 as a client actually sees it, over HTTP.
 *
 * <p>{@code openapi/core.yaml} documents a {@code 409 FUNCTION_REMOVAL_PENDING} on
 * {@code :invoke} and {@code :enqueue}, and ADR §8.3 makes it part of the contract. The unit
 * coverage stops one layer short of that claim: it asserts the exception out of
 * {@code FunctionService.get} and the response out of the advice method called directly, but
 * nothing drove the two verbs through the chain that has to carry the exception from the lookup to
 * the response — and the sync path in particular raises it inside a {@code Mono.fromCallable}, so
 * whether it survives to the advice is a property of the reactive chain, not of the advice.
 *
 * <p>Here the whole path is real: a DEPLOYMENT function is registered, its backend reports a
 * partial deprovision on the DELETE, and the two verbs are then called over the wire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.rate.maxPerSecond=1000",
                "nanofaas.defaults.timeoutMs=2000",
                "nanofaas.defaults.concurrency=2",
                "nanofaas.defaults.queueSize=10",
                "nanofaas.defaults.maxRetries=3",
                "nanofaas.registry.path=build/test-pending-removal-api-functions.json",
                // The k8s provider module is on the test classpath too; name the backend this
                // test owns so the resolution is unambiguous.
                "nanofaas.deployment.default-backend=test-partial",
                "sync-queue.enabled=false",
                "nanofaas.admin.runtime-config.enabled=false"
        })
@AutoConfigureWebTestClient
class PendingRemovalInvocationApiTest {

    private static final String FUNCTION = "partially-removed";
    private static final List<String> LEFTOVERS = List.of("nanofaas-partially-removed-r2");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private FunctionService functionService;

    @Autowired
    private PartialDeprovisioningProvider provider;

    @BeforeEach
    void registerAndPartiallyDelete() {
        provider.failDeprovision.set(false);
        functionService.remove(FUNCTION);
        functionService.register(deploymentSpec());

        provider.failDeprovision.set(true);
        // The DELETE that leaves the function in pending removal, over HTTP like everything else.
        webTestClient.delete()
                .uri("/v1/functions/" + FUNCTION)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").isEqualTo("FUNCTION_REMOVAL_PENDING");
    }

    @AfterEach
    void finishTheCleanup() {
        // The retry that ends the pending state, so the next test starts from a free name and the
        // persisted catalog is not left holding a half-removed function.
        provider.failDeprovision.set(false);
        functionService.remove(FUNCTION);
        assertThat(functionService.get(FUNCTION)).isEmpty();
    }

    @Test
    void invokeOnAFunctionInPendingRemovalReturns409OverHttp() {
        webTestClient.post()
                .uri("/v1/functions/" + FUNCTION + ":invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").isEqualTo("FUNCTION_REMOVAL_PENDING")
                // The body names what is left: that is what the operator has to act on.
                .jsonPath("$.message").value(String.class,
                        message -> assertThat(message)
                                .contains(FUNCTION)
                                .contains("nanofaas-partially-removed-r2")
                                .contains("retry DELETE /v1/functions/" + FUNCTION));
    }

    @Test
    void enqueueOnAFunctionInPendingRemovalReturns409OverHttp() {
        // 409 and not the 501 of a missing async-queue module: the refusal is decided by the
        // lookup, before the enqueuer is ever consulted.
        webTestClient.post()
                .uri("/v1/functions/" + FUNCTION + ":enqueue")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").isEqualTo("FUNCTION_REMOVAL_PENDING")
                .jsonPath("$.message").value(String.class,
                        message -> assertThat(message)
                                .contains(FUNCTION)
                                .contains("nanofaas-partially-removed-r2"));
    }

    private static FunctionSpec deploymentSpec() {
        return new FunctionSpec(FUNCTION, "img:latest", null, Map.of(), null, 1000, 1, 10, 3, null,
                ExecutionMode.DEPLOYMENT, null, null, null);
    }

    @TestConfiguration
    static class Backend {
        @Bean
        PartialDeprovisioningProvider partialDeprovisioningProvider() {
            return new PartialDeprovisioningProvider();
        }
    }

    /**
     * A managed backend whose deprovision can be made to report a partial outcome — the only way
     * to reach pending removal, since that state is written by exactly one code path.
     */
    static class PartialDeprovisioningProvider implements ManagedDeploymentProvider {

        final AtomicBoolean failDeprovision = new AtomicBoolean();

        @Override
        public String backendId() {
            return "test-partial";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean supports(FunctionSpec spec) {
            return true;
        }

        @Override
        public ProvisionResult provision(FunctionSpec spec) {
            return new ProvisionResult("http://" + spec.name() + ":8080/invoke", backendId(),
                    ExecutionMode.DEPLOYMENT, null, Map.of());
        }

        @Override
        public ProvisionResult reconcile(FunctionSpec spec, int desiredReplicas,
                                         Map<String, String> deploymentObjects) {
            return provision(spec);
        }

        @Override
        public void deprovision(String functionName) {
            if (failDeprovision.get()) {
                throw new PartialDeprovisionException(functionName, backendId(), LEFTOVERS,
                        List.of(new IllegalStateException("container runtime unavailable")));
            }
        }

        @Override
        public void setReplicas(String functionName, int replicas) {
            // Nothing to scale: this backend owns no real resource.
        }

        @Override
        public int getReadyReplicas(String functionName) {
            return 1;
        }
    }
}
