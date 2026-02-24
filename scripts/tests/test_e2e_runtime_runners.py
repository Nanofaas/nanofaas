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


def test_runner_scripts_expose_control_plane_runtime_switch():
    for name in (
        "e2e-k8s-vm.sh",
        "e2e-cli.sh",
        "e2e-cli-host-platform.sh",
        "e2e-k3s-helm.sh",
        "e2e-all.sh",
    ):
        script = read_script(name)
        assert "CONTROL_PLANE_RUNTIME=${CONTROL_PLANE_RUNTIME:-java}" in script


def test_vm_based_runners_use_runtime_aware_shared_helpers():
    k8s_vm = read_script("e2e-k8s-vm.sh")
    assert "e2e_build_control_plane_artifacts" in k8s_vm
    assert "e2e_build_control_plane_image" in k8s_vm

    cli = read_script("e2e-cli.sh")
    assert "e2e_build_control_plane_artifacts" in cli
    assert "e2e_build_control_plane_image" in cli

    host_cli = read_script("e2e-cli-host-platform.sh")
    assert "e2e_build_control_plane_artifacts" in host_cli
    assert "e2e_build_control_plane_image" in host_cli


def test_k3s_helm_uses_runtime_aware_control_plane_image_paths():
    script = read_script("e2e-k3s-helm.sh")
    assert "e2e_build_control_plane_image \"/home/ubuntu/nanofaas\" \"${CONTROL_IMAGE}\"" in script
    assert "if [[ \"$(e2e_runtime_kind)\" == \"rust\" ]]; then" in script
    assert "Building control-plane image on host (Rust Dockerfile)" in script


def test_e2e_all_logs_runtime_and_skips_unsupported_rust_suites():
    script = read_script("e2e-all.sh")
    assert "E2E_RUNTIME_KIND=$(e2e_runtime_kind)" in script
    assert "Runtime=${E2E_RUNTIME_KIND}" in script
    assert "if [[ \"${E2E_RUNTIME_KIND}\" != \"rust\" ]]; then" in script
    assert "rust runtime skip:" in script
    assert "k3s-curl" in script


def test_e2e_all_dry_run_skips_java_only_suite_for_rust_runtime():
    output = run_script(
        "e2e-all.sh",
        "--only",
        "docker",
        env={"DRY_RUN": "true", "CONTROL_PLANE_RUNTIME": "rust"},
    )
    assert "SKIP: docker" in output
    assert "rust runtime skip:" in output


def test_e2e_all_dry_run_executes_supported_suite_for_rust_runtime():
    output = run_script(
        "e2e-all.sh",
        "--only",
        "k3s-curl",
        env={"DRY_RUN": "true", "CONTROL_PLANE_RUNTIME": "rust"},
    )
    assert "Would execute:" in output
    assert "e2e-k3s-curl.sh" in output
    assert "SKIP: k3s-curl" not in output
