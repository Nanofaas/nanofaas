# tools/controlplane/src/controlplane_tool/loadtest/loadtest_adapters.py
from __future__ import annotations

from workflow_tasks.vm.runners import OrchestratorVmRunner, VmFileFetcher
from workflow_tasks.loadtest.models import TimeWindow
from controlplane_tool.loadtest.metrics import query_prometheus_range_series

__all__ = ["OrchestratorVmRunner", "VmFileFetcher", "HttpPrometheusClient"]


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
