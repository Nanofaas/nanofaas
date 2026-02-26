from controlplane_tool.metrics import missing_required_metrics, parse_prometheus_metric_names


def test_parse_prometheus_metric_names_and_detect_missing() -> None:
    payload = """
# HELP function_dispatch_total Dispatch attempts.
# TYPE function_dispatch_total counter
function_dispatch_total{function=\"echo\"} 3.0
# HELP process_cpu_usage CPU usage
# TYPE process_cpu_usage gauge
process_cpu_usage 0.52
"""
    names = parse_prometheus_metric_names(payload)
    missing = missing_required_metrics(
        required=["function_dispatch_total", "process_cpu_usage", "function_latency_ms"],
        observed_names=names,
    )

    assert "function_dispatch_total" in names
    assert "process_cpu_usage" in names
    assert missing == ["function_latency_ms"]
