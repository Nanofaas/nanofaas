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
            true, true, Duration.ofSeconds(2), Duration.ofSeconds(2), 2
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

    @Test
    void validateReturns400ForMalformedDuration() {
        AdminRuntimeConfigController controller = controller();

        ResponseEntity<?> response = controller.validate(new AdminRuntimeConfigController.PatchRequest(
                null,
                null,
                null,
                null,
                "5s",
                null,
                null
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void patchReturns400ForMalformedDuration() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);
        RuntimeConfigService configService = new RuntimeConfigService(rateLimiter, DEFAULT_SYNC_QUEUE_DEFAULTS);
        AdminRuntimeConfigController controller = new AdminRuntimeConfigController(
                configService,
                new RuntimeConfigValidator(),
                new RuntimeConfigApplier(rateLimiter, new SimpleMeterRegistry())
        );
        RuntimeConfigSnapshot before = configService.getSnapshot();

        ResponseEntity<?> response = controller.patch(new AdminRuntimeConfigController.PatchRequest(
                before.revision(),
                null,
                null,
                null,
                "5s",
                null,
                null
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(configService.getSnapshot().revision()).isEqualTo(before.revision());
    }

    @Test
    void patchReturns422WhenPartialUpdateCreatesInvalidSyncQueueConfig() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);
        RuntimeConfigService configService = new RuntimeConfigService(rateLimiter, DEFAULT_SYNC_QUEUE_DEFAULTS);
        AdminRuntimeConfigController controller = new AdminRuntimeConfigController(
                configService,
                new RuntimeConfigValidator(),
                new RuntimeConfigApplier(rateLimiter, new SimpleMeterRegistry())
        );
        RuntimeConfigSnapshot before = configService.getSnapshot();

        ResponseEntity<?> response = controller.patch(new AdminRuntimeConfigController.PatchRequest(
                before.revision(),
                null,
                null,
                null,
                "PT5S",
                null,
                null
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(configService.getSnapshot().revision()).isEqualTo(before.revision());
    }

    private AdminRuntimeConfigController controller() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);
        RuntimeConfigService configService = new RuntimeConfigService(rateLimiter, DEFAULT_SYNC_QUEUE_DEFAULTS);
        return new AdminRuntimeConfigController(
                configService,
                new RuntimeConfigValidator(),
                new RuntimeConfigApplier(rateLimiter, new SimpleMeterRegistry())
        );
    }
}
