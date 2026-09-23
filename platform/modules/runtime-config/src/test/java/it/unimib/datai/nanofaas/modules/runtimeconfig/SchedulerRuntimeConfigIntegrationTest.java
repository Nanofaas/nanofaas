package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerControl;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerSelection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level coverage for the {@code scheduler} runtime-config namespace (task 7): the admin
 * PATCH must not answer before {@link SchedulerControl#switchTo(String)} has committed, and every
 * status the brief lists must be reachable through the real controller/service/extension wiring,
 * not just through {@code RuntimeConfigServiceTest} unit doubles.
 */
class SchedulerRuntimeConfigIntegrationTest {

    /** A {@link SchedulerControl} whose {@code switchTo} can be held on a latch, or made to fail
     * before activation, so tests can pin ordering and pre-commit failure mapping precisely. */
    static final class FakeSchedulerControl implements SchedulerControl {
        private final AtomicReference<String> strategy = new AtomicReference<>("per-function");
        private final List<String> available = List.of("per-function", "shared-queue");
        private volatile CountDownLatch entered;
        private volatile CountDownLatch gate;
        private volatile RuntimeException failure;

        @Override
        public SchedulerSelection snapshot() {
            return new SchedulerSelection(strategy.get(), available);
        }

        @Override
        public void switchTo(String target) {
            CountDownLatch enteredLatch = entered;
            if (enteredLatch != null) {
                enteredLatch.countDown();
            }
            CountDownLatch gateLatch = gate;
            if (gateLatch != null) {
                awaitUninterruptibly(gateLatch);
            }
            RuntimeException toThrow = failure;
            if (toThrow != null) {
                throw toThrow;
            }
            strategy.set(target);
        }

        void holdNextSwitchOn(CountDownLatch enteredLatch, CountDownLatch gateLatch) {
            this.entered = enteredLatch;
            this.gate = gateLatch;
        }

        void failNextSwitchWith(RuntimeException exception) {
            this.failure = exception;
        }

        /**
         * The Spring context (and therefore this bean) is cached and shared across every
         * {@code @Test} method in {@link WithSchedulerControl}, so a latch/failure/strategy left
         * behind by one test would otherwise leak into the next regardless of declaration order.
         */
        void reset() {
            this.entered = null;
            this.gate = null;
            this.failure = null;
            this.strategy.set("per-function");
        }

        private static void awaitUninterruptibly(CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("gate latch was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    @TestConfiguration
    static class FakeSchedulerControlConfig {
        // Single bean of the concrete type: it's also a SchedulerControl (the interface every
        // injection point in production code, e.g. @ConditionalOnBean/schedulerRuntimeConfigExtension,
        // asks for), and declaring a second bean of the interface type would just be the same
        // instance exposed twice, tripping NoUniqueBeanDefinitionException on any SchedulerControl
        // injection point.
        @Bean
        FakeSchedulerControl fakeSchedulerControl() {
            return new FakeSchedulerControl();
        }
    }

    @Nested
    @SpringBootTest(classes = ControlPlaneApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "nanofaas.admin.runtime-config.enabled=true",
                    "sync-queue.enabled=false",
                    // The default 5s client-side response timeout is shorter than the latch
                    // windows this class deliberately holds the PATCH open for.
                    "spring.test.webtestclient.timeout=20s",
                    // Task 8 (issue #208): SchedulerConfiguration now publishes a real
                    // SchedulerControl (the composed engine) whenever a queue module is on the
                    // classpath, and SyncQueueConfiguration's EngineSyncQueueGateway bean now
                    // requires the engine unconditionally (by design: this module always
                    // contributes a SchedulingStrategy, so in production SchedulerConfiguration
                    // is always present too). Excluding both queue modules' auto-configurations
                    // keeps FakeSchedulerControl (imported below) the only SchedulerControl;
                    // SyncQueueRuntimeConfigAutoConfiguration (the sync-queue admin namespace,
                    // unrelated to what this class tests) is excluded alongside it rather than
                    // supplying its MutableSyncQueueConfigSource dependency by hand.
                    "spring.autoconfigure.exclude=it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration,it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration,it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueRuntimeConfigAutoConfiguration"
            })
    @AutoConfigureWebTestClient
    @Import(FakeSchedulerControlConfig.class)
    class WithSchedulerControl {

        @Autowired
        private WebTestClient webTestClient;

        @Autowired
        private RuntimeConfigService configService;

        @Autowired
        private FakeSchedulerControl schedulerControl;

        @Autowired
        @Qualifier("adminRuntimeConfigExecutor")
        private ThreadPoolExecutor adminExecutor;

        private ExecutorService testExecutor;

        @BeforeEach
        void resetFakeSchedulerControl() {
            schedulerControl.reset();
        }

        @AfterEach
        void shutdownTestExecutor() {
            if (testExecutor != null) {
                testExecutor.shutdownNow();
            }
        }

        @Test
        void exposesSchedulerNamespaceAndSwitchesStrategy() {
            webTestClient.get().uri("/v1/admin/runtime-config/scheduler")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.strategy").isEqualTo("per-function")
                    .jsonPath("$.available").isArray()
                    .jsonPath("$.persistence").isEqualTo("restart");

            long revision = configService.getSnapshot().revision();
            webTestClient.patch().uri("/v1/admin/runtime-config/scheduler")
                    .bodyValue("{\"expectedRevision\":%d,\"values\":{\"strategy\":\"shared-queue\"}}".formatted(revision))
                    .header("Content-Type", "application/json")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.revision").isEqualTo(revision + 1)
                    .jsonPath("$.effectiveConfig.namespaces.scheduler.strategy").isEqualTo("shared-queue");
        }

        @Test
        void rejectsIncompleteRequestWith400() {
            webTestClient.patch().uri("/v1/admin/runtime-config/scheduler")
                    .bodyValue("{\"values\":{\"strategy\":\"shared-queue\"}}")
                    .header("Content-Type", "application/json")
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        @Test
        void rejectsStaleRevisionWith409() {
            long revision = configService.getSnapshot().revision();
            webTestClient.patch().uri("/v1/admin/runtime-config/scheduler")
                    .bodyValue("{\"expectedRevision\":%d,\"values\":{\"strategy\":\"shared-queue\"}}".formatted(revision + 41))
                    .header("Content-Type", "application/json")
                    .exchange()
                    .expectStatus().isEqualTo(409);
            assertThat(schedulerControl.snapshot().strategy()).isEqualTo("per-function");
        }

        @Test
        void rejectsUnknownStrategyWith422() {
            long revision = configService.getSnapshot().revision();
            webTestClient.patch().uri("/v1/admin/runtime-config/scheduler")
                    .bodyValue("{\"expectedRevision\":%d,\"values\":{\"strategy\":\"round-robin\"}}".formatted(revision))
                    .header("Content-Type", "application/json")
                    .exchange()
                    .expectStatus().isEqualTo(422);
            assertThat(schedulerControl.snapshot().strategy()).isEqualTo("per-function");
        }

        @Test
        void refusalBeforeCommitReturns503WithNoCommit() {
            long revision = configService.getSnapshot().revision();
            schedulerControl.failNextSwitchWith(new IllegalStateException("cannot rebuild index"));

            webTestClient.patch().uri("/v1/admin/runtime-config/scheduler")
                    .bodyValue("{\"expectedRevision\":%d,\"values\":{\"strategy\":\"shared-queue\"}}".formatted(revision))
                    .header("Content-Type", "application/json")
                    .exchange()
                    .expectStatus().isEqualTo(503);

            // No commit happened: strategy AND revision are exactly what they were before.
            assertThat(schedulerControl.snapshot().strategy()).isEqualTo("per-function");
            assertThat(configService.getSnapshot().revision()).isEqualTo(revision);
        }

        /**
         * Pins that the PATCH response cannot arrive before {@code switchTo} activates: the fake
         * control counts down {@code entered} the instant it starts running (proving the request
         * was admitted to the bounded admin executor) and then blocks on {@code gate}. The test
         * thread waits on {@code entered} (a latch, not a sleep) and then asserts the HTTP future
         * is NOT done — which is only safe to assert because {@code gate} is still held, so
         * {@code switchTo} (and therefore the commit) cannot have returned yet. Only after that
         * assertion does the test release {@code gate} and wait for the real response.
         */
        @Test
        void patchDoesNotCompleteBeforeActivation() throws Exception {
            testExecutor = Executors.newSingleThreadExecutor();
            long revision = configService.getSnapshot().revision();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            schedulerControl.holdNextSwitchOn(entered, gate);

            Future<Integer> responseStatus = testExecutor.submit(() ->
                    webTestClient.patch().uri("/v1/admin/runtime-config/scheduler")
                            .bodyValue("{\"expectedRevision\":%d,\"values\":{\"strategy\":\"shared-queue\"}}".formatted(revision))
                            .header("Content-Type", "application/json")
                            .exchange()
                            .expectStatus().isOk()
                            .expectBody()
                            .jsonPath("$.revision").isEqualTo(revision + 1)
                            .returnResult().getStatus().value());

            assertThat(entered.await(10, TimeUnit.SECONDS))
                    .as("switchTo must have been invoked (admitted to the admin executor) by now")
                    .isTrue();
            assertThat(responseStatus.isDone())
                    .as("the PATCH response must not arrive while switchTo/commit is still blocked on the gate")
                    .isFalse();
            assertThat(schedulerControl.snapshot().strategy())
                    .as("activation has not happened yet, so the pre-switch strategy must still be reported")
                    .isEqualTo("per-function");

            gate.countDown();

            assertThat(responseStatus.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(schedulerControl.snapshot().strategy()).isEqualTo("shared-queue");
        }

        /**
         * Saturates the real {@code adminRuntimeConfigExecutor} bean directly (1 worker, 1 queue
         * slot) rather than racing two concurrent HTTP calls against it: a successful
         * {@code execute()} return is itself proof of admission (it throws synchronously
         * otherwise), so there is no window where "was it queued yet?" is ambiguous. Only then is
         * a real PATCH sent, and it must be rejected before the service — and therefore
         * {@code switchTo} — is ever invoked.
         */
        @Test
        void rejectsWhenAdminExecutorIsSaturatedWith503() throws Exception {
            long revision = configService.getSnapshot().revision();
            CountDownLatch workerEntered = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            CountDownLatch releaseQueued = new CountDownLatch(1);

            adminExecutor.execute(() -> {
                workerEntered.countDown();
                awaitUninterruptibly(releaseWorker);
            });
            assertThat(workerEntered.await(10, TimeUnit.SECONDS)).isTrue();
            // The sole worker is occupied above; this fills the one queue slot. A successful
            // return here (no RejectedExecutionException) is itself proof it was admitted.
            adminExecutor.execute(() -> awaitUninterruptibly(releaseQueued));

            webTestClient.patch().uri("/v1/admin/runtime-config/scheduler")
                    .bodyValue("{\"expectedRevision\":%d,\"values\":{\"strategy\":\"shared-queue\"}}".formatted(revision))
                    .header("Content-Type", "application/json")
                    .exchange()
                    .expectStatus().isEqualTo(503);
            // Confirms the strategy never moved: the rejected admission never reached the service.
            assertThat(schedulerControl.snapshot().strategy()).isEqualTo("per-function");

            releaseWorker.countDown();
            releaseQueued.countDown();
        }

        private static void awaitUninterruptibly(CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("latch was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * No {@link SchedulerControl} bean at all in this context (the engine that composes one is
     * task 8's job): the {@code scheduler} namespace must not exist, exactly like a genuinely
     * unknown namespace, rather than 500ing or exposing a stale/default snapshot.
     */
    @Nested
    @SpringBootTest(classes = ControlPlaneApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "nanofaas.admin.runtime-config.enabled=true",
                    "sync-queue.enabled=false",
                    // Task 8 (issue #208): SchedulerConfiguration now publishes a real
                    // SchedulerControl (the composed engine) whenever any queue module is on
                    // the classpath, which every other test in this file runs with. This nested
                    // class exists specifically to cover the "no SchedulerControl at all" case
                    // the extension's own @ConditionalOnBean handles — excluded explicitly here
                    // so that case stays reachable regardless of -PcontrolPlaneModules.
                    "spring.autoconfigure.exclude=it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration,it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration,it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueRuntimeConfigAutoConfiguration"
            })
    @AutoConfigureWebTestClient
    class WithoutSchedulerControl {

        @Autowired
        private WebTestClient webTestClient;

        @Test
        void schedulerNamespaceIsAbsentWith404() {
            webTestClient.get().uri("/v1/admin/runtime-config/scheduler")
                    .exchange()
                    .expectStatus().isNotFound();

            // This context is cached and may be shared with other test classes using the same
            // Spring Boot configuration, so the current revision is not necessarily 0 here.
            long revision = webTestClient.get().uri("/v1/admin/runtime-config")
                    .exchange().expectBody(RuntimeConfigSnapshot.class).returnResult().getResponseBody()
                    .revision();
            webTestClient.patch().uri("/v1/admin/runtime-config/scheduler")
                    .bodyValue("{\"expectedRevision\":%d,\"values\":{\"strategy\":\"shared-queue\"}}".formatted(revision))
                    .header("Content-Type", "application/json")
                    .exchange()
                    .expectStatus().isNotFound();
        }
    }
}
