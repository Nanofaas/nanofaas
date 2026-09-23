package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerControl;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.execution.StrategyRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Task 13a (issue #208): how the scheduling strategy is SELECTED by configuration.
 *
 * <p>The selection reaches the engine the way the deployment artifacts deliver it — an
 * environment variable named {@code NANOFAAS_SCHEDULER_STRATEGY}, which only becomes
 * {@code nanofaas.scheduler.strategy} because application.yml declares
 * {@code ${NANOFAAS_SCHEDULER_STRATEGY:}}. That is verified and not assumed: with the
 * placeholder in place the environment reads {@code nanofaas.scheduler.strategy=shared-queue}
 * and the engine starts on it, and replacing the placeholder with an empty literal turns three
 * tests in this class red — the engine then starts on the legacy mapping and cannot even refuse
 * the configured id, which is exactly the wiring this class exists to pin.
 *
 * <p>Three properties this class documents by asserting them:
 * <ul>
 *   <li>{@code available} is what THIS artifact was built with. A minimal artifact carrying one
 *       queue module publishes only that module's id and refuses the other one as an unknown id —
 *       a lookup miss in {@link StrategyRegistry}, not a download and not a class load. Both
 *       modules must be selected into the same artifact for both ids to exist, which is why every
 *       HTTP assertion here is gated on the two-module profile.</li>
 *   <li>An id no built-in strategy provides prevents startup. It is not silently replaced by a
 *       default: the operator's typo must be the thing that fails, not the thing that is papered
 *       over.</li>
 *   <li>A committed switch does not survive a restart. The engine reports
 *       {@code persistence: "restart"} because the selection lives in memory: the configured
 *       value wins again on the next boot.</li>
 * </ul>
 *
 * <p>Every test here is independent of the order it runs in: the context is shared, a committed
 * switch is process state, and a test that assumed "the strategy is still the configured one"
 * would be reading the test before it rather than the configuration.
 */
@EnabledIfSystemProperty(named = "nanofaas.selectedControlPlaneModules", matches = ".*\\basync-queue\\b.*")
@EnabledIfSystemProperty(named = "nanofaas.selectedControlPlaneModules", matches = ".*\\bsync-queue\\b.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.registry.path=build/test-scheduler-strategy-config-functions.json",
                "nanofaas.admin.runtime-config.enabled=true",
                // The value the deployment artifacts pass; see the class comment.
                "NANOFAAS_SCHEDULER_STRATEGY=shared-queue"
        })
class SchedulerStrategyConfigurationTest {

    private static final String CONFIGURED = "shared-queue";
    private static final String ASYNC_STRATEGY_CLASS =
            "it.unimib.datai.nanofaas.modules.asyncqueue.PerFunctionSchedulingStrategy";

    @LocalServerPort
    private int port;

    @Test
    void theArtifactPublishesTheIdsItWasBuiltWithAndTheRestartPersistence() {
        WebTestClient client = SchedulerSwitchHttpTest.boundedClient(port);

        client.get().uri("/v1/admin/runtime-config/scheduler")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // Both queue modules are in this artifact, so both ids are published and only
                // these two, in StrategyRegistry's own stable (alphabetical) order.
                .jsonPath("$.available.length()").isEqualTo(2)
                .jsonPath("$.available[0]").isEqualTo("per-function")
                .jsonPath("$.available[1]").isEqualTo("shared-queue")
                .jsonPath("$.persistence").isEqualTo("restart");
    }

    /**
     * A fresh process built from the same sources and the same environment variable starts on the
     * strategy that variable names — the startup half of the contract, asserted on a process that
     * nothing else in this class has touched.
     */
    @Test
    void theEnvironmentVariableSelectsTheStartingStrategy() {
        try (ConfigurableApplicationContext started = start(Map.of(
                "NANOFAAS_SCHEDULER_STRATEGY", CONFIGURED,
                "nanofaas.registry.path", "build/test-scheduler-strategy-start-functions.json"))) {
            SchedulerControl control = started.getBean(SchedulerControl.class);
            assertThat(control.snapshot().strategy()).isEqualTo(CONFIGURED);
            assertThat(control.snapshot().available())
                    .containsExactly("per-function", "shared-queue");
        }
    }

    /**
     * The switch is validated against {@code available}, so an id this artifact does not carry is
     * a 422 — and, because a refused switch never reaches the engine, the active strategy is
     * exactly what it was before.
     */
    @Test
    void anIdNoStrategyProvidesIsRefusedWith422AndLeavesTheSelectionAlone() {
        WebTestClient client = SchedulerSwitchHttpTest.boundedClient(port);
        String before = strategyOf(client);

        client.patch().uri("/v1/admin/runtime-config/scheduler")
                .header("Content-Type", "application/json")
                .bodyValue("{\"expectedRevision\":%d,\"values\":{\"strategy\":\"no-such-strategy\"}}"
                        .formatted(revision(client)))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.errors").isNotEmpty();

        assertThat(strategyOf(client)).isEqualTo(before);
    }

    /**
     * A real switch, committed through the API, followed by a real restart: the restarted process
     * is a second application built from the same configuration, and it starts on the configured
     * strategy again — not on the one the first process had switched to.
     */
    @Test
    void aCommittedSwitchDoesNotSurviveARestart() {
        WebTestClient client = SchedulerSwitchHttpTest.boundedClient(port);

        client.patch().uri("/v1/admin/runtime-config/scheduler")
                .header("Content-Type", "application/json")
                .bodyValue("{\"expectedRevision\":%d,\"values\":{\"strategy\":\"per-function\"}}"
                        .formatted(revision(client)))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.effectiveConfig.namespaces.scheduler.strategy").isEqualTo("per-function");

        assertThat(strategyOf(client)).isEqualTo("per-function");

        try (ConfigurableApplicationContext restarted = start(Map.of(
                "NANOFAAS_SCHEDULER_STRATEGY", CONFIGURED,
                "nanofaas.registry.path", "build/test-scheduler-strategy-restart-functions.json"))) {
            assertThat(restarted.getBean(SchedulerControl.class).snapshot().strategy())
                    .as("a restarted process starts on the configured strategy again")
                    .isEqualTo(CONFIGURED);
        }

        assertThat(strategyOf(client))
                .as("and the restart does not reach back into the process that switched")
                .isEqualTo("per-function");
    }

    /**
     * An unknown id is refused at startup, naming the id and the ids that do exist. The whole
     * cause chain is searched rather than the top-level message: Spring wraps a failing bean
     * creation, and which wrapper surfaces is not this test's business.
     */
    @Test
    void anUnknownStrategyIdPreventsStartup() {
        Throwable failure = catchThrowable(() -> start(Map.of(
                "NANOFAAS_SCHEDULER_STRATEGY", "no-such-strategy",
                "nanofaas.registry.path", "build/test-unknown-scheduler-strategy-functions.json")));

        assertThat(failure)
                .as("startup must not fall back to a default when the configured id is unknown")
                .isNotNull();
        assertThat(messagesOf(failure))
                .as("the refusal must name the id and what the artifact actually carries")
                .contains("unknown scheduling strategy: no-such-strategy, available [per-function, shared-queue]");
    }

    /**
     * The minimal-artifact half of the same property, without a second artifact to build: a
     * registry constructed with one strategy publishes exactly that one id and refuses the other
     * as an unknown id. Nothing is consulted to decide that — it is a map lookup over the
     * strategies the artifact was built with, which is what makes a native image possible at all.
     */
    @Test
    void aMinimalArtifactPublishesOnlyTheIdsItActuallyHas() throws Exception {
        SchedulingStrategy perFunction = Class.forName(ASYNC_STRATEGY_CLASS)
                .asSubclass(SchedulingStrategy.class)
                .getDeclaredConstructor()
                .newInstance();

        StrategyRegistry minimal = new StrategyRegistry(List.of(perFunction));

        assertThat(minimal.ids()).containsExactly("per-function");
        assertThatThrownBy(() -> minimal.require("shared-queue"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown scheduling strategy: shared-queue")
                .hasMessageContaining("available [per-function]");
        assertThat(minimal.require("per-function")).isSameAs(perFunction);
    }

    // ---------------------------------------------------------------------------------------

    /**
     * A second application from the same sources, the way a restart would be: reactive, since
     * this artefact's web beans (the body-limit filter among them) need the codec configurer a
     * non-web context does not have, and on an ephemeral port so it cannot collide with the
     * context under test.
     */
    private static ConfigurableApplicationContext start(Map<String, Object> properties) {
        SpringApplication application = new SpringApplication(ControlPlaneApplication.class);
        application.setWebApplicationType(WebApplicationType.REACTIVE);
        Map<String, Object> withPort = new HashMap<>(properties);
        withPort.put("server.port", "0");
        application.setDefaultProperties(withPort);
        return application.run();
    }

    private static List<String> messagesOf(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.add(current.getMessage());
            }
        }
        return messages;
    }

    private static String strategyOf(WebTestClient client) {
        return SchedulerSwitchHttpTest.field(SchedulerSwitchHttpTest.body(client.get()
                .uri("/v1/admin/runtime-config/scheduler")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()), "strategy");
    }

    private static long revision(WebTestClient client) {
        return SchedulerSwitchHttpTest.numberField(SchedulerSwitchHttpTest.body(client.get()
                .uri("/v1/admin/runtime-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()), "revision");
    }
}
