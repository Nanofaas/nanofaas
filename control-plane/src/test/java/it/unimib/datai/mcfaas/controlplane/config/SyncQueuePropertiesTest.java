package it.unimib.datai.mcfaas.controlplane.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncQueuePropertiesTest {
    @Test
    void bindsConfiguredValues() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("sync-queue.enabled", "true")
                .withProperty("sync-queue.admission-enabled", "true")
                .withProperty("sync-queue.max-depth", "200")
                .withProperty("sync-queue.max-estimated-wait", "2s")
                .withProperty("sync-queue.max-queue-wait", "2s")
                .withProperty("sync-queue.retry-after-seconds", "2")
                .withProperty("sync-queue.throughput-window", "30s")
                .withProperty("sync-queue.per-function-min-samples", "50");

        SyncQueueProperties props = Binder.get(env)
                .bind("sync-queue", Bindable.of(SyncQueueProperties.class))
                .get();

        assertTrue(props.enabled());
        assertTrue(props.admissionEnabled());
        assertEquals(200, props.maxDepth());
        assertEquals(Duration.ofSeconds(2), props.maxEstimatedWait());
        assertEquals(Duration.ofSeconds(2), props.maxQueueWait());
        assertEquals(2, props.retryAfterSeconds());
        assertEquals(Duration.ofSeconds(30), props.throughputWindow());
        assertEquals(50, props.perFunctionMinSamples());
    }
}
