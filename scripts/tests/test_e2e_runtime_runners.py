import os
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = REPO_ROOT / "scripts"


def read_script(name: str) -> str:
    return (SCRIPTS_DIR / name).read_text(encoding="utf-8")


def run_script(name: str, *args: str, env: dict[str, str] | None = None) -> str:
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)

    proc = subprocess.run(
        ["bash", str(SCRIPTS_DIR / name), *args],
        text=True,
        capture_output=True,
        check=True,
        env=merged_env,
    )
    return f"{proc.stdout}\n{proc.stderr}"


def test_legacy_runner_scripts_delegate_to_controlplane_e2e_run() -> None:
    wrappers = {
        "e2e.sh": "docker",
        "e2e-buildpack.sh": "buildpack",
        "e2e-container-local.sh": "container-local",
        "e2e-k3s-curl.sh": "k3s-curl",
        "e2e-k8s-vm.sh": "k8s-vm",
        "e2e-k3s-helm.sh": "helm-stack",
    }
    legacy_markers = (
        "e2e_install_k3s",
        "e2e_sync_project_to_vm",
        "sdk use java",
        "trap cleanup EXIT",
        "SUITES=(",
    )

    for script_name, scenario_name in wrappers.items():
        script = read_script(script_name)
        assert f'controlplane.sh" e2e run {scenario_name} "$@"' in script
        for marker in legacy_markers:
            assert marker not in script, script_name


def test_e2e_all_script_delegates_to_tool_all_command() -> None:
    script = read_script("e2e-all.sh")
    assert 'controlplane.sh" e2e all "$@"' in script
    assert "SUITES=(" not in script


def test_vm_provisioning_contract_uses_ansible_in_common_library() -> None:
    common = (SCRIPTS_DIR / "lib" / "e2e-k3s-common.sh").read_text(encoding="utf-8")
    assert "e2e_ensure_ansible()" in common
    assert "e2e_run_ansible_playbook()" in common
    assert "playbooks/provision-base.yml" in common
    assert "playbooks/provision-k3s.yml" in common
    assert "playbooks/configure-registry.yml" in common


def test_host_facing_helpers_live_in_common_library() -> None:
    common = (SCRIPTS_DIR / "lib" / "e2e-k3s-common.sh").read_text(encoding="utf-8")
    assert "e2e_export_kubeconfig_to_host()" in common
    assert "e2e_get_public_host()" in common
    assert 'public_host=$(e2e_get_public_host)' in common


def test_experiment_scripts_resolve_host_endpoints_through_shared_helpers() -> None:
    loadtest = (REPO_ROOT / "experiments" / "e2e-loadtest.sh").read_text(encoding="utf-8")
    assert "e2e_resolve_nanofaas_url 30080" in loadtest
    assert "e2e_resolve_nanofaas_url 30090" in loadtest

    loadtest_registry = (REPO_ROOT / "experiments" / "e2e-loadtest-registry.sh").read_text(encoding="utf-8")
    assert 'REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}' in loadtest_registry
    assert 'REMOTE_HELM_DIR="${REMOTE_DIR}/helm/nanofaas"' in loadtest_registry
    assert 'e2e_sync_project_to_vm "${PROJECT_ROOT}" "${VM_NAME}" "${REMOTE_DIR}"' in loadtest_registry
    assert "e2e_resolve_nanofaas_url 30090" in loadtest_registry

    cold_start = (REPO_ROOT / "experiments" / "e2e-cold-start-metrics.sh").read_text(encoding="utf-8")
    assert 'REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}' in cold_start
    assert 'e2e_build_control_plane_artifacts "${REMOTE_DIR}"' in cold_start
    assert 'e2e_build_function_runtime_image "${REMOTE_DIR}" "${RUNTIME_IMAGE}"' in cold_start


def test_e2e_all_wrapper_passes_through_filters_and_dry_run() -> None:
    output = run_script("e2e-all.sh", "--only", "k3s-curl,k8s-vm", "--dry-run")
    assert "Scenario: k3s-curl" in output
    assert "Scenario: k8s-vm" in output
    assert "Scenario: docker" not in output


def test_vm_wrapper_runs_named_scenario_in_dry_run() -> None:
    output = run_script("e2e-k8s-vm.sh", "--dry-run")
    assert "Scenario: k8s-vm" in output
    assert "Step 1:" in output


# M9: container-local and deploy-host backends are migrated to Python.
# The remaining shell backends (k3s-curl, cli, cli-host, helm-stack)
# still source scenario-manifest.sh until M10/M11.
def test_remaining_vm_backends_still_source_scenario_manifest_helpers() -> None:
    backend_names = (
        "e2e-k3s-curl-backend.sh",
        "e2e-cli-backend.sh",
        "e2e-cli-host-backend.sh",
        "e2e-helm-stack-backend.sh",
    )

    for backend_name in backend_names:
        script = (SCRIPTS_DIR / "lib" / backend_name).read_text(encoding="utf-8")
        assert "scenario-manifest.sh" in script
        assert "NANOFAAS_SCENARIO_PATH" in script


# M9: container-local backend is deleted; Python replaces it.
def test_container_local_shell_backend_is_deleted() -> None:
    assert not (SCRIPTS_DIR / "lib" / "e2e-container-local-backend.sh").exists(), (
        "e2e-container-local-backend.sh still exists — delete it after Python path is green (M9)"
    )


# M9: deploy-host backend is deleted; Python replaces it.
def test_deploy_host_shell_backend_is_deleted() -> None:
    assert not (SCRIPTS_DIR / "lib" / "e2e-deploy-host-backend.sh").exists(), (
        "e2e-deploy-host-backend.sh still exists — delete it after Python path is green (M9)"
    )


# M9: scenario-manifest.sh is deleted; Python uses ResolvedScenario directly.
def test_scenario_manifest_shell_helper_is_deleted() -> None:
    assert not (SCRIPTS_DIR / "lib" / "scenario-manifest.sh").exists(), (
        "scenario-manifest.sh still exists — delete it after Python path is green (M9)"
    )


def test_cli_backends_remain_concrete_workflows() -> None:
    cli_backend = (SCRIPTS_DIR / "lib" / "e2e-cli-backend.sh").read_text(encoding="utf-8")
    host_backend = (SCRIPTS_DIR / "lib" / "e2e-cli-host-backend.sh").read_text(encoding="utf-8")

    assert "nanofaas fn apply" in cli_backend
    assert "platform install" in host_backend


def test_cli_backend_iterates_all_selected_functions() -> None:
    script = (SCRIPTS_DIR / "lib" / "e2e-cli-backend.sh").read_text(encoding="utf-8")

    assert "scenario_selected_functions" in script
    assert "for function_key in" in script or "for FUNCTION_NAME in" in script


def test_k3s_curl_backend_iterates_all_selected_functions() -> None:
    script = (SCRIPTS_DIR / "lib" / "e2e-k3s-curl-backend.sh").read_text(encoding="utf-8")

    assert "scenario_selected_functions" in script
    assert "for function_key in" in script or "for FUNCTION_NAME in" in script
