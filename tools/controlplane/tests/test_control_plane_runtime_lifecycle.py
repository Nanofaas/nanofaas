"""
Tests for control_plane_runtime.py — ControlPlaneRuntimeManager lifecycle helpers.
"""
from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from controlplane_tool.control_plane_runtime import (
    ControlPlaneRuntimeManager,
    ControlPlaneSession,
)


# ---------------------------------------------------------------------------
# ControlPlaneSession
# ---------------------------------------------------------------------------

def test_session_prometheus_scrape_target_uses_management_port() -> None:
    session = ControlPlaneSession(
        base_url="http://127.0.0.1:8080",
        management_url="http://127.0.0.1:8081",
        api_port=8080,
        management_port=8081,
    )
    assert session.prometheus_scrape_target == "host.docker.internal:8081"


# ---------------------------------------------------------------------------
# _parse_port_or_default
# ---------------------------------------------------------------------------

def test_parse_port_extracts_port_from_url(tmp_path: Path) -> None:
    mgr = ControlPlaneRuntimeManager(tmp_path)
    assert mgr._parse_port_or_default("http://localhost:9999", 8080) == 9999


def test_parse_port_returns_default_when_no_port(tmp_path: Path) -> None:
    mgr = ControlPlaneRuntimeManager(tmp_path)
    assert mgr._parse_port_or_default("http://localhost", 8080) == 8080


def test_parse_port_returns_default_on_invalid_port(tmp_path: Path) -> None:
    mgr = ControlPlaneRuntimeManager(tmp_path)
    assert mgr._parse_port_or_default("http://localhost:abc", 8080) == 8080


# ---------------------------------------------------------------------------
# _tail
# ---------------------------------------------------------------------------

def test_tail_returns_no_logs_when_file_missing(tmp_path: Path) -> None:
    mgr = ControlPlaneRuntimeManager(tmp_path)
    result = mgr._tail(tmp_path / "missing.log")
    assert result == "no logs"


def test_tail_returns_empty_logs_for_empty_file(tmp_path: Path) -> None:
    log = tmp_path / "out.log"
    log.write_text("", encoding="utf-8")
    mgr = ControlPlaneRuntimeManager(tmp_path)
    assert mgr._tail(log) == "empty logs"


def test_tail_truncates_to_max_chars(tmp_path: Path) -> None:
    log = tmp_path / "out.log"
    log.write_text("x" * 1000, encoding="utf-8")
    mgr = ControlPlaneRuntimeManager(tmp_path)
    result = mgr._tail(log, max_chars=100)
    assert len(result) == 100


def test_tail_returns_full_content_when_short_enough(tmp_path: Path) -> None:
    log = tmp_path / "out.log"
    log.write_text("hello world", encoding="utf-8")
    mgr = ControlPlaneRuntimeManager(tmp_path)
    assert mgr._tail(log) == "hello world"


# ---------------------------------------------------------------------------
# _is_ready
# ---------------------------------------------------------------------------

def test_is_ready_returns_true_on_http_200(tmp_path: Path) -> None:
    mock_response = MagicMock()
    mock_response.status_code = 200
    mgr = ControlPlaneRuntimeManager(tmp_path)
    with patch("controlplane_tool.control_plane_runtime.httpx.get", return_value=mock_response):
        assert mgr._is_ready("http://127.0.0.1:8080") is True


def test_is_ready_returns_false_on_connection_error(tmp_path: Path) -> None:
    import httpx
    mgr = ControlPlaneRuntimeManager(tmp_path)
    with patch("controlplane_tool.control_plane_runtime.httpx.get", side_effect=httpx.RequestError("refused")):
        assert mgr._is_ready("http://127.0.0.1:8080") is False


def test_is_ready_returns_false_on_non_200_status(tmp_path: Path) -> None:
    mock_response = MagicMock()
    mock_response.status_code = 503
    mgr = ControlPlaneRuntimeManager(tmp_path)
    with patch("controlplane_tool.control_plane_runtime.httpx.get", return_value=mock_response):
        assert mgr._is_ready("http://127.0.0.1:8080") is False


# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# cleanup
# ---------------------------------------------------------------------------

def test_cleanup_does_nothing_when_no_owned_process(tmp_path: Path) -> None:
    session = ControlPlaneSession(
        base_url="http://127.0.0.1:8080",
        management_url="http://127.0.0.1:8081",
        api_port=8080,
        management_port=8081,
        owned_process=None,
    )
    mgr = ControlPlaneRuntimeManager(tmp_path)
    mgr.cleanup(session)  # must not raise


def test_cleanup_terminates_owned_process(tmp_path: Path) -> None:
    mock_process = MagicMock()
    mock_process.poll.return_value = None  # still running
    session = ControlPlaneSession(
        base_url="http://127.0.0.1:8080",
        management_url="http://127.0.0.1:8081",
        api_port=8080,
        management_port=8081,
        owned_process=mock_process,
    )
    mgr = ControlPlaneRuntimeManager(tmp_path)
    mgr.cleanup(session)
    mock_process.terminate.assert_called_once()


def test_cleanup_kills_process_on_timeout(tmp_path: Path) -> None:
    import subprocess as _subprocess

    mock_process = MagicMock()
    mock_process.poll.return_value = None
    mock_process.wait.side_effect = [_subprocess.TimeoutExpired(cmd="java", timeout=15), None]
    session = ControlPlaneSession(
        base_url="http://127.0.0.1:8080",
        management_url="http://127.0.0.1:8081",
        api_port=8080,
        management_port=8081,
        owned_process=mock_process,
    )
    mgr = ControlPlaneRuntimeManager(tmp_path)
    mgr.cleanup(session)
    mock_process.kill.assert_called_once()


# ---------------------------------------------------------------------------
# ensure_available — external URL path
# ---------------------------------------------------------------------------

def test_ensure_available_uses_external_url_when_env_set(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_TOOL_CONTROL_PLANE_URL", "http://external:9090")
    monkeypatch.delenv("NANOFAAS_TOOL_CONTROL_PLANE_MANAGEMENT_URL", raising=False)
    mgr = ControlPlaneRuntimeManager(tmp_path, startup_timeout_seconds=0.1)
    with patch.object(mgr, "_wait_ready", return_value=True):
        session = mgr.ensure_available(tmp_path, "http://ignored:6443")
    assert session.base_url == "http://external:9090"
    assert session.owned_process is None


def test_ensure_available_raises_when_external_url_not_reachable(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_TOOL_CONTROL_PLANE_URL", "http://external:9090")
    mgr = ControlPlaneRuntimeManager(tmp_path, startup_timeout_seconds=0.1)
    with patch.object(mgr, "_wait_ready", return_value=False):
        with pytest.raises(RuntimeError, match="not reachable"):
            mgr.ensure_available(tmp_path, "http://ignored:6443")
