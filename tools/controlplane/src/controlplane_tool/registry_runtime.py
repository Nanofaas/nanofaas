"""
registry_runtime.py

Helpers for local container image registry naming conventions
used across k3s and VM-backed E2E scenarios.
"""
from __future__ import annotations

from pathlib import Path
import os
import platform
import time

from controlplane_tool.scenario_runtime import wait_for_http_ok
from controlplane_tool.shell_backend import ShellBackend, ShellExecutionResult, SubprocessShell
from controlplane_tool.tool_settings import ToolSettings


def default_registry_url() -> str:
    value = ToolSettings().nanofaas_tool_registry_url.strip()
    return value or LocalRegistry.DEFAULT_ADDRESS


def set_registry_url(url: str) -> str:
    normalized = url.rstrip("/")
    os.environ["NANOFAAS_TOOL_REGISTRY_URL"] = normalized
    return normalized


def _docker_desktop_candidates() -> list[Path]:
    return [Path("/Applications/Docker.app"), Path.home() / "Applications/Docker.app"]


def _docker_desktop_is_available() -> bool:
    return any(candidate.exists() for candidate in _docker_desktop_candidates())


def _run_docker_desktop(shell: ShellBackend) -> None:
    if platform.system() != "Darwin":
        return
    if not _docker_desktop_is_available():
        return
    shell.run(["open", "-a", "Docker"], dry_run=False)


def _docker_info_ready(shell: ShellBackend) -> bool:
    try:
        return shell.run(["docker", "info"], dry_run=False).return_code == 0
    except FileNotFoundError:
        return False


def _wait_for_docker(shell: ShellBackend, *, attempts: int = 60, interval_seconds: float = 1.0) -> bool:
    for _ in range(attempts):
        if _docker_info_ready(shell):
            return True
        time.sleep(interval_seconds)
    return False


def _registry_port(registry: str) -> str:
    normalized = registry.rstrip("/")
    host, separator, port = normalized.rpartition(":")
    if not separator or not host:
        raise ValueError(f"Invalid registry address: {registry}")
    return port


def ensure_local_registry(
    *,
    registry: str | None = None,
    container_name: str = "nanofaas-e2e-registry",
    shell: ShellBackend | None = None,
) -> ShellExecutionResult:
    runtime_shell = shell or SubprocessShell()
    registry_url = (registry or default_registry_url()).rstrip("/")
    port = _registry_port(registry_url)

    if not _docker_info_ready(runtime_shell):
        _run_docker_desktop(runtime_shell)
    if not _wait_for_docker(runtime_shell):
        raise RuntimeError("Docker daemon did not become ready")

    runtime_shell.run(["docker", "rm", "-f", container_name], dry_run=False)
    result = runtime_shell.run(
        [
            "docker",
            "run",
            "-d",
            "--restart",
            "unless-stopped",
            "--name",
            container_name,
            "-p",
            f"{port}:5000",
            "registry:2",
        ],
        dry_run=False,
    )
    if result.return_code != 0:
        return result

    if not wait_for_http_ok(f"http://127.0.0.1:{port}/v2/", max_attempts=40):
        raise RuntimeError(f"Registry did not become ready at {registry_url}")

    set_registry_url(registry_url)
    return result


class LocalRegistry:
    """Convenience wrapper for local Docker registry image naming."""

    DEFAULT_ADDRESS = "localhost:5000"

    def __init__(self, address: str | None = None) -> None:
        self.address = (address or default_registry_url()).rstrip("/")

    def image(self, repo: str, tag: str = "e2e") -> str:
        return f"{self.address}/{repo}:{tag}"

    def control_plane_image(self, tag: str = "e2e") -> str:
        return self.image("nanofaas/control-plane", tag)

    def function_runtime_image(self, tag: str = "e2e") -> str:
        return self.image("nanofaas/function-runtime", tag)

    @staticmethod
    def split_image_ref(image: str) -> tuple[str, str]:
        repository, separator, tag = image.rpartition(":")
        if not separator:
            return image, "latest"
        return repository, tag
