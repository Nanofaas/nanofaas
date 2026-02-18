package it.unimib.datai.nanofaas.modules.runtimeconfig;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AdminRuntimeConfigControllerTest {

    private static final SyncQueueRuntimeDefaults DEFAULT_SYNC_QUEUE_DEFAULTS = new SyncQueueRuntimeDefaults(
            true, true, Duration.ofSeconds(5), Duration.ofSeconds(2), 2
    );

    @Test
    void patchReturns503WhenApplyFailsAndSnapshotIsRolledBack() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);
        RuntimeConfigService configService = new RuntimeConfigService(rateLimiter, DEFAULT_SYNC_QUEUE_DEFAULTS);
        RuntimeConfigValidator validator = new RuntimeConfigValidator();

        RuntimeConfigApplier failingApplier = new RuntimeConfigApplier(rateLimiter, new SimpleMeterRegistry()) {
            @Override
            public void apply(RuntimeConfigSnapshot snapshot, RuntimeConfigSnapshot previous, RuntimeConfigService service) {
                service.restore(previous);
                rateLimiter.setMaxPerSecond(previous.rateMaxPerSecond());
                throw new RuntimeConfigApplyException("forced apply failure", new RuntimeException("boom"));
            }
        };
        AdminRuntimeConfigController controller = new AdminRuntimeConfigController(configService, validator, failingApplier);

        RuntimeConfigSnapshot before = configService.getSnapshot();
        ResponseEntity<?> response = controller.patch(new AdminRuntimeConfigController.PatchRequest(
                before.revision(),
                777,
                null,
                null,
                null,
                null,
                null
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        RuntimeConfigSnapshot after = configService.getSnapshot();
        assertThat(after.revision()).isEqualTo(before.revision());
        assertThat(after.rateMaxPerSecond()).isEqualTo(before.rateMaxPerSecond());
    }
}
