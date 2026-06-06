# tools/workflow-tasks/src/workflow_tasks/loadtest/adapters.py
from __future__ import annotations

from workflow_tasks.loadtest.models import TimeWindow
from workflow_tasks.loadtest.prometheus import (
    query_prometheus_range_series,
    query_prometheus_server_time,
)


class HttpPrometheusClient:
    """Implements PrometheusClient using the Prometheus HTTP API."""

    def __init__(self, url: str) -> None:
        self._url = url

    def query_range(
        self,
        expr: str,
        window: TimeWindow,
        step_seconds: int = 5,
    ) -> list[dict[str, float | str]]:
        return query_prometheus_range_series(
            self._url, expr, window.start, window.end, step_seconds
        )

    def server_time(self) -> float:
        """Prometheus's current clock (epoch seconds), for window alignment."""
        return query_prometheus_server_time(self._url)
