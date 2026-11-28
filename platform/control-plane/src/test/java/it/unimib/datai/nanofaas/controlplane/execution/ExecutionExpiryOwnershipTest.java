package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Scheduler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ExecutionExpiryOwnershipTest {
    private AnnotationConfigApplicationContext context() {
        return context("nanofaas.execution-store.ttl=80ms",
                "nanofaas.execution-store.max-lifetime=80ms",
                "nanofaas.execution-store.sync-ttl=80ms");
    }

    private AnnotationConfigApplicationContext context(String... properties) {
        var context = new AnnotationConfigApplicationContext();
        // ExecutionStore/IdempotencyStore carry no @Component: ExecutionExpiryConfiguration's
        // @Bean methods construct them from the record it binds out of the environment.
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        context.register(ExecutionExpiryConfiguration.class);
        context.refresh();
        return context;
    }

    @Test
    void expiredOutcomesArePhysicallyRemovedWithoutFurtherStoreActivity() {
        try (var context = context()) {
            var store = context.getBean(ExecutionStore.class);
            var gauge = context.getBean(MeterRegistry.class).get("execution_store_size").gauge();
            var executionRecord = newRecord("completed");
            store.put(executionRecord);
            executionRecord.markSuccess("ok");
            store.settle(executionRecord);
            // Reading the gauge does not run cleanUp(), unlike store.size().
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(gauge.value()).isZero());
        }
    }

    @Test
    void cancelledTimersLeaveNoQueuedTasksAndContextCloseOwnsShutdown() {
        var context = context();
        ScheduledThreadPoolExecutor executor;
        try {
            var scheduler = context.getBean("executionExpiryScheduler", Scheduler.class);
            executor = context.getBean("executionExpiryExecutor", ScheduledThreadPoolExecutor.class);
            for (int i = 0; i < 10_000; i++) {
                scheduler.schedule(Runnable::run, () -> { }, 1, TimeUnit.DAYS).cancel(false);
            }
            assertThat(executor.getQueue()).isEmpty();
            var pending = scheduler.schedule(Runnable::run, () -> { }, 1, TimeUnit.DAYS);
            assertThat(pending.isDone()).isFalse();
        } finally {
            context.close();
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(executor.isTerminated()).isTrue());
        assertThat(executor.getQueue()).isEmpty();
    }

    @Test
    void abandonedExecutionsStillExpireWithoutTraffic() {
        try (var context = context()) {
            var store = context.getBean(ExecutionStore.class);
            var expired = new ConcurrentLinkedQueue<ExecutionRecord>();
            store.onAdministrativeExpiry(expired::add);
            store.put(newRecord("abandoned"));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(expired).hasSize(1));
        }
    }

    /**
     * A keyed admission refused under overload claims its key and abandons it at once. Caffeine
     * then reschedules the key cache's expiry timer, and on the JDK's system scheduler a
     * cancelled timer stays queued, with its CancellationException, until its original deadline
     * (max-lifetime, 30 minutes by default): the P24 retention, which only ExecutionStore was
     * moved off. The key cache must run on the owned scheduler too.
     */
    @Test
    void abandonedKeyClaimsLeaveNoCancelledTimersInTheCommonPool() throws InterruptedException {
        try (var context = context("nanofaas.execution-store.ttl=5m")) {
            var keys = context.getBean(IdempotencyStore.class);
            long before = ForkJoinPool.commonPool().getDelayedTaskCount();
            for (int i = 0; i < 2_000; i++) {
                var claim = keys.acquireOrGet("fn", "refused-" + i);
                keys.abandonClaim("fn", "refused-" + i, claim.executionIdOrToken());
                // Refusals spread over time, as under real overload, give the cache's
                // maintenance the chance to reschedule its timer between them.
                if (i % 100 == 0) Thread.sleep(5);
            }

            await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(ForkJoinPool.commonPool().getDelayedTaskCount() - before).isLessThan(20));
        }
    }

    /**
     * The terminal transition of a keyed execution must tombstone its key with the beans this
     * configuration builds, not only after some other bean's constructor happened to attach the
     * lifecycle to the store as a side effect.
     */
    @Test
    void theConfiguredStoreTombstonesAKeyWhenItsExecutionSettles() {
        try (var context = context("nanofaas.execution-store.ttl=5m")) {
            var store = context.getBean(ExecutionStore.class);
            var keys = context.getBean(IdempotencyStore.class);
            var claim = keys.acquireOrGet("fn", "key");
            var executionRecord = newRecord("keyed", "key");
            store.put(executionRecord);
            keys.publishClaim("fn", "key", claim.executionIdOrToken(), "keyed");
            executionRecord.markSuccess("ok");

            store.settle(executionRecord);

            assertThat(keys.acquireOrGet("fn", "key")).isEqualTo(new IdempotencyStore.AcquireResult(
                    IdempotencyStore.AcquireResult.State.EXISTING, "keyed", true));
        }
    }

    private ExecutionRecord newRecord(String id) {
        return newRecord(id, null);
    }

    private ExecutionRecord newRecord(String id, String idempotencyKey) {
        var spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null, 1000, 1, 10, 0,
                null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        var task = new InvocationTask(id, "fn", spec, new InvocationRequest("payload", Map.of()),
                idempotencyKey, null, Instant.now(), 1, InvocationKind.SYNC);
        return ExecutionFixtures.record(id, task);
    }
}
