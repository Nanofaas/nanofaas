import {
    Counter,
    Gauge,
    Histogram,
    Registry,
    collectDefaultMetrics,
} from "prom-client";

export type RuntimeMetrics = {
    registry: Registry;
    invocations: Counter<"success">;
    duration: Histogram<string>;
    inFlight: Gauge<string>;
    coldStarts: Counter<string>;
    callbackFailures: Counter<string>;
};

export function createMetrics(): RuntimeMetrics {
    const registry = new Registry();
    collectDefaultMetrics({ register: registry });

    const invocations = new Counter({
        name: "runtime_invocations_total",
        help: "Total runtime invocations.",
        labelNames: ["success"] as const,
        registers: [registry],
    });

    const duration = new Histogram({
        name: "runtime_invocation_duration_seconds",
        help: "Runtime invocation duration in seconds.",
        buckets: [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 5],
        registers: [registry],
    });

    const inFlight = new Gauge({
        name: "runtime_in_flight",
        help: "Current in-flight runtime invocations.",
        registers: [registry],
    });

    const coldStarts = new Counter({
        name: "runtime_cold_start",
        help: "Total cold-start invocations.",
        registers: [registry],
    });

    const callbackFailures = new Counter({
        name: "runtime_callback_failures",
        help: "Total callback delivery failures.",
        registers: [registry],
    });

    return {
        registry,
        invocations,
        duration,
        inFlight,
        coldStarts,
        callbackFailures,
    };
}
