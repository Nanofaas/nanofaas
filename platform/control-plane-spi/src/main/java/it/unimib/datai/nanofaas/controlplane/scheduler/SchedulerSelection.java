package it.unimib.datai.nanofaas.controlplane.scheduler;

import java.util.List;
import java.util.Objects;

/**
 * A point-in-time view of which scheduling policy is active, which policies could be
 * switched to, and how the current selection persists across restarts.
 *
 * @param strategy    id of the currently active {@link SchedulingStrategy}
 * @param available   ids of every registered strategy, including {@code strategy}
 * @param persistence how the selection survives a restart (e.g. {@code "restart"} meaning
 *                    it does not — it reverts to the configured initial strategy)
 */
public record SchedulerSelection(String strategy, List<String> available, String persistence) {

    public SchedulerSelection {
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(available, "available must not be null");
        Objects.requireNonNull(persistence, "persistence must not be null");
        available = List.copyOf(available);
    }
}
