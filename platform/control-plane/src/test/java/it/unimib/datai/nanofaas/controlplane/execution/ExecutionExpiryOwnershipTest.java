package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Scheduler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreBindingProperties;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ExecutionExpiryOwnershipTest {
    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        // ExecutionStore/IdempotencyStore no longer carry @Component (Task 9, issue #208): they
        // are constructed explicitly by ExecutionExpiryConfiguration's @Bean methods, from the
        // ExecutionStoreBindingProperties Spring binds and converts to the runtime record.
        context.registerBean(ExecutionStoreBindingProperties.class, () -> {
            var binding = new ExecutionStoreBindingProperties();
            binding.setTtl(Duration.ofMillis(80));
            binding.setMaxLifetime(Duration.ofMillis(80));
            binding.setSyncTtl(Duration.ofMillis(80));
            return binding;
        });
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
            var record = record("completed");
            store.put(record);
            record.markSuccess("ok");
            store.settle(record);
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
            store.put(record("abandoned"));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(expired).hasSize(1));
        }
    }

    private ExecutionRecord record(String id) {
        var spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null, 1000, 1, 10, 0,
                null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        var task = new InvocationTask(id, "fn", spec, new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1, InvocationKind.SYNC);
        return new ExecutionRecord(id, task);
    }
}
