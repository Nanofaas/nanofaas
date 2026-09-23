package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.config.PreparedRuntimeConfigChange;
import it.unimib.datai.nanofaas.controlplane.config.PreparedRuntimeConfigExtension;
import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerControl;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerSelection;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    // --- Prepared (two-phase, commit-is-irreversible) update path -----------------------

    @Test
    void snapshotFailureOccursBeforeSchedulerCommit() {
        var extension = mock(PreparedRuntimeConfigExtension.class);
        var change = mock(PreparedRuntimeConfigChange.class);
        var other = mock(RuntimeConfigExtension.class);
        when(extension.namespace()).thenReturn("scheduler");
        when(extension.validate(any())).thenReturn(List.of());
        when(extension.prepare(any())).thenReturn(change);
        when(change.snapshotAfterCommit()).thenReturn(Map.of("strategy", "shared-queue"));
        when(other.namespace()).thenReturn("other");
        // Throws once (during the update's pre-commit snapshotReplacing), then lets the
        // post-assertion getSnapshot() succeed so the test can also observe the revision.
        when(other.snapshot())
                .thenThrow(new IllegalStateException("snapshot failure"))
                .thenReturn(Map.of());
        var service = new RuntimeConfigService(new RuntimeConfigRegistry(List.of(extension, other)),
                new SimpleMeterRegistry());
        assertThatThrownBy(() -> service.update(0, "scheduler", Map.of("strategy", "shared-queue")))
                .isInstanceOf(RuntimeConfigApplyException.class);
        verify(change, never()).commit();
        verify(change).close();
        verify(extension, never()).restore(any());
        assertThat(service.getSnapshot().revision()).isZero();
    }

    @Test
    void meterFailureAfterActivationDoesNotRevertOrLeaveStaleRevision() {
        var extension = mock(PreparedRuntimeConfigExtension.class);
        var change = mock(PreparedRuntimeConfigChange.class);
        when(extension.namespace()).thenReturn("scheduler");
        when(extension.validate(any())).thenReturn(List.of());
        when(extension.prepare(any())).thenReturn(change);
        when(change.snapshotAfterCommit()).thenReturn(Map.of("strategy", "shared-queue"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        meterRegistry.config().meterFilter(new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if ("success".equals(id.getTag("status"))) throw new IllegalStateException("metrics boom");
                return MeterFilterReply.NEUTRAL;
            }
        });
        var service = new RuntimeConfigService(new RuntimeConfigRegistry(List.of(extension)), meterRegistry);

        RuntimeConfigSnapshot updated = service.update(0, "scheduler", Map.of("strategy", "shared-queue"));

        assertThat(updated.revision()).isOne();
        assertThat(service.getSnapshot().revision()).isOne();
        verify(change).commit();
        verify(extension, never()).restore(any());
    }

    @Test
    void staleRevisionNeverPreparesTheChange() {
        var extension = mock(PreparedRuntimeConfigExtension.class);
        when(extension.namespace()).thenReturn("scheduler");
        var service = new RuntimeConfigService(new RuntimeConfigRegistry(List.of(extension)),
                new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.update(1, "scheduler", Map.of("strategy", "shared-queue")))
                .isInstanceOf(RevisionMismatchException.class);

        verify(extension, never()).validate(any());
        verify(extension, never()).prepare(any());
    }

    @Test
    void invalidPatchIsRejectedWithoutPreparing() {
        var extension = mock(PreparedRuntimeConfigExtension.class);
        when(extension.namespace()).thenReturn("scheduler");
        when(extension.validate(any())).thenReturn(List.of("strategy must be one of the available strategies"));
        var service = new RuntimeConfigService(new RuntimeConfigRegistry(List.of(extension)),
                new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.update(0, "scheduler", Map.of("strategy", "unknown")))
                .isInstanceOf(RuntimeConfigValidationException.class);

        verify(extension, never()).prepare(any());
        assertThat(service.getSnapshot().revision()).isZero();
    }

    @Test
    void noOpPreparedUpdateStillAdvancesRevisionOnce() {
        var extension = mock(PreparedRuntimeConfigExtension.class);
        var change = mock(PreparedRuntimeConfigChange.class);
        when(extension.namespace()).thenReturn("scheduler");
        when(extension.validate(any())).thenReturn(List.of());
        when(extension.prepare(any())).thenReturn(change);
        when(change.snapshotAfterCommit()).thenReturn(Map.of("strategy", "per-function"));
        var service = new RuntimeConfigService(new RuntimeConfigRegistry(List.of(extension)),
                new SimpleMeterRegistry());

        RuntimeConfigSnapshot updated = service.update(0, "scheduler", Map.of("strategy", "per-function"));

        assertThat(updated.revision()).isOne();
        assertThat(service.getSnapshot().revision()).isOne();
        verify(change).commit();
    }

    @Test
    void failureInAnyOtherNamespaceDuringSnapshotLeavesRevisionAndStateUnchanged() {
        var extension = mock(PreparedRuntimeConfigExtension.class);
        var change = mock(PreparedRuntimeConfigChange.class);
        var ok1 = mock(RuntimeConfigExtension.class);
        var failing = mock(RuntimeConfigExtension.class);
        var ok2 = mock(RuntimeConfigExtension.class);
        when(extension.namespace()).thenReturn("scheduler");
        when(extension.validate(any())).thenReturn(List.of());
        when(extension.prepare(any())).thenReturn(change);
        when(change.snapshotAfterCommit()).thenReturn(Map.of("strategy", "shared-queue"));
        when(ok1.namespace()).thenReturn("a");
        when(ok1.snapshot()).thenReturn(Map.of("x", 1));
        when(failing.namespace()).thenReturn("b");
        when(failing.snapshot())
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(Map.of());
        when(ok2.namespace()).thenReturn("c");
        when(ok2.snapshot()).thenReturn(Map.of("y", 2));
        var service = new RuntimeConfigService(
                new RuntimeConfigRegistry(List.of(extension, ok1, failing, ok2)), new SimpleMeterRegistry());

        assertThatThrownBy(() -> service.update(0, "scheduler", Map.of("strategy", "shared-queue")))
                .isInstanceOf(RuntimeConfigApplyException.class);

        verify(change, never()).commit();
        assertThat(service.getSnapshot().revision()).isZero();
    }

    @Test
    void cleanupFailureAfterSuccessfulCommitDoesNotFailTheUpdate() {
        var extension = mock(PreparedRuntimeConfigExtension.class);
        var change = mock(PreparedRuntimeConfigChange.class);
        when(extension.namespace()).thenReturn("scheduler");
        when(extension.validate(any())).thenReturn(List.of());
        when(extension.prepare(any())).thenReturn(change);
        when(change.snapshotAfterCommit()).thenReturn(Map.of("strategy", "shared-queue"));
        doThrow(new IllegalStateException("cleanup boom")).when(change).close();
        var service = new RuntimeConfigService(new RuntimeConfigRegistry(List.of(extension)),
                new SimpleMeterRegistry());

        RuntimeConfigSnapshot updated = service.update(0, "scheduler", Map.of("strategy", "shared-queue"));

        assertThat(updated.revision()).isOne();
        assertThat(service.getSnapshot().revision()).isOne();
        verify(change).commit();
    }

    // --- SchedulerRuntimeConfigExtension: the production PreparedRuntimeConfigExtension ---

    @Test
    void schedulerExtensionSnapshotReflectsControl() {
        FakeSchedulerControl control = new FakeSchedulerControl("per-function", List.of("per-function", "shared-queue"));
        SchedulerRuntimeConfigExtension extension = new SchedulerRuntimeConfigExtension(control);

        assertThat(extension.snapshot())
                .containsEntry("strategy", "per-function")
                .containsEntry("available", List.of("per-function", "shared-queue"))
                .containsEntry("persistence", "restart");
    }

    @Test
    void schedulerExtensionRejectsMissingOrUnavailableTarget() {
        FakeSchedulerControl control = new FakeSchedulerControl("per-function", List.of("per-function", "shared-queue"));
        SchedulerRuntimeConfigExtension extension = new SchedulerRuntimeConfigExtension(control);

        assertThat(extension.validate(Map.of())).isNotEmpty();
        assertThat(extension.validate(Map.of("strategy", "per-function", "extra", 1))).isNotEmpty();
        assertThat(extension.validate(Map.of("strategy", "does-not-exist"))).isNotEmpty();
        assertThat(extension.validate(Map.of("strategy", "shared-queue"))).isEmpty();
    }

    @Test
    void schedulerExtensionPrepareCommitsThroughControlOnly() {
        FakeSchedulerControl control = new FakeSchedulerControl("per-function", List.of("per-function", "shared-queue"));
        SchedulerRuntimeConfigExtension extension = new SchedulerRuntimeConfigExtension(control);
        RuntimeConfigService service = new RuntimeConfigService(
                new RuntimeConfigRegistry(List.of(extension)), new SimpleMeterRegistry());

        RuntimeConfigSnapshot updated = service.update(0, "scheduler", Map.of("strategy", "shared-queue"));

        assertThat(updated.namespaces().get("scheduler")).containsEntry("strategy", "shared-queue");
        assertThat(control.active).isEqualTo("shared-queue");
        assertThat(control.switchCount).isEqualTo(1);
    }

    private static final class FakeSchedulerControl implements SchedulerControl {
        private String active;
        private final List<String> available;
        private int switchCount;

        private FakeSchedulerControl(String active, List<String> available) {
            this.active = active;
            this.available = available;
        }

        @Override
        public SchedulerSelection snapshot() {
            return new SchedulerSelection(active, available);
        }

        @Override
        public void switchTo(String strategy) {
            switchCount++;
            active = strategy;
        }
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
