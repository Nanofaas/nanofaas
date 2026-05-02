"""
Tests for registry_runtime.LocalRegistry (M11).

Gate: Image naming conventions must be stable and consistent with what K3sCurlRunner
pushes to the local registry and what Helm chart values expect.
"""
from __future__ import annotations

from pathlib import Path
import os
from unittest.mock import patch

from controlplane_tool.registry_runtime import ensure_local_registry
from controlplane_tool.registry_runtime import LocalRegistry
from controlplane_tool.shell_backend import ShellBackend, ShellExecutionResult


def test_local_registry_default_address() -> None:
    registry = LocalRegistry()
    assert registry.address == "localhost:5000"


def test_local_registry_prefers_tool_settings_registry_url(monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_TOOL_REGISTRY_URL", "localhost:5001")
    registry = LocalRegistry()
    assert registry.address == "localhost:5001"


def test_local_registry_strips_trailing_slash() -> None:
    registry = LocalRegistry("localhost:5000/")
    assert registry.address == "localhost:5000"


def test_local_registry_image_constructs_full_ref() -> None:
    registry = LocalRegistry("localhost:5000")
    assert registry.image("nanofaas/control-plane", "e2e") == "localhost:5000/nanofaas/control-plane:e2e"


def test_local_registry_image_uses_e2e_tag_by_default() -> None:
    registry = LocalRegistry("localhost:5000")
    assert registry.image("nanofaas/control-plane") == "localhost:5000/nanofaas/control-plane:e2e"


def test_control_plane_image_uses_canonical_repo() -> None:
    registry = LocalRegistry("localhost:5000")
    assert registry.control_plane_image() == "localhost:5000/nanofaas/control-plane:e2e"


def test_function_runtime_image_uses_canonical_repo() -> None:
    registry = LocalRegistry("localhost:5000")
    assert registry.function_runtime_image() == "localhost:5000/nanofaas/function-runtime:e2e"


def test_local_registry_supports_custom_tag() -> None:
    registry = LocalRegistry("myregistry.local:5001")
    assert registry.control_plane_image("latest") == "myregistry.local:5001/nanofaas/control-plane:latest"
    assert registry.function_runtime_image("rc1") == "myregistry.local:5001/nanofaas/function-runtime:rc1"


def test_local_registry_address_used_directly_in_image() -> None:
    registry = LocalRegistry("192.168.64.2:5000")
    img = registry.image("my/repo", "tag")
    assert img.startswith("192.168.64.2:5000/")


def test_registry_playbooks_split_container_runtime_and_k3s_configuration() -> None:
    repo_root = Path(__file__).resolve().parents[3]
    ensure_registry = (repo_root / "ops/ansible/playbooks/ensure-registry.yml").read_text(encoding="utf-8")
    configure_k3s = (repo_root / "ops/ansible/playbooks/configure-k3s-registry.yml").read_text(
        encoding="utf-8"
    )

    assert "/etc/rancher/k3s/registries.yaml" not in ensure_registry
    assert "docker run" not in configure_k3s


def test_ensure_local_registry_sets_environment_and_runs_docker_desktop() -> None:
    class FakeShell(ShellBackend):
        def __init__(self) -> None:
            self.commands: list[list[str]] = []
            self.docker_info_calls = 0

        def run(self, command, *, cwd=None, env=None, dry_run=False):  # noqa: ANN001
            _ = cwd, env, dry_run
            self.commands.append(command)
            if command == ["docker", "info"]:
                self.docker_info_calls += 1
                return ShellExecutionResult(command=command, return_code=0 if self.docker_info_calls > 1 else 1)
            return ShellExecutionResult(command=command, return_code=0)

    shell = FakeShell()
    original_registry_url = os.environ.get("NANOFAAS_TOOL_REGISTRY_URL")

    try:
        with patch("controlplane_tool.registry_runtime.platform.system", return_value="Darwin"), patch(
            "controlplane_tool.registry_runtime._docker_desktop_is_available", return_value=True
        ), patch("controlplane_tool.registry_runtime.wait_for_http_ok", return_value=True):
            result = ensure_local_registry(registry="localhost:5001", shell=shell)

        assert result.return_code == 0
        assert shell.commands == [
            ["docker", "info"],
            ["open", "-a", "Docker"],
            ["docker", "info"],
            ["docker", "rm", "-f", "nanofaas-e2e-registry"],
            ["docker", "run", "-d", "--restart", "unless-stopped", "--name", "nanofaas-e2e-registry", "-p", "5001:5000", "registry:2"],
        ]
        assert os.environ["NANOFAAS_TOOL_REGISTRY_URL"] == "localhost:5001"
    finally:
        if original_registry_url is None:
            os.environ.pop("NANOFAAS_TOOL_REGISTRY_URL", None)
        else:
            os.environ["NANOFAAS_TOOL_REGISTRY_URL"] = original_registry_url
