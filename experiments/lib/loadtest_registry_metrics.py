from __future__ import annotations


def dedup_by_function(expr: str) -> str:
    """
    Deduplicate duplicated scrape targets (pods + endpoints) by collapsing
    to max per (function, instance), then summing per function.
    """
    return f"sum by (function) (max by (function, instance) ({expr}))"


def build_prom_queries(
    lat_base: str,
    e2e_base: str,
    queue_wait_base: str,
    init_base: str,
) -> dict[str, str]:
    return {
        # Counters
        "enqueue": dedup_by_function("function_enqueue_total"),
        "dispatch": dedup_by_function("function_dispatch_total"),
        "success": dedup_by_function("function_success_total"),
        "error": dedup_by_function("function_error_total"),
        "timeout": dedup_by_function("function_timeout_total"),
        "rejected": dedup_by_function("function_queue_rejected_total"),
        "retry": dedup_by_function("function_retry_total"),
        "cold_start": dedup_by_function("function_cold_start_total"),
        "warm_start": dedup_by_function("function_warm_start_total"),
        # Timers — percentiles and mean components
        "latency_p50": dedup_by_function(f'{lat_base}{{quantile="0.5"}}'),
        "latency_p95": dedup_by_function(f'{lat_base}{{quantile="0.95"}}'),
        "latency_p99": dedup_by_function(f'{lat_base}{{quantile="0.99"}}'),
        "latency_count": dedup_by_function(f"{lat_base}_count"),
        "latency_sum": dedup_by_function(f"{lat_base}_sum"),
        "e2e_p50": dedup_by_function(f'{e2e_base}{{quantile="0.5"}}'),
        "e2e_p95": dedup_by_function(f'{e2e_base}{{quantile="0.95"}}'),
        "e2e_p99": dedup_by_function(f'{e2e_base}{{quantile="0.99"}}'),
        "queue_wait_p50": dedup_by_function(f'{queue_wait_base}{{quantile="0.5"}}'),
        "queue_wait_p95": dedup_by_function(f'{queue_wait_base}{{quantile="0.95"}}'),
        "queue_wait_count": dedup_by_function(f"{queue_wait_base}_count"),
        "queue_wait_sum": dedup_by_function(f"{queue_wait_base}_sum"),
        "init_p50": dedup_by_function(f'{init_base}{{quantile="0.5"}}'),
        "init_p95": dedup_by_function(f'{init_base}{{quantile="0.95"}}'),
        # Gauges
        "queue_depth": dedup_by_function("function_queue_depth"),
        "in_flight": dedup_by_function("function_inFlight"),
    }


def compute_avg_ms(sum_seconds: float, count: float) -> float:
    if count <= 0:
        return 0.0
    return round((sum_seconds / count) * 1000.0, 2)


def _parse_cpu_milli(cpu: str) -> int:
    cpu = cpu.strip()
    if not cpu:
        return 0
    if cpu.endswith("m"):
        return int(cpu[:-1])
    return int(float(cpu) * 1000)


def _parse_mem_bytes(mem: str) -> int:
    mem = mem.strip()
    if not mem:
        return 0
    units = {
        "Ki": 1024,
        "Mi": 1024**2,
        "Gi": 1024**3,
    }
    for unit, factor in units.items():
        if mem.endswith(unit):
            return int(float(mem[: -len(unit)]) * factor)
    return int(float(mem))


def _percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    sorted_values = sorted(values)
    idx = int(round((pct / 100.0) * (len(sorted_values) - 1)))
    return float(sorted_values[idx])


def summarize_control_plane_samples(lines: list[str]) -> dict[str, float]:
    cpu_m = []
    mem_b = []
    for line in lines:
        parts = line.split()
        if len(parts) < 3:
            continue
        cpu_m.append(_parse_cpu_milli(parts[-2]))
        mem_b.append(_parse_mem_bytes(parts[-1]))

    if not cpu_m or not mem_b:
        return {
            "samples": 0,
            "cpu_avg_m": 0.0,
            "cpu_p95_m": 0.0,
            "cpu_max_m": 0.0,
            "mem_avg_bytes": 0.0,
            "mem_p95_bytes": 0.0,
            "mem_max_bytes": 0.0,
        }

    return {
        "samples": len(cpu_m),
        "cpu_avg_m": round(sum(cpu_m) / len(cpu_m), 2),
        "cpu_p95_m": _percentile(cpu_m, 95),
        "cpu_max_m": float(max(cpu_m)),
        "mem_avg_bytes": round(sum(mem_b) / len(mem_b), 2),
        "mem_p95_bytes": _percentile(mem_b, 95),
        "mem_max_bytes": float(max(mem_b)),
    }


def _parse_timed_sample(line: str) -> tuple[int, str] | None:
    parts = line.split()
    if len(parts) < 4:
        return None
    try:
        ts = int(parts[0])
    except ValueError:
        return None
    # Normalize to the format expected by summarize_control_plane_samples.
    return ts, f"{parts[1]} {parts[2]} {parts[3]}"


def summarize_control_plane_samples_by_windows(
    sample_lines: list[str],
    windows: list[dict[str, int | str]],
) -> dict[str, dict[str, float]]:
    parsed = []
    for line in sample_lines:
        item = _parse_timed_sample(line)
        if item is not None:
            parsed.append(item)

    grouped: dict[str, dict[str, float]] = {}
    for window in windows:
        fn = str(window.get("function", ""))
        start = int(window.get("start", 0))
        end = int(window.get("end", 0))
        if not fn or end <= start:
            continue

        lines = [payload for ts, payload in parsed if start <= ts <= end]
        grouped[fn] = summarize_control_plane_samples(lines)
    return grouped


def merge_prom_with_snapshots(
    prom: dict[str, dict[str, float]],
    snapshots: list[dict],
) -> dict[str, dict[str, float]]:
    # Mutate a copy so callers can keep original data if needed.
    merged: dict[str, dict[str, float]] = {fn: dict(metrics) for fn, metrics in prom.items()}
    keys = (
        "latency_p50",
        "latency_p95",
        "latency_p99",
        "e2e_p50",
        "e2e_p95",
        "e2e_p99",
        "queue_wait_p50",
        "queue_wait_p95",
        "init_p50",
        "init_p95",
    )
    for snap in snapshots:
        fn = snap.get("function")
        metrics = snap.get("metrics", {})
        if not fn:
            continue
        merged.setdefault(fn, {})
        for key in keys:
            value = float(metrics.get(key, 0.0) or 0.0)
            current = float(merged[fn].get(key, 0.0) or 0.0)
            if current == 0.0 and value > 0.0:
                merged[fn][key] = value
    return merged
