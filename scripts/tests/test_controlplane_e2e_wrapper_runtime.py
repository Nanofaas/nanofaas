import os
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = REPO_ROOT / "scripts"


def run_script(name: str) -> str:
    proc = subprocess.run(
        ["bash", str(SCRIPTS_DIR / name), "--dry-run"],
        text=True,
        capture_output=True,
        check=True,
        env=os.environ.copy(),
    )
    return f"{proc.stdout}\n{proc.stderr}"


# M9: container-local now uses Python runtime — no shell backend in dry-run output
def test_container_local_wrapper_dry_run_uses_python_runner() -> None:
    output = run_script("e2e-container-local.sh")
    assert "e2e run container-local" in output
    assert "e2e-container-local-backend.sh" not in output



# M13: the unified k3s wrapper routes to the fused scenario and not to any legacy backend.
def test_k3s_junit_curl_wrapper_dry_run_routes_to_unified_scenario() -> None:
    output = run_script("e2e-k3s-junit-curl.sh")
    assert "e2e-k3s-curl-backend.sh" not in output
    assert "Scenario: k3s-junit-curl" in output
    assert "K8sE2eTest" in output


# M11: helm-stack backend deleted; the dry-run step must NOT show the old backend
def test_helm_stack_wrapper_dry_run_no_longer_uses_shell_backend() -> None:
    output = run_script("e2e-k3s-helm.sh")
    assert "e2e-helm-stack-backend.sh" not in output
    assert "Scenario: helm-stack" in output


def test_k3s_junit_curl_wrapper_dry_run_shows_manifest_property() -> None:
    output = run_script("e2e-k3s-junit-curl.sh")
    assert "nanofaas.e2e.scenarioManifest" in output
