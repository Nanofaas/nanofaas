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


def test_cold_start_experiment_script_resolves_shared_helpers() -> None:
    cold_start = (REPO_ROOT / "experiments" / "e2e-cold-start-metrics.sh").read_text(encoding="utf-8")
    assert 'REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}' in cold_start
    assert 'e2e_build_control_plane_artifacts "${REMOTE_DIR}"' in cold_start
    assert 'e2e_build_function_runtime_image "${REMOTE_DIR}" "${RUNTIME_IMAGE}"' in cold_start


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


# M10: CLI backends deleted; Python CliVmRunner/CliHostPlatformRunner replace them.
def test_cli_shell_backend_is_deleted() -> None:
    assert not (SCRIPTS_DIR / "lib" / "e2e-cli-backend.sh").exists(), (
        "e2e-cli-backend.sh still exists — delete it after Python path is green (M10)"
    )


def test_cli_host_shell_backend_is_deleted() -> None:
    assert not (SCRIPTS_DIR / "lib" / "e2e-cli-host-backend.sh").exists(), (
        "e2e-cli-host-backend.sh still exists — delete it after Python path is green (M10)"
    )


# M11: k3s-curl/helm-stack backends deleted; Python K3sCurlRunner/HelmStackRunner replace them.
def test_k3s_curl_shell_backend_is_deleted() -> None:
    assert not (SCRIPTS_DIR / "lib" / "e2e-k3s-curl-backend.sh").exists(), (
        "e2e-k3s-curl-backend.sh still exists — delete it after Python path is green (M11)"
    )


def test_helm_stack_shell_backend_is_deleted() -> None:
    assert not (SCRIPTS_DIR / "lib" / "e2e-helm-stack-backend.sh").exists(), (
        "e2e-helm-stack-backend.sh still exists — delete it after Python path is green (M11)"
    )


def test_k3s_common_shell_helper_is_deleted() -> None:
    assert not (SCRIPTS_DIR / "lib" / "e2e-k3s-common.sh").exists(), (
        "e2e-k3s-common.sh still exists — delete it after Python path is green (M11)"
    )
