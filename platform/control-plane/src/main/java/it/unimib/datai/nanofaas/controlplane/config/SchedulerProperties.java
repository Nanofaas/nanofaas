package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Startup configuration for the composed scheduling engine (Task 8, issue #208).
 *
 * <p>{@code strategy} is the initial/explicit scheduling algorithm id ({@code per-function} or
 * {@code shared-queue}). When absent, {@link SchedulerConfiguration} derives it from the
 * strategies actually built into this artifact. An explicit id that no built-in strategy
 * provides is a startup error (surfaced by {@code StrategyRegistry#require}).
 *
 * <h2>Why there are no switch-budget keys here</h2>
 *
 * <p>Task 13a first declared {@code max-switch-preparation} and {@code max-switch-pause} here, as
 * the plan's Task 13 YAML specifies. A review found them inert, and they were removed: they bound
 * to this record and were then read by nobody ({@link SchedulerConfiguration} consumes only
 * {@code strategy}), nothing range-checked them, and the numbers did not describe what the engine
 * does — its preparation budget is the compiled {@code SchedulerEngine.SWITCH_BUDGET_MS} (50 ms,
 * not the 2 s declared), and its pause is bounded by the engine's own design rather than by a
 * setting.
 *
 * <p>The thresholds a switch is verified against live where they are <em>enforced</em>: the frozen
 * values in {@code docs/experiments/scheduler-switching-2026-09/budgets.json} (Task 12's harness
 * fails a run that exceeds them) and the engine's own compiled bound at runtime. Wiring a frozen
 * measurement threshold into runtime configuration would make it settable, which is the opposite
 * of what a threshold is for, and a second home for the same number is how the two drift apart.
 *
 * <p>So: the switch pause is bounded by the engine and verified at the frozen threshold. It is not
 * settable at runtime, and this record does not pretend otherwise.
 */
@ConfigurationProperties(prefix = "nanofaas.scheduler")
public record SchedulerProperties(String strategy) {
}
