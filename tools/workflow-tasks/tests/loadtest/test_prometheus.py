from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import MagicMock, patch

import pytest

from workflow_tasks.loadtest.prometheus import query_prometheus_range_series


def test_query_range_series_raises_on_http_error() -> None:
    with patch("workflow_tasks.loadtest.prometheus.httpx.get") as mock_get:
        mock_get.side_effect = Exception("connection refused")
        with pytest.raises(RuntimeError, match="prometheus api request failed"):
            query_prometheus_range_series(
                "http://localhost:9090",
                "http_requests_total",
                datetime(2026, 1, 1, tzinfo=timezone.utc),
                datetime(2026, 1, 1, 1, tzinfo=timezone.utc),
            )


def test_query_range_series_returns_parsed_points() -> None:
    mock_response = MagicMock()
    mock_response.json.return_value = {
        "status": "success",
        "data": {
            "result": [
                {
                    "metric": {"__name__": "http_requests_total"},
                    "values": [
                        [1704067200.0, "42"],
                        [1704067260.0, "43"],
                    ],
                }
            ]
        },
    }

    with patch("workflow_tasks.loadtest.prometheus.httpx.get", return_value=mock_response):
        result = query_prometheus_range_series(
            "http://localhost:9090",
            "http_requests_total",
            datetime(2026, 1, 1, tzinfo=timezone.utc),
            datetime(2026, 1, 1, 1, tzinfo=timezone.utc),
        )

    assert isinstance(result, list)
    assert len(result) == 2
    assert result[0]["value"] == 42.0


def test_query_prometheus_server_time_parses_time_scalar(monkeypatch) -> None:
    from workflow_tasks.loadtest import prometheus

    monkeypatch.setattr(
        prometheus,
        "_prometheus_api_get",
        lambda *a, **k: {"resultType": "scalar", "result": [1780728785.1, "1780728785.1"]},
    )
    assert prometheus.query_prometheus_server_time("http://prom") == 1780728785.1
