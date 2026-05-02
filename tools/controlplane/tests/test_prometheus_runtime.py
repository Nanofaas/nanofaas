from __future__ import annotations

from pathlib import Path
import subprocess

from controlplane_tool.prometheus_runtime import PrometheusRuntimeManager, PrometheusSession
from controlplane_tool.shell_backend import ShellExecutionResult


def test_ensure_prometheus_uses_existing_endpoint(tmp_path: Path, monkeypatch) -> None:
    manager = PrometheusRuntimeManager(
        repo_root=tmp_path,
        preferred_url="http://prometheus.local:9090",
    )
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)

    monkeypatch.setattr(
        manager,
        "_is_ready",
        lambda base_url: base_url == "http://prometheus.local:9090",
    )

    def _unexpected_docker(*args, **kwargs):  # noqa: ARG001
        raise AssertionError("docker must not be invoked when an existing endpoint is ready")

    monkeypatch.setattr(manager, "_docker", _unexpected_docker)

    session = manager.ensure_available(run_dir=run_dir)

    assert session.url == "http://prometheus.local:9090"
    assert session.owned_container_name is None


def test_ensure_prometheus_starts_container_when_unavailable(
    tmp_path: Path, monkeypatch
) -> None:
    manager = PrometheusRuntimeManager(repo_root=tmp_path)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)

    commands: list[list[str]] = []

    def _fake_docker(args: list[str], check: bool):  # noqa: ARG001
        commands.append(args)
        if args[:2] == ["image", "inspect"]:
            return subprocess.CompletedProcess(args=args, returncode=1, stdout="", stderr="")
        return subprocess.CompletedProcess(args=args, returncode=0, stdout="", stderr="")

    monkeypatch.setattr(manager, "_docker", _fake_docker)
    monkeypatch.setattr(manager, "_is_ready", lambda base_url: False)  # noqa: ARG005
    monkeypatch.setattr(manager, "_pick_local_port", lambda: 19090)
    monkeypatch.setattr(manager, "_wait_ready", lambda base_url: True)  # noqa: ARG005

    session = manager.ensure_available(run_dir=run_dir)

    assert session.url == "http://127.0.0.1:19090"
    assert session.owned_container_name is not None
    assert ["image", "inspect", "prom/prometheus"] in commands
    assert ["pull", "prom/prometheus"] in commands
    assert any(command and command[0] == "run" for command in commands)
    config_path = run_dir / "metrics" / "prometheus" / "prometheus.yml"
    assert config_path.exists()


def test_ensure_prometheus_accepts_shell_execution_result_from_docker_probe(
    tmp_path: Path, monkeypatch
) -> None:
    manager = PrometheusRuntimeManager(repo_root=tmp_path)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)

    commands: list[list[str]] = []

    def _fake_docker(args: list[str], check: bool):  # noqa: ARG001
        commands.append(args)
        if args[:2] == ["image", "inspect"]:
            return ShellExecutionResult(command=["docker", *args], return_code=1)
        return ShellExecutionResult(command=["docker", *args], return_code=0)

    monkeypatch.setattr(manager, "_docker", _fake_docker)
    monkeypatch.setattr(manager, "_is_ready", lambda base_url: False)  # noqa: ARG005
    monkeypatch.setattr(manager, "_pick_local_port", lambda: 19092)
    monkeypatch.setattr(manager, "_wait_ready", lambda base_url: True)  # noqa: ARG005

    session = manager.ensure_available(run_dir=run_dir)

    assert session.url == "http://127.0.0.1:19092"
    assert ["image", "inspect", "prom/prometheus"] in commands
    assert ["pull", "prom/prometheus"] in commands


def test_cleanup_stops_owned_container_only(tmp_path: Path, monkeypatch) -> None:
    manager = PrometheusRuntimeManager(repo_root=tmp_path)
    commands: list[list[str]] = []

    def _fake_docker(args: list[str], check: bool):  # noqa: ARG001
        commands.append(args)
        return subprocess.CompletedProcess(args=args, returncode=0, stdout="", stderr="")

    monkeypatch.setattr(manager, "_docker", _fake_docker)

    manager.cleanup(PrometheusSession(url="http://127.0.0.1:9090", owned_container_name=None))
    assert commands == []

    manager.cleanup(
        PrometheusSession(
            url="http://127.0.0.1:19090",
            owned_container_name="controlplane-tool-prom-123",
        )
    )
    assert commands == [["rm", "-f", "controlplane-tool-prom-123"]]


def test_legacy_scrape_url_is_ignored_as_prometheus_base(tmp_path: Path, monkeypatch) -> None:
    manager = PrometheusRuntimeManager(
        repo_root=tmp_path,
        preferred_url="http://localhost:8081/actuator/prometheus",
    )
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)

    monkeypatch.setattr(
        manager,
        "_is_ready",
        lambda base_url: base_url == "http://127.0.0.1:9090",
    )

    def _unexpected_docker(*args, **kwargs):  # noqa: ARG001
        raise AssertionError("docker must not be invoked when default endpoint is ready")

    monkeypatch.setattr(manager, "_docker", _unexpected_docker)

    session = manager.ensure_available(run_dir=run_dir)

    assert session.url == "http://127.0.0.1:9090"


def test_container_mount_uses_absolute_bind_source_for_relative_run_dir(
    tmp_path: Path, monkeypatch
) -> None:
    manager = PrometheusRuntimeManager(repo_root=tmp_path)
    # Reproduces live behavior where pipeline passes a relative run_dir.
    run_dir = Path("tooling/runs/relative-prometheus-test")
    if run_dir.exists():
        raise AssertionError(f"unexpected pre-existing path: {run_dir}")

    commands: list[list[str]] = []

    def _fake_docker(args: list[str], check: bool):  # noqa: ARG001
        commands.append(args)
        if args[:2] == ["image", "inspect"]:
            return subprocess.CompletedProcess(args=args, returncode=1, stdout="", stderr="")
        return subprocess.CompletedProcess(args=args, returncode=0, stdout="", stderr="")

    monkeypatch.setattr(manager, "_docker", _fake_docker)
    monkeypatch.setattr(manager, "_is_ready", lambda base_url: False)  # noqa: ARG005
    monkeypatch.setattr(manager, "_pick_local_port", lambda: 19091)
    monkeypatch.setattr(manager, "_wait_ready", lambda base_url: True)  # noqa: ARG005

    try:
        manager.ensure_available(run_dir=run_dir)
    finally:
        if run_dir.exists():
            import shutil

            shutil.rmtree(run_dir, ignore_errors=True)

    run_command = next(command for command in commands if command and command[0] == "run")
    assert "--mount" in run_command
    mount_spec = run_command[run_command.index("--mount") + 1]
    assert "type=bind" in mount_spec
    assert "src=/" in mount_spec
