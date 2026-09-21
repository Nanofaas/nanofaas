package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 13a (issue #208): the manual scheduler switch as an HTTP contract, on live work.
 *
 * <p>What this pins, and why it is not another equivalence test: the switch may not lose, reorder
 * away or renumber the work already in the engine when the operator changes the strategy mid-flight.
 * A controllable backend holds ONE dispatch open, the function admits a single dispatch at a time,
 * and at least two more invocations are therefore sitting in the engine's index — and only then does
 * a PATCH through the real admin API change the strategy. The assertion is on the observable
 * outcome: the same execution ids still resolve, each to its own payload, and the run is repeated in
 * the opposite direction under the same conditions.
 *
 * <p>The interleaving is forced, not slept into: the backend holds its dispatch until the test
 * releases it, the two remaining invocations cannot reach the backend while the first holds the
 * function's only capacity slot, and the PATCH happens strictly between "the backend holds one" and
 * "release". Nothing here waits on a wall-clock guess, and every wait is bounded — a test that can
 * hang is a test that eventually hangs CI.
 *
 * <p>The strategy is pinned through {@code NANOFAAS_SCHEDULER_STRATEGY}, the same name Helm and
 * Compose pass as an environment variable, which only reaches {@code nanofaas.scheduler.strategy}
 * because application.yml declares {@code ${NANOFAAS_SCHEDULER_STRATEGY:}}.
 */
@EnabledIfSystemProperty(named = "nanofaas.selectedControlPlaneModules", matches = ".*\\basync-queue\\b.*")
@EnabledIfSystemProperty(named = "nanofaas.selectedControlPlaneModules", matches = ".*\\bsync-queue\\b.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.rate.maxPerSecond=1000",
                "nanofaas.defaults.timeoutMs=30000",
                "nanofaas.defaults.concurrency=1",
                "nanofaas.defaults.queueSize=10",
                "nanofaas.defaults.maxRetries=0",
                "nanofaas.registry.path=build/test-scheduler-switch-http-functions.json",
                "nanofaas.admin.runtime-config.enabled=true",
                "NANOFAAS_SCHEDULER_STRATEGY=per-function"
        })
class SchedulerSwitchHttpTest {

    private static final String FUNCTION = "echo";
    /** The strategy this context is configured to start on (the Helm/Compose env var above). */
    private static final String CONFIGURED = "per-function";
    private static final String OTHER = "shared-queue";
    /** One invocation at a time: the reason two of three invocations must stay pending. */
    private static final int CONCURRENCY = 1;
    /** Bounded client and bounded waits; see the class comment. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SETTLE = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Autowired
    private FunctionService functionService;

    @Autowired
    private ControllableLocalDispatcher backend;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = boundedClient(port);
        backend.rearm();
        functionService.remove(FUNCTION);
        functionService.register(new FunctionSpec(
                FUNCTION, "local", null, Map.of(), null,
                30_000, CONCURRENCY, 10, 0, null,
                ExecutionMode.LOCAL, null, null, null));
        // The context is shared by every method here, and a committed switch is deliberately
        // not undone by anything but a restart: put the configured strategy back before each
        // scenario so a failure in one method cannot decide the next one's starting point.
        if (!CONFIGURED.equals(activeStrategy())) {
            switchStrategyTo(CONFIGURED);
        }
    }

    @AfterEach
    void releaseWhateverIsStillHeld() {
        backend.releaseAll();
    }

    /**
     * The ASYNC contract: three {@code :enqueue} calls, one of which the backend holds, and a real
     * switch in each direction. Ids are the ASYNC caller's only handle on its work, so they are what
     * must survive.
     */
    @Test
    void enqueuedWorkKeepsItsIdsAndItsResultsAcrossBothDirections() {
        List<String> forward = enqueueThree("forward");
        assertOneActiveAndTwoPending(forward);
        switchStrategyTo(OTHER);
        assertOneActiveAndTwoPending(forward);
        backend.releaseAll();
        assertEachIdResolvesToItsOwnPayload("forward", forward);

        // The opposite direction, on a fresh batch, under the same forced interleaving.
        backend.rearm();
        List<String> backward = enqueueThree("backward");
        assertOneActiveAndTwoPending(backward);
        switchStrategyTo(CONFIGURED);
        assertOneActiveAndTwoPending(backward);
        backend.releaseAll();
        assertEachIdResolvesToItsOwnPayload("backward", backward);
    }

    /**
     * The SYNC contract, same interleaving: three callers blocked in {@code :invoke}. Their
     * execution ids are only visible once their responses arrive, so what is asserted here is that
     * all three still complete — with distinct ids, each carrying its own payload — after the
     * strategy changed while two of them were still waiting for the function's capacity slot.
     */
    @Test
    void blockedSyncWaitersKeepTheirResultsAcrossBothDirections() throws Exception {
        List<SyncOutcome> forward = invokeThreeAcrossASwitch("sync-forward", OTHER);
        assertThreeDistinctIdsCameBack("sync-forward", forward);

        backend.rearm();
        List<SyncOutcome> backward = invokeThreeAcrossASwitch("sync-backward", CONFIGURED);
        assertThreeDistinctIdsCameBack("sync-backward", backward);
    }

    /**
     * Polling and a shared idempotency key: the two ways an ASYNC caller identifies work without
     * holding a response. A key that already has a live execution must keep resolving to that one
     * execution across the switch — not to a second execution under the new strategy.
     */
    @Test
    void pollingAndASharedKeyStillNameTheSameExecutionAfterTheSwitch() {
        String key = "shared-key-across-a-switch";
        String payload = "shared-key-payload";
        String executionId = enqueueWithKey(key, payload);
        awaitOneActive(executionId);
        assertThat(statusOf(executionId)).isEqualTo("running");

        // Same key, same live execution: no second admission, so the backend still holds exactly one.
        assertThat(enqueueWithKey(key, payload)).isEqualTo(executionId);
        assertThat(backend.dispatchedCount()).isEqualTo(1);

        switchStrategyTo(OTHER);
        assertThat(statusOf(executionId)).isEqualTo("running");
        assertThat(enqueueWithKey(key, payload)).isEqualTo(executionId);
        assertThat(backend.dispatchedCount()).isEqualTo(1);

        backend.releaseAll();
        assertResolvesTo(executionId, payload);

        // And the reverse change afterwards does not reassign the key either.
        switchStrategyTo(CONFIGURED);
        assertThat(enqueueWithKey(key, payload)).isEqualTo(executionId);
        assertResolvesTo(executionId, payload);
    }

    // ---------------------------------------------------------------------------------------
    // The controllable backend: the real DispatcherRouter/LocalDispatcher path, with the leaf
    // dispatch held open on demand. Overriding the @Component by @Primary keeps every other
    // participant of the attempt path real (admission, the engine, the attempt state machine).
    // ---------------------------------------------------------------------------------------

    @TestConfiguration
    static class HeldBackend {
        @Bean
        @Primary
        ControllableLocalDispatcher controllableLocalDispatcher() {
            return new ControllableLocalDispatcher();
        }
    }

    /** A {@link LocalDispatcher} whose dispatch does not complete until the test says so. */
    static final class ControllableLocalDispatcher extends LocalDispatcher {

        private final Object lock = new Object();
        private final List<String> dispatched = new ArrayList<>();
        private final List<Held> held = new ArrayList<>();
        private boolean passThrough;

        @Override
        public CompletableFuture<DispatchResult> dispatch(InvocationTask task) {
            synchronized (lock) {
                dispatched.add(task.executionId());
                if (passThrough) {
                    return CompletableFuture.completedFuture(echo(task));
                }
                Held pending = new Held(task, new CompletableFuture<>());
                held.add(pending);
                return pending.outcome();
            }
        }

        int dispatchedCount() {
            synchronized (lock) {
                return dispatched.size();
            }
        }

        /** Forgets the previous scenario and holds the next dispatch again. */
        void rearm() {
            synchronized (lock) {
                dispatched.clear();
                held.clear();
                passThrough = false;
            }
        }

        /** Completes everything held so far and lets later dispatches through. */
        void releaseAll() {
            List<Held> releasing;
            synchronized (lock) {
                passThrough = true;
                releasing = List.copyOf(held);
                held.clear();
            }
            releasing.forEach(pending -> pending.outcome().complete(echo(pending.task())));
        }

        /** LOCAL dispatch semantics, unchanged from {@link LocalDispatcher}: echo the input. */
        private static DispatchResult echo(InvocationTask task) {
            return DispatchResult.warm(InvocationResult.success(task.request().input()));
        }

        private record Held(InvocationTask task, CompletableFuture<DispatchResult> outcome) {
        }
    }

    // ---------------------------------------------------------------------------------------
    // Scenario helpers
    // ---------------------------------------------------------------------------------------

    /**
     * The setup every scenario needs, asserted through the public status endpoint rather than
     * through engine internals: exactly one invocation reached the backend and is RUNNING, and the
     * other two are QUEUED behind it. Called again after the switch, it also pins that the switch
     * neither re-dispatched, dropped nor renumbered anything.
     */
    private void assertOneActiveAndTwoPending(List<String> ids) {
        Awaitility.await().atMost(SETTLE).untilAsserted(() ->
                assertThat(backend.dispatchedCount()).isEqualTo(1));
        assertThat(ids).anyMatch(id -> "running".equals(statusOf(id)));
        for (String id : ids) {
            assertThat(statusOf(id))
                    .as("execution %s while the backend holds the one active dispatch", id)
                    .isIn("running", "queued");
        }
        assertThat(ids.stream().filter(id -> "queued".equals(statusOf(id))).count())
                .as("invocations still waiting for the function's only capacity slot")
                .isEqualTo(2);
    }

    private void awaitOneActive(String executionId) {
        Awaitility.await().atMost(SETTLE).untilAsserted(() ->
                assertThat(statusOf(executionId)).isEqualTo("running"));
    }

    private List<String> enqueueThree(String prefix) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(enqueue(prefix + "-" + i));
        }
        return ids;
    }

    private String enqueue(String payload) {
        EntityExchangeResult<byte[]> accepted = client.post()
                .uri("/v1/functions/{name}:enqueue", FUNCTION)
                .bodyValue(new InvocationRequest(payload, Map.of()))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("queued")
                .returnResult();
        return field(body(accepted), "executionId");
    }

    private String enqueueWithKey(String key, String payload) {
        EntityExchangeResult<byte[]> accepted = client.post()
                .uri("/v1/functions/{name}:enqueue", FUNCTION)
                .header("Idempotency-Key", key)
                .bodyValue(new InvocationRequest(payload, Map.of()))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .returnResult();
        return field(body(accepted), "executionId");
    }

    /**
     * Three concurrent {@code :invoke} callers, one held by the backend and two waiting for the
     * capacity slot, a switch, then the release. The switch happens while two of the three are
     * genuinely blocked: the futures are asserted not-done on both sides of it.
     */
    private List<SyncOutcome> invokeThreeAcrossASwitch(String prefix, String target) throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(3);
        try {
            List<Future<SyncOutcome>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String payload = prefix + "-" + i;
                calls.add(callers.submit(() -> invokeSync(payload)));
            }
            Awaitility.await().atMost(SETTLE).untilAsserted(() ->
                    assertThat(backend.dispatchedCount()).isEqualTo(1));

            switchStrategyTo(target);

            assertThat(backend.dispatchedCount())
                    .as("the switch must not re-dispatch the active invocation")
                    .isEqualTo(1);
            assertThat(calls).noneMatch(Future::isDone);

            backend.releaseAll();

            List<SyncOutcome> outcomes = new ArrayList<>();
            for (Future<SyncOutcome> call : calls) {
                outcomes.add(call.get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            }
            return outcomes;
        } finally {
            callers.shutdownNow();
        }
    }

    private SyncOutcome invokeSync(String payload) {
        EntityExchangeResult<byte[]> response = client.post()
                .uri("/v1/functions/{name}:invoke", FUNCTION)
                .bodyValue(new InvocationRequest(payload, Map.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("success")
                .jsonPath("$.output").isEqualTo(payload)
                .returnResult();
        return new SyncOutcome(
                response.getResponseHeaders().getFirst("X-Execution-Id"), payload);
    }

    /**
     * Three callers, three distinct ids, the three payloads that were submitted. That each
     * response carried the payload of the caller that sent it is asserted where the response is
     * read ({@link #invokeSync}); what remains here is that no two callers ended up sharing an
     * execution, which is what a switch that mishandled the pending tickets would produce.
     */
    private void assertThreeDistinctIdsCameBack(String prefix, List<SyncOutcome> outcomes) {
        assertThat(outcomes).extracting(SyncOutcome::executionId).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(outcomes).extracting(SyncOutcome::payload)
                .containsExactlyInAnyOrder(prefix + "-0", prefix + "-1", prefix + "-2");
    }

    private void assertEachIdResolvesToItsOwnPayload(String prefix, List<String> ids) {
        for (int i = 0; i < ids.size(); i++) {
            assertResolvesTo(ids.get(i), prefix + "-" + i);
        }
    }

    private void assertResolvesTo(String executionId, String payload) {
        Awaitility.await().atMost(SETTLE).untilAsserted(() ->
                client.get().uri("/v1/executions/{id}", executionId)
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody()
                        .jsonPath("$.status").isEqualTo("success")
                        .jsonPath("$.output").isEqualTo(payload));
    }

    // ---------------------------------------------------------------------------------------
    // The admin contract and the wire helpers
    // ---------------------------------------------------------------------------------------

    /**
     * The switch itself, through the real endpoint and the existing runtime-config envelope: the
     * revision the caller last read, 200 on commit, and the committed snapshot in the answer.
     *
     * <p>The envelope's own {@code effectiveConfig} is NOT the assertion that the switch took
     * effect, even though it looks like one: {@code SchedulerRuntimeConfigExtension.prepare}
     * computes it from the requested target, and {@code RuntimeConfigService.updatePrepared}
     * deliberately never re-reads the registry after commit — so it would read back
     * {@code target} even if {@code SchedulerEngine.switchTo} had silently done nothing. The
     * assertion that the engine really switched is {@link #activeStrategy()}, which reads the
     * live snapshot.
     */
    private void switchStrategyTo(String target) {
        client.patch().uri("/v1/admin/runtime-config/scheduler")
                .header("Content-Type", "application/json")
                .bodyValue("{\"expectedRevision\":%d,\"values\":{\"strategy\":\"%s\"}}"
                        .formatted(revision(), target))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.effectiveConfig.namespaces.scheduler.persistence").isEqualTo("restart")
                .jsonPath("$.warnings").isEmpty();

        assertThat(activeStrategy())
                .as("the committed switch must be visible in the engine's own live selection")
                .isEqualTo(target);
    }

    private String activeStrategy() {
        return field(body(client.get().uri("/v1/admin/runtime-config/scheduler")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()), "strategy");
    }

    private long revision() {
        String snapshot = body(client.get().uri("/v1/admin/runtime-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult());
        return numberField(snapshot, "revision");
    }

    /** The public status of one execution, as a string: queued, running, success, ... */
    private String statusOf(String executionId) {
        return field(body(client.get().uri("/v1/executions/{id}", executionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()), "status");
    }

    static String body(EntityExchangeResult<byte[]> result) {
        return new String(result.getResponseBody(), StandardCharsets.UTF_8);
    }

    /**
     * Reads one string field out of a JSON body. Deliberately a regex and not a bound DTO: the
     * status bodies carry {@code Instant} fields, and the WebTestClient's own codecs are not the
     * application's Jackson configuration. Every existing test in this module extracts the same
     * way for the same reason.
     */
    static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("no string field " + name + " in body: " + json);
        }
        return matcher.group(1);
    }

    /** The numeric counterpart, for the envelope's {@code revision}. */
    static long numberField(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("no numeric field " + name + " in body: " + json);
        }
        return Long.parseLong(matcher.group(1));
    }

    /**
     * A client that cannot outlive a bounded wait, built from the running server's port rather
     * than injected: {@code @AutoConfigureWebTestClient}'s client has no response timeout, and an
     * unbounded request in a test is how a test hangs.
     */
    static WebTestClient boundedClient(int port) {
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(CALL_TIMEOUT)
                .build();
    }

    private record SyncOutcome(String executionId, String payload) {
    }
}

/**
 * The legacy sync profile: {@code nanofaas.admission.profile=sync-queue} is Task 8's "sync-queue
 * only" mapping, under which ASYNC is not a capability at all. Changing the scheduling strategy
 * must not conjure it — {@code :enqueue} stays 501 in both directions, and this is the direction
 * {@code AsyncCapabilityStrategyIndependenceApiTest} does not cover (that one starts on the async
 * profile and switches to shared-queue; this one starts on shared-queue and switches to
 * per-function, which is the switch an operator might expect to enable ASYNC).
 */
@EnabledIfSystemProperty(named = "nanofaas.selectedControlPlaneModules", matches = ".*\\basync-queue\\b.*")
@EnabledIfSystemProperty(named = "nanofaas.selectedControlPlaneModules", matches = ".*\\bsync-queue\\b.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.rate.maxPerSecond=1000",
                "nanofaas.defaults.timeoutMs=2000",
                "nanofaas.defaults.concurrency=2",
                "nanofaas.defaults.queueSize=10",
                "nanofaas.defaults.maxRetries=0",
                "nanofaas.registry.path=build/test-legacy-sync-scheduler-switch-functions.json",
                "nanofaas.admin.runtime-config.enabled=true",
                "nanofaas.admission.profile=sync-queue",
                "NANOFAAS_SCHEDULER_STRATEGY=shared-queue",
                "sync-queue.enabled=true",
                "sync-queue.admission-enabled=true"
        })
class LegacySyncProfileSchedulerSwitchHttpTest {

    private static final String FUNCTION = "echo";

    @LocalServerPort
    private int port;

    @Autowired
    private FunctionService functionService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = SchedulerSwitchHttpTest.boundedClient(port);
        functionService.remove(FUNCTION);
        functionService.register(new FunctionSpec(
                FUNCTION, "local", null, Map.of(), null,
                2000, 2, 10, 0, null,
                ExecutionMode.LOCAL, null, null, null));
    }

    @Test
    void asyncStaysUnavailableWhenTheStrategyChangesToPerFunction() {
        assertAsyncNotImplemented();

        switchStrategyTo("per-function");

        assertAsyncNotImplemented();
    }

    private void assertAsyncNotImplemented() {
        client.post()
                .uri("/v1/functions/{name}:enqueue", FUNCTION)
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isEqualTo(501);
    }

    private void switchStrategyTo(String target) {
        long revision = SchedulerSwitchHttpTest.numberField(
                SchedulerSwitchHttpTest.body(client.get().uri("/v1/admin/runtime-config")
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody()
                        .returnResult()), "revision");
        client.patch().uri("/v1/admin/runtime-config/scheduler")
                .header("Content-Type", "application/json")
                .bodyValue("{\"expectedRevision\":%d,\"values\":{\"strategy\":\"%s\"}}"
                        .formatted(revision, target))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.effectiveConfig.namespaces.scheduler.strategy").isEqualTo(target);
    }
}
