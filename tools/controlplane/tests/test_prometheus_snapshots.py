from datetime import datetime, timezone
from pathlib import Path

import pytest

from controlplane_tool.loadtest.prometheus_snapshots import (
    PrometheusQuerySpec,
    capture_prometheus_snapshots,
)


def test_capture_prometheus_snapshots_writes_required_and_optional_queries(tmp_path: Path) -> None:
    start = datetime(2026, 5, 13, 10, 0, tzinfo=timezone.utc)
    end = datetime(2026, 5, 13, 10, 1, tzinfo=timezone.utc)
    calls: list[str] = []

    def query_range(base_url: str, query: str, start: datetime, end: datetime, step_seconds: int = 5):  # noqa: ARG001
        calls.append(f"{base_url}:{query}")
        if query == "optional_missing":
            return []
        return [{"timestamp": start.isoformat(), "value": 1.0}]

    snapshot_path = capture_prometheus_snapshots(
        prometheus_url="http://stack.example:30090",
        output_dir=tmp_path,
        start=start,
        end=end,
        specs=(
            PrometheusQuerySpec(name="dispatch", query="function_dispatch_total", required=True),
            PrometheusQuerySpec(name="optional", query="optional_missing", required=False),
        ),
        query_range=query_range,
    )

    assert snapshot_path == tmp_path / "metrics" / "prometheus-snapshots.json"
    payload = snapshot_path.read_text(encoding="utf-8")
    assert "http://stack.example:30090" in payload
    assert "function_dispatch_total" in payload
    assert "optional_missing" in payload
    assert calls == [
        "http://stack.example:30090:function_dispatch_total",
        "http://stack.example:30090:optional_missing",
    ]


def test_capture_prometheus_snapshots_fails_when_required_query_has_no_data(tmp_path: Path) -> None:
    start = datetime(2026, 5, 13, 10, 0, tzinfo=timezone.utc)
    end = datetime(2026, 5, 13, 10, 1, tzinfo=timezone.utc)

    with pytest.raises(RuntimeError, match="required prometheus query"):
        capture_prometheus_snapshots(
            prometheus_url="http://stack.example:30090",
            output_dir=tmp_path,
            start=start,
            end=end,
            specs=(PrometheusQuerySpec(name="dispatch", query="function_dispatch_total", required=True),),
            query_range=lambda *args, **kwargs: [],
        )
