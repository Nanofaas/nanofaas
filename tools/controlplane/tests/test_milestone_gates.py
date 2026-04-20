"""
Milestone gate tests for the controlplane legacy retirement plan (M8-M13).

Each test in this file acts as a non-negotiable contract check for a specific
milestone. A failure here means the milestone's contract was broken or was never
fully implemented.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
from typer.testing import CliRunner

from controlplane_tool.main import app

runner = CliRunner()

# ---------------------------------------------------------------------------
# M8 gate: Python tooling scaffold and canonical CLI entry point
# ---------------------------------------------------------------------------

def test_m8_controlplane_sh_invokes_uv_run_locked() -> None:
    """scripts/controlplane.sh must invoke `uv run --project tools/controlplane --locked`."""
    script = (Path(__file__).resolve().parents[3] / "scripts" / "controlplane.sh").read_text()
    assert "uv run --project tools/controlplane --locked" in script


def test_m8_main_cli_has_all_top_level_groups() -> None:
    result = runner.invoke(app, ["--help"])
    assert result.exit_code == 0
    for group in ("e2e", "cli-test", "loadtest", "vm"):
        assert group in result.stdout, f"Missing CLI group: {group!r}"


def test_m8_tools_controlplane_pyproject_exists() -> None:
    pyproject = Path(__file__).resolve().parents[1] / "pyproject.toml"
    assert pyproject.exists()
    content = pyproject.read_text()
    assert "controlplane-tool" in content


# ---------------------------------------------------------------------------
# M9 gate: Container-local and deploy-host Python backends
# ---------------------------------------------------------------------------

def test_m9_container_local_backend_shell_is_deleted() -> None:
    scripts_lib = Path(__file__).resolve().parents[3] / "scripts" / "lib"
    assert not (scripts_lib / "e2e-container-local-backend.sh").exists()


def test_m9_deploy_host_backend_shell_is_deleted() -> None:
    scripts_lib = Path(__file__).resolve().parents[3] / "scripts" / "lib"
    assert not (scripts_lib / "e2e-deploy-host-backend.sh").exists()


def test_m9_scenario_manifest_shell_is_deleted() -> None:
    scripts_lib = Path(__file__).resolve().parents[3] / "scripts" / "lib"
    assert not (scripts_lib / "scenario-manifest.sh").exists()


def test_m9_local_e2e_commands_module_is_deleted() -> None:
    module = Path(__file__).resolve().parents[1] / "src" / "controlplane_tool" / "local_e2e_commands.py"
    assert not module.exists()


def test_m9_local_e2e_group_is_removed_from_main_cli() -> None:
    result = runner.invoke(app, ["--help"])
    assert "local-e2e" not in result.stdout


def test_m9_container_local_e2e_runner_is_importable() -> None:
    from controlplane_tool.local_e2e_runner import ContainerLocalE2eRunner  # noqa: F401
    from controlplane_tool.local_e2e_runner import DeployHostE2eRunner  # noqa: F401


# ---------------------------------------------------------------------------
# M10 gate: CLI E2E backends migrated to Python
# ---------------------------------------------------------------------------

def test_m10_cli_shell_backend_is_deleted() -> None:
    scripts_lib = Path(__file__).resolve().parents[3] / "scripts" / "lib"
    assert not (scripts_lib / "e2e-cli-backend.sh").exists()


def test_m10_cli_host_shell_backend_is_deleted() -> None:
    scripts_lib = Path(__file__).resolve().parents[3] / "scripts" / "lib"
    assert not (scripts_lib / "e2e-cli-host-backend.sh").exists()


def test_m10_cli_vm_runner_is_importable() -> None:
    from controlplane_tool.cli_runtime import CliVmRunner  # noqa: F401
    from controlplane_tool.cli_runtime import CliHostPlatformRunner  # noqa: F401


def test_m10_cli_e2e_group_is_removed_from_main_cli() -> None:
    result = runner.invoke(app, ["--help"])
    assert "cli-e2e" not in result.stdout


def test_m10_cli_vm_runner_shell_backends_are_deleted() -> None:
    """The deleted shell backends must not exist on disk."""
    scripts_lib = Path(__file__).resolve().parents[3] / "scripts" / "lib"
    assert not (scripts_lib / "e2e-cli-backend.sh").exists()
    assert not (scripts_lib / "e2e-cli-host-backend.sh").exists()


# ---------------------------------------------------------------------------
# M11 gate: K3s/Helm shell backends migrated to Python
# ---------------------------------------------------------------------------

def test_m11_k3s_curl_backend_shell_is_deleted() -> None:
    scripts_lib = Path(__file__).resolve().parents[3] / "scripts" / "lib"
    assert not (scripts_lib / "e2e-k3s-curl-backend.sh").exists()


def test_m11_helm_stack_backend_shell_is_deleted() -> None:
    scripts_lib = Path(__file__).resolve().parents[3] / "scripts" / "lib"
    assert not (scripts_lib / "e2e-helm-stack-backend.sh").exists()


def test_m11_k3s_common_shell_is_deleted() -> None:
    scripts_lib = Path(__file__).resolve().parents[3] / "scripts" / "lib"
    assert not (scripts_lib / "e2e-k3s-common.sh").exists()


def test_m11_k3s_runtime_is_importable() -> None:
    from controlplane_tool.k3s_runtime import K3sCurlRunner, HelmStackRunner  # noqa: F401


def test_m11_k3s_e2e_group_is_removed_from_main_cli() -> None:
    result = runner.invoke(app, ["--help"])
    assert "k3s-e2e" not in result.stdout


def test_m11_ansible_adapter_has_provision_contract() -> None:
    from controlplane_tool.ansible_adapter import AnsibleAdapter

    adapter = AnsibleAdapter.__new__(AnsibleAdapter)
    assert hasattr(adapter, "provision_base")
    assert hasattr(adapter, "provision_k3s")
    assert hasattr(adapter, "configure_registry")


def test_m11_vm_orchestrator_has_lifecycle_contract() -> None:
    from controlplane_tool.vm_adapter import VmOrchestrator

    orch = VmOrchestrator.__new__(VmOrchestrator)
    assert hasattr(orch, "ensure_running")
    assert hasattr(orch, "teardown")
    assert hasattr(orch, "sync_project")
    assert hasattr(orch, "remote_exec")


def test_m11_helm_stack_runner_does_not_call_deleted_registry_script() -> None:
    """M12 deleted e2e-loadtest-registry.sh — it must not exist on disk."""
    experiments = Path(__file__).resolve().parents[3] / "experiments"
    assert not (experiments / "e2e-loadtest-registry.sh").exists()


# ---------------------------------------------------------------------------
# M12 gate: Loadtest shell ownership retired
# ---------------------------------------------------------------------------

def test_m12_loadtest_registry_experiment_script_is_deleted() -> None:
    experiments = Path(__file__).resolve().parents[3] / "experiments"
    assert not (experiments / "e2e-loadtest.sh").exists()
    assert not (experiments / "e2e-loadtest-registry.sh").exists()


def test_m12_e2e_loadtest_sh_routes_to_python_runner() -> None:
    script = (Path(__file__).resolve().parents[3] / "scripts" / "e2e-loadtest.sh").read_text()
    assert "Compatibility wrapper" in script
    assert "controlplane.sh" in script
    assert "loadtest run" in script
    assert "experiments/e2e-loadtest.sh" not in script


def test_m12_grafana_runtime_is_importable() -> None:
    from controlplane_tool.grafana_runtime import GrafanaRuntime  # noqa: F401


# ---------------------------------------------------------------------------
# M13 gate: Full migration — scripts/lib empty, all CLI groups present
# ---------------------------------------------------------------------------

def test_m13_scripts_lib_is_empty() -> None:
    lib_dir = Path(__file__).resolve().parents[3] / "scripts" / "lib"
    if lib_dir.exists():
        remaining = [f for f in lib_dir.iterdir() if f.name != "__pycache__"]
        assert remaining == [], (
            "scripts/lib/ still has files: " + ", ".join(f.name for f in remaining)
        )


def test_m13_all_cli_command_groups_registered() -> None:
    result = runner.invoke(app, ["--help"])
    assert result.exit_code == 0
    for group in ("e2e", "cli-test", "loadtest", "vm", "functions", "tui"):
        assert group in result.stdout, f"CLI group {group!r} missing from help"


def test_m13_all_legacy_wrappers_are_compatibility_shims() -> None:
    scripts_dir = Path(__file__).resolve().parents[3] / "scripts"
    shims = {
        "control-plane-build.sh",
        "controlplane-tool.sh",
        "e2e.sh",
        "e2e-all.sh",
        "e2e-buildpack.sh",
        "e2e-container-local.sh",
        "e2e-k3s-junit-curl.sh",
        "e2e-k3s-helm.sh",
        "e2e-cli.sh",
        "e2e-cli-host-platform.sh",
        "e2e-cli-deploy-host.sh",
    }
    for name in shims:
        script = (scripts_dir / name).read_text(encoding="utf-8")
        assert "wrapper" in script.lower(), f"{name} is not documented as a wrapper"
        assert "controlplane.sh" in script, f"{name} does not delegate to controlplane.sh"
        assert "gradlew" not in script, f"{name} still calls gradlew directly"
