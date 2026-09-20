package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Startup configuration for the composed scheduling engine (Task 8, issue #208).
 *
 * <p>{@code strategy} is the initial/explicit scheduling algorithm id ({@code per-function} or
 * {@code shared-queue}). When absent, {@link SchedulerConfiguration} derives it from the
 * strategies actually built into this artifact. An explicit id that no built-in strategy
 * provides is a startup error (surfaced by {@code StrategyRegistry#require}).
 *
 * <p>{@code maxSwitchPreparation} and {@code maxSwitchPause} describe the budget a manual
 * strategy switch must respect; they are validated here but the engine's own rebuild budget
 * ({@code SchedulerEngine.SWITCH_BUDGET_MS}/{@code MAX_SWITCH_REBUILD_TICKETS}) is not yet
 * wired to a runtime-configurable value — connecting these two is left to the task that
 * measures the switch pause against them (plan-context: "Task 12 misura la pausa").
 */
@ConfigurationProperties(prefix = "nanofaas.scheduler")
public record SchedulerProperties(String strategy, Duration maxSwitchPreparation, Duration maxSwitchPause) {

    private static final Duration DEFAULT_MAX_SWITCH_PREPARATION = Duration.ofSeconds(2);
    private static final Duration DEFAULT_MAX_SWITCH_PAUSE = Duration.ofMillis(250);

    public SchedulerProperties {
        if (maxSwitchPreparation == null) {
            maxSwitchPreparation = DEFAULT_MAX_SWITCH_PREPARATION;
        }
        if (maxSwitchPause == null) {
            maxSwitchPause = DEFAULT_MAX_SWITCH_PAUSE;
        }
    }
}
