# tools/workflow-tasks/tests/loadtest/test_loadtest_adapters.py
from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import patch

from workflow_tasks.loadtest.adapters import HttpPrometheusClient
from workflow_tasks.loadtest.models import TimeWindow


def _make_window() -> TimeWindow:
    return TimeWindow(
        start=datetime(2026, 1, 1, 10, 0, tzinfo=timezone.utc),
        end=datetime(2026, 1, 1, 10, 30, tzinfo=timezone.utc),
    )


def test_http_prometheus_client_calls_query_range_series() -> None:
    fake_points = [{"timestamp": 1.0, "value": 42.0}]
    window = _make_window()

    with patch(
        "workflow_tasks.loadtest.adapters.query_prometheus_range_series",
        return_value=fake_points,
    ) as mock_fn:
        client = HttpPrometheusClient(url="http://prometheus:9090")
        result = client.query_range("http_requests_total", window)

    assert result == fake_points
    mock_fn.assert_called_once_with(
        "http://prometheus:9090",
        "http_requests_total",
        window.start,
        window.end,
        5,
    )
