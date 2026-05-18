from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from workflow_tasks.loadtest.models import TimeWindow

from controlplane_tool.loadtest.loadtest_adapters import HttpPrometheusClient, VmFileFetcher


def _make_window() -> TimeWindow:
    return TimeWindow(
        start=datetime(2026, 1, 1, 10, 0, tzinfo=timezone.utc),
        end=datetime(2026, 1, 1, 10, 30, tzinfo=timezone.utc),
    )


def test_vm_file_fetcher_calls_transfer_from(tmp_path: Path) -> None:
    vm = MagicMock()
    vm.transfer_from.return_value = MagicMock(return_code=0)
    request = MagicMock()
    fetcher = VmFileFetcher(vm=vm, request=request)

    fetcher.fetch_from("/remote/results", tmp_path / "local")

    vm.transfer_from.assert_called_once_with(
        request, source="/remote/results", destination=tmp_path / "local"
    )


def test_vm_file_fetcher_raises_on_nonzero() -> None:
    vm = MagicMock()
    vm.transfer_from.return_value = MagicMock(return_code=1, stderr="permission denied", stdout="")
    fetcher = VmFileFetcher(vm=vm, request=MagicMock())

    with pytest.raises(RuntimeError, match="permission denied"):
        fetcher.fetch_from("/remote/results", Path("/local"))


def test_http_prometheus_client_delegates_to_query_fn() -> None:
    window = _make_window()
    fake_points = [{"timestamp": "t", "value": 1.0}]

    with patch(
        "controlplane_tool.loadtest.loadtest_adapters.query_prometheus_range_series",
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
