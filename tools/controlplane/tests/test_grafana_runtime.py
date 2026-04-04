"""
Tests for grafana_runtime.GrafanaRuntime (M12).

Gate: GrafanaRuntime must guard all operations behind availability checks — no subprocess
calls when docker is missing or the compose file does not exist.
"""
from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock, patch

from controlplane_tool.grafana_runtime import GrafanaRuntime


def _make_runtime(tmp_path: Path, *, with_compose: bool = False) -> GrafanaRuntime:
    if with_compose:
        compose_dir = tmp_path / "experiments" / "grafana"
        compose_dir.mkdir(parents=True)
        (compose_dir / "docker-compose.yml").write_text("version: '3'\n", encoding="utf-8")
    return GrafanaRuntime(tmp_path, prom_url="http://192.168.64.2:30090")


def test_grafana_runtime_is_docker_available_returns_true_when_docker_on_path(tmp_path) -> None:
    rt = _make_runtime(tmp_path)
    with patch("shutil.which", return_value="/usr/bin/docker"):
        assert rt.is_docker_available() is True


def test_grafana_runtime_is_docker_available_returns_false_when_missing(tmp_path) -> None:
    rt = _make_runtime(tmp_path)
    with patch("shutil.which", return_value=None):
        assert rt.is_docker_available() is False


def test_grafana_runtime_is_compose_file_available_returns_true_when_file_exists(tmp_path) -> None:
    rt = _make_runtime(tmp_path, with_compose=True)
    assert rt.is_compose_file_available() is True


def test_grafana_runtime_is_compose_file_available_returns_false_when_missing(tmp_path) -> None:
    rt = _make_runtime(tmp_path, with_compose=False)
    assert rt.is_compose_file_available() is False


def test_grafana_runtime_start_skips_when_docker_unavailable(tmp_path, capsys) -> None:
    rt = _make_runtime(tmp_path, with_compose=True)
    with patch("shutil.which", return_value=None):
        rt.start()
    out = capsys.readouterr().out
    assert "skipping" in out


def test_grafana_runtime_start_skips_when_compose_file_missing(tmp_path, capsys) -> None:
    rt = _make_runtime(tmp_path, with_compose=False)
    with patch("shutil.which", return_value="/usr/bin/docker"):
        rt.start()
    out = capsys.readouterr().out
    assert "skipping" in out


def test_grafana_runtime_start_calls_docker_compose_up(tmp_path) -> None:
    rt = _make_runtime(tmp_path, with_compose=True)
    with patch("shutil.which", return_value="/usr/bin/docker"), \
         patch("subprocess.run") as mock_run:
        rt.start()
    mock_run.assert_called_once()
    cmd = mock_run.call_args[0][0]
    assert "docker" in cmd
    assert "up" in cmd
    assert "-d" in cmd


def test_grafana_runtime_start_passes_prom_url_in_env(tmp_path) -> None:
    rt = _make_runtime(tmp_path, with_compose=True)
    with patch("shutil.which", return_value="/usr/bin/docker"), \
         patch("subprocess.run") as mock_run:
        rt.start()
    env = mock_run.call_args[1]["env"]
    assert env.get("PROM_URL") == "http://192.168.64.2:30090"


def test_grafana_runtime_stop_is_noop_when_docker_unavailable(tmp_path) -> None:
    rt = _make_runtime(tmp_path, with_compose=True)
    with patch("shutil.which", return_value=None), \
         patch("subprocess.run") as mock_run:
        rt.stop()
    mock_run.assert_not_called()


def test_grafana_runtime_stop_calls_docker_compose_down(tmp_path) -> None:
    rt = _make_runtime(tmp_path, with_compose=True)
    with patch("shutil.which", return_value="/usr/bin/docker"), \
         patch("subprocess.run") as mock_run:
        rt.stop()
    mock_run.assert_called_once()
    cmd = mock_run.call_args[0][0]
    assert "down" in cmd


def test_grafana_runtime_stop_uses_check_false(tmp_path) -> None:
    rt = _make_runtime(tmp_path, with_compose=True)
    with patch("shutil.which", return_value="/usr/bin/docker"), \
         patch("subprocess.run") as mock_run:
        rt.stop()
    assert mock_run.call_args[1].get("check") is False
