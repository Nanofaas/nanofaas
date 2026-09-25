package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Startup configuration for the composed scheduling engine.
 *
 * <p>{@code strategy} is the initial/explicit scheduling algorithm id ({@code per-function} or
 * {@code shared-queue}). When absent, {@link SchedulerConfiguration} derives it from the
 * strategies actually built into this artifact. An explicit id that no built-in strategy
 * provides is a startup error (surfaced by {@code StrategyRegistry#require}).
 *
 * <h2>Why there are no switch-budget keys here</h2>
 *
 * <p>{@link SchedulerConfiguration} consumes only {@code strategy}. Keys such as
 * {@code max-switch-preparation} or {@code max-switch-pause} would be read by nobody and would
 * not describe what the engine does: its preparation budget is the compiled
 * {@code SchedulerEngine.SWITCH_BUDGET_MS} (50 ms), and its pause is bounded by the engine's own
 * design rather than by a setting.
 *
 * <p>The thresholds a switch is verified against live where they are <em>enforced</em>: the frozen
 * values in {@code docs/experiments/scheduler-switching-2026-09/budgets.json} (the switching
 * benchmark harness fails a run that exceeds them) and the engine's own compiled bound at
 * runtime. Wiring a frozen measurement threshold into runtime configuration would make it
 * settable, which is the opposite of what a threshold is for, and a second home for the same
 * number is how the two drift apart.
 *
 * <p>So: the switch pause is bounded by the engine and verified at the frozen threshold. It is not
 * settable at runtime, and this record does not pretend otherwise.
 */
@ConfigurationProperties(prefix = "nanofaas.scheduler")
public record SchedulerProperties(String strategy) {
}
