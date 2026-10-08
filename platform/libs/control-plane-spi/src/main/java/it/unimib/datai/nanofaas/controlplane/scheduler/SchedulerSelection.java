package it.unimib.datai.nanofaas.controlplane.scheduler;

import java.util.List;
import java.util.Objects;

/**
 * A point-in-time view of which scheduling policy is active and which policies could be
 * switched to.
 *
 * @param strategy  id of the currently active {@link SchedulingStrategy}
 * @param available ids of every registered strategy, including {@code strategy}
 */
public record SchedulerSelection(String strategy, List<String> available) {
    public SchedulerSelection {
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(available, "available must not be null");
        available = List.copyOf(available);
    }
}
