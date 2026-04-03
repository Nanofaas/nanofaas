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


def test_container_local_wrapper_dry_run_leads_to_real_backend() -> None:
    output = run_script("e2e-container-local.sh")
    assert "e2e-container-local-backend.sh" in output
    assert "echo container-local verification workflow" not in output


def test_cli_wrapper_dry_run_leads_to_real_backend() -> None:
    output = run_script("e2e-cli.sh")
    assert "e2e-cli-backend.sh" in output


def test_cli_host_platform_wrapper_dry_run_leads_to_real_backend() -> None:
    output = run_script("e2e-cli-host-platform.sh")
    assert "e2e-cli-host-backend.sh" in output


def test_cli_deploy_host_wrapper_dry_run_leads_to_real_backend() -> None:
    output = run_script("e2e-cli-deploy-host.sh")
    assert "e2e-deploy-host-backend.sh" in output


def test_k3s_curl_wrapper_dry_run_leads_to_real_backend() -> None:
    output = run_script("e2e-k3s-curl.sh")
    assert "e2e-k3s-curl-backend.sh" in output
    assert "K8sE2eTest" not in output


def test_helm_stack_wrapper_dry_run_leads_to_real_backend() -> None:
    output = run_script("e2e-k3s-helm.sh")
    assert "e2e-helm-stack-backend.sh" in output


def test_k8s_vm_wrapper_dry_run_shows_manifest_property() -> None:
    output = run_script("e2e-k8s-vm.sh")
    assert "nanofaas.e2e.scenarioManifest" in output
