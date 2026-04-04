"""
Tests for scenario_runtime.py — wait helpers, select_container_runtime, FakeControlPlane.
"""
from __future__ import annotations

import urllib.error
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from controlplane_tool.scenario_runtime import (
    FakeControlPlane,
    select_container_runtime,
    wait_for_http_any_status,
    wait_for_http_ok,
)


# ---------------------------------------------------------------------------
# wait_for_http_ok
# ---------------------------------------------------------------------------

def _http_200_response():
    mock = MagicMock()
    mock.__enter__ = lambda s: s
    mock.__exit__ = MagicMock(return_value=False)
    mock.status = 200
    return mock


def test_wait_for_http_ok_returns_true_on_first_200() -> None:
    with patch("urllib.request.urlopen", return_value=_http_200_response()):
        result = wait_for_http_ok("http://localhost:8080", interval_seconds=0)
    assert result is True


def test_wait_for_http_ok_retries_until_success() -> None:
    call_count = 0

    def fake_urlopen(url, timeout):
        nonlocal call_count
        call_count += 1
        if call_count < 3:
            raise OSError("not yet")
        return _http_200_response()

    with patch("urllib.request.urlopen", side_effect=fake_urlopen):
        with patch("time.sleep"):
            result = wait_for_http_ok("http://localhost:8080", max_attempts=5, interval_seconds=0)
    assert result is True
    assert call_count == 3


def test_wait_for_http_ok_returns_false_after_max_attempts() -> None:
    with patch("urllib.request.urlopen", side_effect=OSError("refused")):
        with patch("time.sleep"):
            result = wait_for_http_ok("http://localhost:8080", max_attempts=3, interval_seconds=0)
    assert result is False


def test_wait_for_http_ok_treats_3xx_as_failure() -> None:
    mock = MagicMock()
    mock.__enter__ = lambda s: s
    mock.__exit__ = MagicMock(return_value=False)
    mock.status = 301
    with patch("urllib.request.urlopen", return_value=mock):
        with patch("time.sleep"):
            result = wait_for_http_ok("http://localhost:8080", max_attempts=2, interval_seconds=0)
    assert result is False


# ---------------------------------------------------------------------------
# wait_for_http_any_status
# ---------------------------------------------------------------------------

def test_wait_for_http_any_status_returns_true_on_200() -> None:
    with patch("urllib.request.urlopen", return_value=_http_200_response()):
        result = wait_for_http_any_status("http://localhost:8080", interval_seconds=0)
    assert result is True


def test_wait_for_http_any_status_returns_true_on_http_error() -> None:
    with patch("urllib.request.urlopen", side_effect=urllib.error.HTTPError(
        url="http://localhost:8080", code=404, msg="Not Found", hdrs=None, fp=None  # type: ignore[arg-type]
    )):
        result = wait_for_http_any_status("http://localhost:8080")
    assert result is True


def test_wait_for_http_any_status_returns_false_on_timeout() -> None:
    with patch("urllib.request.urlopen", side_effect=OSError("refused")):
        with patch("time.sleep"):
            result = wait_for_http_any_status(
                "http://localhost:8080", max_attempts=2, interval_seconds=0
            )
    assert result is False


# ---------------------------------------------------------------------------
# select_container_runtime
# ---------------------------------------------------------------------------

def test_select_container_runtime_returns_preferred_when_available() -> None:
    with patch("shutil.which", side_effect=lambda x: f"/usr/bin/{x}" if x == "podman" else None):
        result = select_container_runtime(preferred="podman")
    assert result == "podman"


def test_select_container_runtime_falls_back_to_docker() -> None:
    with patch("shutil.which", side_effect=lambda x: "/usr/bin/docker" if x == "docker" else None):
        result = select_container_runtime()
    assert result == "docker"


def test_select_container_runtime_returns_none_when_none_available() -> None:
    with patch("shutil.which", return_value=None):
        result = select_container_runtime()
    assert result is None


def test_select_container_runtime_preferred_over_docker() -> None:
    with patch("shutil.which", return_value="/usr/bin/found"):
        result = select_container_runtime(preferred="nerdctl")
    assert result == "nerdctl"


# ---------------------------------------------------------------------------
# FakeControlPlane
# ---------------------------------------------------------------------------

def test_fake_control_plane_url_includes_port() -> None:
    fcp = FakeControlPlane(port=9999, request_body_path=Path("/tmp/body.json"))
    assert fcp.url() == "http://127.0.0.1:9999"


def test_fake_control_plane_stop_is_safe_when_not_started() -> None:
    fcp = FakeControlPlane(port=9998, request_body_path=Path("/tmp/body.json"))
    fcp.stop()  # must not raise


def test_fake_control_plane_stop_terminates_process(tmp_path: Path) -> None:
    fcp = FakeControlPlane(port=9997, request_body_path=tmp_path / "body.json")
    mock_proc = MagicMock()
    fcp._proc = mock_proc
    fcp.stop()
    mock_proc.terminate.assert_called_once()
