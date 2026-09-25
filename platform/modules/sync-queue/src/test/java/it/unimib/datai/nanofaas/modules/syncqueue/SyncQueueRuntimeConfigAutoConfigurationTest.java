package it.unimib.datai.nanofaas.modules.syncqueue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.modules.runtimeconfig.RevisionMismatchException;
import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;
import it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigRegistry;
import it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigService;
import it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigSnapshot;
import it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigValidationException;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sync-queue runtime-config bridge ({@link SyncQueueRuntimeConfigAutoConfiguration})
 * and, through it, the runtime-config service contract on the sync-queue namespace.
 *
 * <p>A4: the correlated runtime settings are published as ONE immutable snapshot and
 * updated through {@link RuntimeConfigService}, preserving its revision tracking and
 * rollback.
 */
class SyncQueueRuntimeConfigAutoConfigurationTest {
    @Test
    void acceptsOnlyPositiveIntRetryAfterValues() {
        SyncQueueProperties properties = new SyncQueueProperties(true, true, 100,
                Duration.ofSeconds(1), Duration.ofSeconds(2), 1, Duration.ofMinutes(1), 1);
        MutableSyncQueueConfigSource source = new MutableSyncQueueConfigSource(properties);
        RuntimeConfigExtension extension = new SyncQueueRuntimeConfigAutoConfiguration()
                .syncQueueRuntimeConfigExtension(source);

        assertSoftly(softly -> {
            for (Number value : new Number[]{1.5, 2147483648L}) {
                softly.assertThat(extension.validate(Map.of("retryAfterSeconds", value)))
                        .as("value %s", value)
                        .isNotEmpty();
            }
            softly.assertThat(extension.validate(Map.of("retryAfterSeconds", Integer.MAX_VALUE))).isEmpty();
        });
    }

    @Test
    void validPatchUpdatesSourceAsOneSnapshotAndAdvancesRevision() {
        MutableSyncQueueConfigSource source = source(false, Duration.ofMillis(1500));
        RuntimeConfigService service = service(source);

        RuntimeConfigSnapshot updated =
                service.update(0, "sync-queue", Map.of(
                        MutableSyncQueueConfigSource.KEY_ENABLED, true,
                        MutableSyncQueueConfigSource.KEY_MAX_ESTIMATED_WAIT, Duration.ofMillis(900).toString()));

        assertThat(updated.revision()).isOne();
        assertThat(source.syncQueueEnabled()).isTrue();
        assertThat(source.syncQueueMaxEstimatedWait()).isEqualTo(Duration.ofMillis(900));
        // The rest of the correlated set is untouched, read back from the same snapshot.
        assertThat(source.syncQueueAdmissionEnabled()).isTrue();
        assertThat(source.syncQueueMaxQueueWait()).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void invalidPatchIsRejectedLeavingStateAndRevisionUntouched() {
        MutableSyncQueueConfigSource source = source(false, Duration.ofMillis(1500));
        RuntimeConfigService service = service(source);

        Map<String, Object> patch = Map.of(
                MutableSyncQueueConfigSource.KEY_RETRY_AFTER_SECONDS, 0);
        assertThatThrownBy(() -> service.update(0, "sync-queue", patch))
                .isInstanceOf(RuntimeConfigValidationException.class);

        assertThat(source.syncQueueEnabled()).isFalse();
        assertThat(source.syncQueueAdmissionEnabled()).isTrue();
        assertThat(source.syncQueueMaxEstimatedWait()).isEqualTo(Duration.ofMillis(1500));
        assertThat(service.getSnapshot().revision()).isZero();
    }

    @Test
    void staleRevisionIsRejectedLeavingStateUntouched() {
        MutableSyncQueueConfigSource source = source(false, Duration.ofMillis(1500));
        RuntimeConfigService service = service(source);
        service.update(0, "sync-queue", Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, true));

        Map<String, Object> patch = Map.of(
                MutableSyncQueueConfigSource.KEY_ENABLED, false);
        assertThatThrownBy(() -> service.update(0, "sync-queue", patch))
                .isInstanceOf(RevisionMismatchException.class);
        assertThat(source.syncQueueEnabled()).isTrue();
    }

    @Test
    void failedRestorePublishesNothingLeavingThePreviousSnapshotIntact() {
        MutableSyncQueueConfigSource source = source(true, Duration.ofMillis(1500));

        // A partial/typed map must not tear the published combination: the restore is
        // atomic, so the previous full snapshot remains observable through every getter.
        Map<String, Object> partial = Map.of(
                MutableSyncQueueConfigSource.KEY_ENABLED, true,
                MutableSyncQueueConfigSource.KEY_MAX_QUEUE_WAIT, Duration.ofSeconds(1).toString());
        assertThatThrownBy(() -> source.restore(partial)).isInstanceOf(RuntimeException.class);
        assertThat(source.syncQueueEnabled()).isTrue();
        assertThat(source.syncQueueAdmissionEnabled()).isTrue();
        assertThat(source.syncQueueMaxEstimatedWait()).isEqualTo(Duration.ofMillis(1500));
        assertThat(source.syncQueueMaxQueueWait()).isEqualTo(Duration.ofMillis(1500));
        assertThat(source.syncQueueRetryAfterSeconds()).isEqualTo(2);
    }

    private static MutableSyncQueueConfigSource source(boolean enabled, Duration maxWait) {
        SyncQueueProperties props = new SyncQueueProperties(
                enabled, true, 10, maxWait,
                maxWait, 2, Duration.ofSeconds(30), 1);
        return new MutableSyncQueueConfigSource(props);
    }

    private static RuntimeConfigService service(MutableSyncQueueConfigSource source) {
        RuntimeConfigExtension extension = new SyncQueueRuntimeConfigAutoConfiguration()
                .syncQueueRuntimeConfigExtension(source);
        RuntimeConfigRegistry registry = new RuntimeConfigRegistry(List.of(extension));
        return new RuntimeConfigService(registry, new SimpleMeterRegistry());
    }
}
