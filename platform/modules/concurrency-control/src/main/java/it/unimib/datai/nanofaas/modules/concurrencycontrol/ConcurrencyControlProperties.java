package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nanofaas.concurrency-control")
// One constructor only: Boot infers constructor binding for a single-constructor record, and a
// convenience overload silently disabled it — the properties bound to nothing and every default
// quietly applied.
public record ConcurrencyControlProperties(
        Long pollIntervalMs,
        Integer defaultTargetInFlightPerPod,
        Integer totalBudget
) {
    /**
     * How many invocations may be in flight across every BUDGETED function at once.
     *
     * <p>A platform capacity statement, not something to infer from load: derived from observed
     * throughput it would grow under load and shrink when idle, which is the opposite of what a
     * budget is for. Unset, it scales with the cores the control plane can see, which is a guess —
     * but a guess that at least tracks the machine rather than a constant.</p>
     */
    public int totalBudgetOrDefault() {
        if (totalBudget != null && totalBudget > 0) {
            return totalBudget;
        }
        return Math.max(8, Runtime.getRuntime().availableProcessors() * 4);
    }

    public long pollIntervalMsOrDefault() {
        return pollIntervalMs != null && pollIntervalMs > 0 ? pollIntervalMs : 5000;
    }

    public int defaultTargetInFlightPerPodOrDefault() {
        return defaultTargetInFlightPerPod != null && defaultTargetInFlightPerPod > 0
                ? defaultTargetInFlightPerPod
                : 2;
    }
}
