package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class RuntimeConfigServiceTest {

    // Hoisted out of the assertThatThrownBy lambdas: a lambda that also builds its
    // argument has two calls that can throw, and the assertion cannot say which one did.
    private static final Map<String, Object> NEW_VALUE = Map.of("value", 2);
    private static final Map<String, Object> UNKNOWN_KEY = Map.of("invalid", true);

    @Test
    void updatesOneNamespaceAndIncrementsRevision() {
        TestExtension extension = new TestExtension("queue", 1);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RuntimeConfigService service = service(extension, meterRegistry);

        assertThat(meterRegistry.get("controlplane_runtime_config_revision").gauge().value()).isZero();

        RuntimeConfigSnapshot updated = service.update(0, "queue", Map.of("value", 2));

        assertThat(updated.revision()).isOne();
        assertThat(updated.namespaces().get("queue")).containsEntry("value", 2);
        assertThat(meterRegistry.get("controlplane_runtime_config_revision").gauge().value()).isEqualTo(1);
        assertThat(meterRegistry.get("controlplane_runtime_config_apply_duration_seconds").timer().count()).isOne();
        assertThat(meterRegistry.get("controlplane_runtime_config_updates_total")
                .tags("status", "success", "namespace", "queue").counter().count()).isEqualTo(1);
    }

    @Test
    void staleAndUnknownUpdatesHaveNoSideEffects() {
        TestExtension extension = new TestExtension("queue", 1);
        RuntimeConfigService service = service(extension);

        assertThatThrownBy(() -> service.update(1, "queue", NEW_VALUE))
                .isInstanceOf(RevisionMismatchException.class);
        assertThatThrownBy(() -> service.update(0, "missing", NEW_VALUE))
                .isInstanceOf(UnknownRuntimeConfigNamespaceException.class);
        assertThat(extension.value).isEqualTo(1);
        assertThat(service.getSnapshot().revision()).isZero();
    }

    @Test
    void failedApplyRestoresPreviousStateAndRevision() {
        TestExtension extension = new TestExtension("queue", 1);
        extension.fail = true;
        RuntimeConfigService service = service(extension);

        assertThatThrownBy(() -> service.update(0, "queue", NEW_VALUE))
                .isInstanceOf(RuntimeConfigApplyException.class);
        assertThat(extension.value).isEqualTo(1);
        assertThat(service.getSnapshot().revision()).isZero();
    }

    @Test
    void failedSnapshotRestoresStateWithoutAdvancingRevision() {
        TestExtension extension = new TestExtension("queue", 1);
        extension.failSnapshotAfterApply = true;
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RuntimeConfigService service = service(extension, meterRegistry);

        assertThatThrownBy(() -> service.update(0, "queue", NEW_VALUE))
                .isInstanceOf(RuntimeConfigApplyException.class);
        assertThat(extension.value).isEqualTo(1);
        assertThat(service.getSnapshot().revision()).isZero();
        assertThat(meterRegistry.get("controlplane_runtime_config_revision").gauge().value()).isZero();
        assertThat(meterRegistry.get("controlplane_runtime_config_apply_duration_seconds").timer().count()).isOne();
        assertThat(meterRegistry.get("controlplane_runtime_config_updates_total")
                .tags("status", "failure", "namespace", "queue").counter().count()).isEqualTo(1);
    }

    @Test
    void failedSuccessMetricRestoresStateWithoutAdvancingRevision() {
        TestExtension extension = new TestExtension("queue", 1);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        meterRegistry.config().meterFilter(new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if ("success".equals(id.getTag("status"))) throw new IllegalStateException("metrics boom");
                return MeterFilterReply.NEUTRAL;
            }
        });
        RuntimeConfigService service = new RuntimeConfigService(
                new RuntimeConfigRegistry(List.of(extension)), meterRegistry);

        assertThatThrownBy(() -> service.update(0, "queue", NEW_VALUE))
                .isInstanceOf(RuntimeConfigApplyException.class);
        assertThat(extension.value).isEqualTo(1);
        assertThat(service.getSnapshot().revision()).isZero();
    }

    @Test
    void failedFailureMetricDoesNotMaskRuntimeConfigApplyException() {
        TestExtension extension = new TestExtension("queue", 1);
        extension.fail = true;
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        meterRegistry.config().meterFilter(new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if ("failure".equals(id.getTag("status"))) throw new IllegalStateException("failure metrics boom");
                return MeterFilterReply.NEUTRAL;
            }
        });
        RuntimeConfigService service = new RuntimeConfigService(
                new RuntimeConfigRegistry(List.of(extension)), meterRegistry);

        Throwable thrown = catchThrowable(() -> service.update(0, "queue", Map.of("value", 2)));

        assertThat(thrown).isInstanceOf(RuntimeConfigApplyException.class);
        assertThat(thrown.getCause()).hasMessage("boom");
        assertThat(thrown.getCause().getSuppressed())
                .singleElement()
                .satisfies(failure -> assertThat(failure).hasMessage("failure metrics boom"));
        assertThat(extension.value).isEqualTo(1);
        assertThat(service.getSnapshot().revision()).isZero();
    }

    @Test
    void rejectedUpdatesAreNotTimed() {
        TestExtension extension = new TestExtension("queue", 1);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RuntimeConfigService service = service(extension, meterRegistry);

        assertThatThrownBy(() -> service.update(1, "queue", NEW_VALUE))
                .isInstanceOf(RevisionMismatchException.class);
        assertThatThrownBy(() -> service.update(0, "queue", UNKNOWN_KEY))
                .isInstanceOf(RuntimeConfigValidationException.class);

        assertThat(meterRegistry.get("controlplane_runtime_config_apply_duration_seconds").timer().count()).isZero();
    }

    private static RuntimeConfigService service(TestExtension extension) {
        return service(extension, new SimpleMeterRegistry());
    }

    private static RuntimeConfigService service(TestExtension extension, SimpleMeterRegistry meterRegistry) {
        return new RuntimeConfigService(new RuntimeConfigRegistry(List.of(extension)), meterRegistry);
    }

    private static final class TestExtension implements RuntimeConfigExtension {
        private final String namespace;
        private int value;
        private boolean fail;
        private boolean failSnapshotAfterApply;
        private boolean snapshotFailurePending;

        private TestExtension(String namespace, int value) {
            this.namespace = namespace;
            this.value = value;
        }

        @Override public String namespace() { return namespace; }
        @Override public Map<String, Object> snapshot() {
            if (snapshotFailurePending) throw new IllegalStateException("snapshot boom");
            return Map.of("value", value);
        }
        @Override public List<String> validate(Map<String, Object> patch) {
            return patch.containsKey("invalid") ? List.of("invalid") : List.of();
        }
        @Override public void apply(Map<String, Object> patch) {
            value = ((Number) patch.get("value")).intValue();
            if (fail) throw new IllegalStateException("boom");
            snapshotFailurePending = failSnapshotAfterApply;
        }
        @Override public void restore(Map<String, Object> snapshot) {
            value = ((Number) snapshot.get("value")).intValue();
            snapshotFailurePending = false;
        }
    }
}
