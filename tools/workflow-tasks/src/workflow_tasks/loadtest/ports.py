from __future__ import annotations

from pathlib import Path
from typing import Protocol

from workflow_tasks.loadtest.models import TimeWindow


class RemoteFileFetcher(Protocol):
    def fetch_from(self, remote: str, local: Path) -> None: ...


class PrometheusClient(Protocol):
    def query_range(
        self,
        expr: str,
        window: TimeWindow,
        step_seconds: int = 5,
    ) -> list[dict[str, float | str]]: ...
