package it.unimib.datai.nanofaas.controlplane.scheduler;

/**
 * Administrative control surface for hot-switching the engine's active scheduling
 * strategy. Implemented by the engine itself; consumed by admin APIs (e.g. runtime-config)
 * without those callers depending on the engine implementation.
 */
public interface SchedulerControl {

    /**
     * The current selection: active strategy id, every available strategy id, and how the
     * selection persists across restarts.
     */
    SchedulerSelection snapshot();

    /**
     * Switches the active strategy to the one identified by {@code strategy}. Implementations
     * may throw an unchecked exception (e.g. a switch-specific exception distinguishing
     * preparation failure, temporary capacity, and pre-commit timeout) if the switch cannot
     * be completed; a failed switch leaves the previous strategy active.
     */
    void switchTo(String strategy);
}
