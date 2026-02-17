package it.unimib.datai.nanofaas.controlplane.config.runtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Applies a {@link RuntimeConfigSnapshot} to mutable runtime components
 * in a defined, deterministic order. On failure, re-applies the previous snapshot.
 */
@Component
public class RuntimeConfigApplier {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigApplier.class);

    private final RateLimiter rateLimiter;
    private final AtomicLong revisionGauge = new AtomicLong(0);
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Timer applyTimer;

    public RuntimeConfigApplier(RateLimiter rateLimiter, MeterRegistry registry) {
        this.rateLimiter = rateLimiter;
        io.micrometer.core.instrument.Gauge.builder("controlplane_runtime_config_revision", revisionGauge, AtomicLong::get)
                .register(registry);
        this.successCounter = Counter.builder("controlplane_runtime_config_updates_total")
                .tag("status", "success").register(registry);
        this.failureCounter = Counter.builder("controlplane_runtime_config_updates_total")
                .tag("status", "failure").register(registry);
        this.applyTimer = Timer.builder("controlplane_runtime_config_apply_duration_seconds")
                .register(registry);
    }

    /**
     * Applies the new snapshot to all mutable runtime components.
     * Sync queue fields are read directly from {@link RuntimeConfigService} by consumers,
     * so only components with dedicated setters need explicit application here.
     */
    public void apply(RuntimeConfigSnapshot snapshot, RuntimeConfigSnapshot previous, RuntimeConfigService configService) {
        Timer.Sample sample = Timer.start();
        try {
            rateLimiter.setMaxPerSecond(snapshot.rateMaxPerSecond());
            revisionGauge.set(snapshot.revision());
            successCounter.increment();
            sample.stop(applyTimer);
            log.info("event=runtime_config_update change_id={} from_revision={} to_revision={} outcome=success",
                    snapshot.revision(), previous.revision(), snapshot.revision());
        } catch (Exception e) {
            failureCounter.increment();
            sample.stop(applyTimer);
            log.error("event=runtime_config_update from_revision={} to_revision={} outcome=failure",
                    previous.revision(), snapshot.revision(), e);
            configService.restore(previous);
            rateLimiter.setMaxPerSecond(previous.rateMaxPerSecond());
            throw new RuntimeConfigApplyException("Failed to apply runtime config", e);
        }
    }
}
