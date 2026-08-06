package it.unimib.datai.nanofaas.modules.runtimeconfig;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.config.SyncQueueRuntimeDefaults;
import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        RuntimeConfigApplier failingApplier = new RuntimeConfigApplier(rateLimiter, new SimpleMeterRegistry(), null) {
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
                new RuntimeConfigApplier(rateLimiter, new SimpleMeterRegistry(), null)
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
                new RuntimeConfigApplier(rateLimiter, new SimpleMeterRegistry(), null)
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

    @Test
    void concurrentPatchesSerializeApplyAndRollback() throws Exception {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);
        RuntimeConfigService configService = new RuntimeConfigService(rateLimiter, DEFAULT_SYNC_QUEUE_DEFAULTS);
        CountDownLatch firstApplyEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstApply = new CountDownLatch(1);
        AtomicInteger applyCalls = new AtomicInteger();

        RuntimeConfigApplier applier = new RuntimeConfigApplier(rateLimiter, new SimpleMeterRegistry(), null) {
            @Override
            public void apply(RuntimeConfigSnapshot snapshot, RuntimeConfigSnapshot previous,
                              RuntimeConfigService service) {
                if (applyCalls.incrementAndGet() == 1) {
                    firstApplyEntered.countDown();
                    try {
                        if (!releaseFirstApply.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to release first apply");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    service.restore(previous);
                    rateLimiter.setMaxPerSecond(previous.rateMaxPerSecond());
                    throw new RuntimeConfigApplyException("forced apply failure", new RuntimeException("boom"));
                }
                super.apply(snapshot, previous, service);
            }
        };
        AdminRuntimeConfigController controller = new AdminRuntimeConfigController(
                configService, new RuntimeConfigValidator(), applier);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ResponseEntity<?>> first = executor.submit(() -> controller.patch(
                    patchRequest(0L, 777)));
            assertThat(firstApplyEntered.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<ResponseEntity<?>> second = executor.submit(() -> {
                secondStarted.countDown();
                return controller.patch(patchRequest(0L, 888));
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirstApply.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(second.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(configService.getSnapshot().revision()).isEqualTo(1L);
            assertThat(configService.getSnapshot().rateMaxPerSecond()).isEqualTo(888);
            assertThat(rateLimiter.getMaxPerSecond()).isEqualTo(888);
        } finally {
            releaseFirstApply.countDown();
            executor.shutdownNow();
        }
    }

    private AdminRuntimeConfigController.PatchRequest patchRequest(long revision, int rate) {
        return new AdminRuntimeConfigController.PatchRequest(
                revision, rate, null, null, null, null, null);
    }

    private AdminRuntimeConfigController controller() {
        RateLimiter rateLimiter = new RateLimiter();
        rateLimiter.setMaxPerSecond(1000);
        RuntimeConfigService configService = new RuntimeConfigService(rateLimiter, DEFAULT_SYNC_QUEUE_DEFAULTS);
        return new AdminRuntimeConfigController(
                configService,
                new RuntimeConfigValidator(),
                new RuntimeConfigApplier(rateLimiter, new SimpleMeterRegistry(), null)
        );
    }
}
