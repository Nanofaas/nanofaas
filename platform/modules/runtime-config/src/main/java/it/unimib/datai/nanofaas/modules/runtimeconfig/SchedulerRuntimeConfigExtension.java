package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.config.PreparedRuntimeConfigChange;
import it.unimib.datai.nanofaas.controlplane.config.PreparedRuntimeConfigExtension;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerControl;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerSelection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes the engine's active scheduling strategy under the {@code scheduler} runtime
 * config namespace and lets it be hot-switched through {@link SchedulerControl}.
 *
 * <p>The switch is irreversible once activated ({@link SchedulerControl#switchTo(String)}
 * is the linearization point), so this extension is a {@link PreparedRuntimeConfigExtension}:
 * validation and the resulting snapshot are computed up front by {@link #prepare(Map)}, and
 * {@link PreparedRuntimeConfigChange#commit()} does nothing but call {@code switchTo}. The
 * legacy {@code apply} delegates to prepare/commit for callers still on that path; {@code
 * restore} is never invoked by {@link RuntimeConfigService} for a prepared extension, since a
 * committed switch cannot be undone retroactively.</p>
 */
public final class SchedulerRuntimeConfigExtension implements PreparedRuntimeConfigExtension {

    private static final String STRATEGY_KEY = "strategy";
    private static final String AVAILABLE_KEY = "available";
    private static final String PERSISTENCE_KEY = "persistence";

    private final SchedulerControl control;

    public SchedulerRuntimeConfigExtension(SchedulerControl control) {
        this.control = Objects.requireNonNull(control, "control must not be null");
    }

    @Override
    public String namespace() {
        return "scheduler";
    }

    @Override
    public Map<String, Object> snapshot() {
        return toSnapshot(control.snapshot());
    }

    @Override
    public List<String> validate(Map<String, Object> patch) {
        List<String> errors = new ArrayList<>();
        if (patch.size() != 1 || !patch.containsKey(STRATEGY_KEY)) {
            errors.add("patch must contain exactly one field: " + STRATEGY_KEY);
            return errors;
        }
        Object value = patch.get(STRATEGY_KEY);
        if (!(value instanceof String target) || target.isBlank()) {
            errors.add(STRATEGY_KEY + " must be a non-blank string");
            return errors;
        }
        if (!control.snapshot().available().contains(target)) {
            errors.add(STRATEGY_KEY + " must be one of the available strategies");
        }
        return errors;
    }

    @Override
    public PreparedRuntimeConfigChange prepare(Map<String, Object> patch) {
        String target = (String) patch.get(STRATEGY_KEY);
        // validate() already refused an unavailable target; switchTo re-checks it anyway.
        SchedulerSelection current = control.snapshot();
        Map<String, Object> snapshotAfterCommit = toSnapshot(
                new SchedulerSelection(target, current.available(), current.persistence()));
        return new SchedulerSwitch(control, target, snapshotAfterCommit);
    }

    @Override
    public void apply(Map<String, Object> patch) {
        try (PreparedRuntimeConfigChange change = prepare(Map.copyOf(patch))) {
            change.commit();
        }
    }

    @Override
    public void restore(Map<String, Object> snapshot) {
        throw new UnsupportedOperationException(
                "scheduler runtime config commits through prepare/commit; restore is never invoked for it");
    }

    private static Map<String, Object> toSnapshot(SchedulerSelection selection) {
        return Map.of(
                STRATEGY_KEY, selection.strategy(),
                AVAILABLE_KEY, selection.available(),
                PERSISTENCE_KEY, selection.persistence());
    }

    private static final class SchedulerSwitch implements PreparedRuntimeConfigChange {
        private final SchedulerControl control;
        private final String target;
        private final Map<String, Object> snapshotAfterCommit;

        private SchedulerSwitch(SchedulerControl control, String target, Map<String, Object> snapshotAfterCommit) {
            this.control = control;
            this.target = target;
            this.snapshotAfterCommit = snapshotAfterCommit;
        }

        @Override
        public Map<String, Object> snapshotAfterCommit() {
            return snapshotAfterCommit;
        }

        @Override
        public void commit() {
            control.switchTo(target);
        }

        @Override
        public void close() {
            // Nothing to release: prepare() only computed values, it never reserved
            // engine state. A failed or skipped commit leaves the previous strategy
            // active on its own; there is nothing here to abort.
        }
    }
}
