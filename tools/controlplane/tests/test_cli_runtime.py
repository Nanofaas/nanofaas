"""
Tests for cli_runtime - CliVmRunner and CliHostPlatformRunner (M10).

Gate: Runners must not delegate to deleted shell backends and must guard against
un-bootstrapped VMs. They must use vm_request_from_env() for env-based construction.
"""
from __future__ import annotations

from pathlib import Path

import pytest

import controlplane_tool.cli_runtime as cli_runtime
from controlplane_tool.scenario_components import cli as cli_components
from controlplane_tool.scenario_components.cli import CliComponentContext
from controlplane_tool.cli_vm_runner import CliVmRunner
from controlplane_tool.cli_host_runner import CliHostPlatformRunner
from controlplane_tool.scenario_helpers import (
    function_image as _function_image,
    selected_functions as _selected_functions,
)
from controlplane_tool.vm_models import VmRequest


def _make_vm_request() -> VmRequest:
    return VmRequest(lifecycle="multipass", name="test-vm")


# ---------------------------------------------------------------------------
# _selected_functions and _function_image (shared helpers in cli_runtime)
# ---------------------------------------------------------------------------

def test_cli_selected_functions_returns_echo_test_when_none() -> None:
    assert _selected_functions(None) == ["echo-test"]


def test_cli_selected_functions_uses_custom_default(monkeypatch) -> None:
    assert _selected_functions(None, default="word-stats") == ["word-stats"]


def _rf(key: str, **kwargs):
    from controlplane_tool.scenario_models import ResolvedFunction

    defaults = dict(family="echo", runtime="java", description="test fn")
    defaults.update(kwargs)
    return ResolvedFunction(key=key, **defaults)


def test_cli_selected_functions_reads_from_resolved() -> None:
    from controlplane_tool.scenario_models import ResolvedScenario

    resolved = ResolvedScenario(
        name="test",
        base_scenario="k3s-junit-curl",
        runtime="java",
        functions=[_rf("fn-a"), _rf("fn-b")],
    )
    assert _selected_functions(resolved) == ["fn-a", "fn-b"]


def test_cli_function_image_returns_default_when_none() -> None:
    assert _function_image("echo-test", None, "fallback:img") == "fallback:img"


def test_cli_function_image_returns_custom_image_from_resolved() -> None:
    from controlplane_tool.scenario_models import ResolvedScenario

    resolved = ResolvedScenario(
        name="test",
        base_scenario="k3s-junit-curl",
        runtime="java",
        functions=[_rf("echo-test", image="custom/echo:v1")],
    )
    assert _function_image("echo-test", resolved, "fallback") == "custom/echo:v1"


# ---------------------------------------------------------------------------
# CliVmRunner
# ---------------------------------------------------------------------------

def test_cli_vm_runner_construction_defaults(tmp_path) -> None:
    vm_req = _make_vm_request()
    runner = CliVmRunner(tmp_path, vm_request=vm_req)
    assert runner.namespace == "nanofaas-e2e"
    assert runner.skip_cli_build is False
    assert runner.runtime == "java"


def test_cli_vm_runner_requires_skip_bootstrap_env(tmp_path, monkeypatch) -> None:
    monkeypatch.delenv("E2E_SKIP_VM_BOOTSTRAP", raising=False)
    vm_req = _make_vm_request()
    runner = CliVmRunner(tmp_path, vm_request=vm_req)
    with pytest.raises(RuntimeError, match="E2E_SKIP_VM_BOOTSTRAP"):
        runner.run()


def test_cli_vm_runner_does_not_subprocess_deleted_shell_backend() -> None:
    """CliVmRunner must not exec the deleted e2e-cli-backend.sh."""
    scripts_lib = Path(__file__).resolve().parents[3] / "scripts" / "lib"
    assert not (scripts_lib / "e2e-cli-backend.sh").exists(), (
        "e2e-cli-backend.sh was not deleted — M10 incomplete"
    )


def test_cli_vm_runner_control_image_uses_local_registry(tmp_path) -> None:
    vm_req = _make_vm_request()
    runner = CliVmRunner(tmp_path, vm_request=vm_req, local_registry="myreg:5001")
    assert runner._control_image.startswith("myreg:5001/")


def test_cli_vm_runner_runtime_image_uses_local_registry(tmp_path) -> None:
    vm_req = _make_vm_request()
    runner = CliVmRunner(tmp_path, vm_request=vm_req, local_registry="myreg:5001")
    assert runner._runtime_image.startswith("myreg:5001/")


def test_cli_runtime_re_exports_dedicated_cli_stack_runner() -> None:
    assert hasattr(cli_runtime, "CliStackRunner")


# ---------------------------------------------------------------------------
# CliHostPlatformRunner
# ---------------------------------------------------------------------------

def test_cli_host_platform_runner_construction_defaults(tmp_path) -> None:
    vm_req = _make_vm_request()
    runner = CliHostPlatformRunner(tmp_path, vm_request=vm_req)
    assert runner.namespace == "nanofaas-host-cli-e2e"
    assert runner.skip_build is False
    assert runner.skip_cli_build is False


def test_cli_host_platform_runner_requires_skip_bootstrap_env(tmp_path, monkeypatch) -> None:
    monkeypatch.delenv("E2E_SKIP_VM_BOOTSTRAP", raising=False)
    vm_req = _make_vm_request()
    runner = CliHostPlatformRunner(tmp_path, vm_request=vm_req)
    with pytest.raises(RuntimeError, match="E2E_SKIP_VM_BOOTSTRAP"):
        runner.run()


def test_cli_host_platform_runner_deleted_shell_backend_is_gone() -> None:
    """The deleted e2e-cli-host-backend.sh must not exist on disk (M10 gate)."""
    scripts_lib = Path(__file__).resolve().parents[3] / "scripts" / "lib"
    assert not (scripts_lib / "e2e-cli-host-backend.sh").exists(), (
        "e2e-cli-host-backend.sh was not deleted — M10 incomplete"
    )


def test_cli_host_platform_runner_resolves_external_host_from_vm_request(tmp_path) -> None:
    vm_req = VmRequest(lifecycle="external", host="10.0.0.10")
    runner = CliHostPlatformRunner(tmp_path, vm_request=vm_req)
    # _resolve_public_host uses vm_request.host for external lifecycle
    import os

    with pytest.MonkeyPatch().context() as mp:
        mp.delenv("E2E_PUBLIC_HOST", raising=False)
        mp.delenv("E2E_VM_HOST", raising=False)
        host = runner._resolve_public_host()
    assert host == "10.0.0.10"


def test_cli_host_platform_runner_uses_shared_platform_primitives(tmp_path) -> None:
    vm_req = _make_vm_request()
    runner = CliHostPlatformRunner(
        tmp_path,
        vm_request=vm_req,
        namespace="test-ns",
        release="test-release",
        local_registry="myreg:5001",
    )
    context = CliComponentContext(
        repo_root=tmp_path,
        release="test-release",
        namespace="test-ns",
        local_registry="myreg:5001",
        resolved_scenario=None,
    )

    assert runner._platform_install_command() == list(
        cli_components.plan_platform_install(context)[0].argv
    )
    assert runner._platform_status_command() == list(
        cli_components.plan_platform_status(context)[0].argv
    )
    assert runner._platform_uninstall_command() == list(
        cli_components._plan_platform_uninstall(context)[0].argv
    )


def test_cli_stack_apply_operation_stages_manifest_before_apply(tmp_path) -> None:
    from controlplane_tool.scenario_models import ResolvedScenario

    resolved = ResolvedScenario(
        name="test",
        base_scenario="cli-stack",
        runtime="java",
        functions=[_rf("echo-test", image="registry.internal/echo-test:v1")],
    )
    context = CliComponentContext(
        repo_root=tmp_path,
        release="test-release",
        namespace="test-ns",
        local_registry="registry.internal:5000",
        resolved_scenario=resolved,
    )

    operation = cli_components.plan_fn_apply_selected(context)[0]

    assert operation.argv[:2] == ("bash", "-lc")
    assert "printf '%s'" in operation.argv[2]
    assert "/tmp/echo-test.json" in operation.argv[2]
    assert "fn apply -f /tmp/echo-test.json" in operation.argv[2]
    assert "NANOFAAS_ENDPOINT" not in operation.env
