package it.unimib.datai.nanofaas.modules.runtimeconfig;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigApplierTest {

    private static final SyncQueueRuntimeDefaults DEFAULT_SYNC_QUEUE_DEFAULTS = new SyncQueueRuntimeDefaults(
            true, true, Duration.ofSeconds(5), Duration.ofSeconds(2), 2
    );

    @Test
    void applySuccess_updatesRateLimiterAndMetrics() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);
        MeterRegistry registry = new SimpleMeterRegistry();
        RuntimeConfigApplier applier = new RuntimeConfigApplier(rateLimiter, registry);
        RuntimeConfigService configService = new RuntimeConfigService(rateLimiter, DEFAULT_SYNC_QUEUE_DEFAULTS);

        RuntimeConfigSnapshot previous = configService.getSnapshot();
        RuntimeConfigSnapshot updated = configService.update(0, new RuntimeConfigPatch(500, null, null, null, null, null));

        applier.apply(updated, previous, configService);

        assertEquals(500, rateLimiter.getMaxPerSecond());
        assertEquals(1.0, registry.get("controlplane_runtime_config_updates_total")
                .tag("status", "success").counter().count());
        assertEquals(1, (long) registry.get("controlplane_runtime_config_revision").gauge().value());
    }

    @Test
    void applyFailure_rollsBackAndThrows() {
        // Use a RateLimiter subclass that throws on specific value
        RateLimiter rateLimiter = new RateLimiter() {
            @Override
            public void setMaxPerSecond(int maxPerSecond) {
                if (maxPerSecond == 999) {
                    throw new RuntimeException("simulated failure");
                }
                super.setMaxPerSecond(maxPerSecond);
            }
        };
        rateLimiter.setMaxPerSecond(1000);
        MeterRegistry registry = new SimpleMeterRegistry();
        RuntimeConfigApplier applier = new RuntimeConfigApplier(rateLimiter, registry);
        RuntimeConfigService configService = new RuntimeConfigService(rateLimiter, DEFAULT_SYNC_QUEUE_DEFAULTS);

        RuntimeConfigSnapshot previous = configService.getSnapshot();
        RuntimeConfigSnapshot bad = configService.update(0, new RuntimeConfigPatch(999, null, null, null, null, null));

        RuntimeConfigApplyException ex = assertThrows(
                RuntimeConfigApplyException.class,
                () -> applier.apply(bad, previous, configService)
        );
        assertTrue(ex.getMessage().contains("Failed to apply"));
        assertNotNull(ex.getCause());

        // Verify rollback: rate limiter restored to previous value
        assertEquals(1000, rateLimiter.getMaxPerSecond());
        // Verify rollback: config service snapshot restored
        assertEquals(0, configService.getSnapshot().revision());
        // Verify failure metric incremented
        assertEquals(1.0, registry.get("controlplane_runtime_config_updates_total")
                .tag("status", "failure").counter().count());
    }
}
