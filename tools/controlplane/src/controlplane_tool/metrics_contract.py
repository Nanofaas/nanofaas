from __future__ import annotations

CORE_REQUIRED_METRICS: tuple[str, ...] = (
    "function_dispatch_total",
    "function_success_total",
    "function_warm_start_total",
    "function_latency_ms",
    "function_queue_wait_ms",
    "function_e2e_latency_ms",
)

LEGACY_STRICT_REQUIRED_METRICS: tuple[str, ...] = (
    "function_enqueue_total",
    "function_dispatch_total",
    "function_success_total",
    "function_error_total",
    "function_retry_total",
    "function_timeout_total",
    "function_queue_rejected_total",
    "function_cold_start_total",
    "function_warm_start_total",
    "function_latency_ms",
    "function_init_duration_ms",
    "function_queue_wait_ms",
    "function_e2e_latency_ms",
)
