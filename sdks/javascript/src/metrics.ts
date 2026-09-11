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
    activeHandlers: Gauge<string>;
    inputBytes: Gauge<string>;
    outputBytes: Gauge<string>;
    pendingCallbacks: Gauge<string>;
    pendingCallbackBytes: Gauge<string>;
    serializedCallbackBytes: Gauge<string>;
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

    const activeHandlers = new Gauge({
        name: "runtime_active_handlers",
        help: "Handler promises that still own runtime capacity.",
        registers: [registry],
    });
    const inputBytes = new Gauge({
        name: "runtime_input_bytes",
        help: "Input bytes retained by active runtime work.",
        registers: [registry],
    });
    const outputBytes = new Gauge({
        name: "runtime_output_bytes",
        help: "Serialized handler output bytes retained by the runtime.",
        registers: [registry],
    });
    const pendingCallbacks = new Gauge({
        name: "runtime_pending_callbacks",
        help: "Callback count reserved by admitted invocations.",
        registers: [registry],
    });
    const pendingCallbackBytes = new Gauge({
        name: "runtime_pending_callback_bytes",
        help: "Callback bytes reserved by admitted invocations.",
        registers: [registry],
    });
    const serializedCallbackBytes = new Gauge({
        name: "runtime_serialized_callback_bytes",
        help: "Serialized callback body bytes retained for delivery.",
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
        activeHandlers,
        inputBytes,
        outputBytes,
        pendingCallbacks,
        pendingCallbackBytes,
        serializedCallbackBytes,
        coldStarts,
        callbackFailures,
    };
}
