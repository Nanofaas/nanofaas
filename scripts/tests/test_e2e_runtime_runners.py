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
    assert "e2e_require_vm_access" in k8s_vm

    cli = read_script("e2e-cli.sh")
    assert "e2e_build_control_plane_artifacts" in cli
    assert "e2e_build_control_plane_image" in cli
    assert "e2e_require_vm_access" in cli

    host_cli = read_script("e2e-cli-host-platform.sh")
    assert "e2e_build_control_plane_artifacts" in host_cli
    assert "e2e_build_control_plane_image" in host_cli
    assert "e2e_require_vm_access" in host_cli

    k3s_curl = read_script("e2e-k3s-curl.sh")
    assert "e2e_require_vm_access" in k3s_curl

    k3s_helm = read_script("e2e-k3s-helm.sh")
    assert "e2e_require_vm_access" in k3s_helm


def test_vm_provisioning_contract_uses_ansible_in_common_library():
    common = (SCRIPTS_DIR / "lib" / "e2e-k3s-common.sh").read_text(encoding="utf-8")
    assert "e2e_ensure_ansible()" in common
    assert "e2e_run_ansible_playbook()" in common
    assert "playbooks/provision-base.yml" in common
    assert "playbooks/provision-k3s.yml" in common
    assert "playbooks/configure-registry.yml" in common


def test_runner_scripts_resolve_remote_paths_through_helpers():
    expectations = {
        "e2e-k8s-vm.sh": (
            "REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}",
            "KUBECONFIG_PATH=$(e2e_get_kubeconfig_path)",
            "KUBECONFIG=${KUBECONFIG_PATH}",
        ),
        "e2e-cli.sh": (
            "REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}",
            "VM_HOME=$(e2e_get_vm_home)",
            "VM_USER=$(e2e_get_vm_user)",
            'CLI_BIN_DIR="${REMOTE_DIR}/nanofaas-cli/build/install/nanofaas-cli/bin"',
            "export PATH=\\$PATH:${CLI_BIN_DIR}",
            ">> ${VM_HOME}/.bashrc",
        ),
        "e2e-k3s-curl.sh": (
            "REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}",
            'e2e_sync_project_to_vm "${PROJECT_ROOT}" "${VM_NAME}" "${REMOTE_DIR}"',
        ),
        "e2e-cli-host-platform.sh": (
            "REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}",
            "KUBECONFIG_PATH=$(e2e_get_kubeconfig_path)",
            "VM_HOME=$(e2e_get_vm_home)",
            "VM_USER=$(e2e_get_vm_user)",
        ),
        "e2e-k3s-helm.sh": (
            "REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}",
        ),
    }

    for name, snippets in expectations.items():
        script = read_script(name)
        for snippet in snippets:
            assert snippet in script, name


def test_runner_scripts_do_not_hardcode_ubuntu_remote_paths():
    for name in (
        "e2e-k8s-vm.sh",
        "e2e-cli.sh",
        "e2e-k3s-curl.sh",
        "e2e-cli-host-platform.sh",
        "e2e-k3s-helm.sh",
    ):
        script = read_script(name)
        assert "/home/ubuntu/nanofaas" not in script, name
        assert "/home/ubuntu/.kube/config" not in script, name
        assert "/home/ubuntu/.bashrc" not in script, name
        assert "/home/ubuntu/nanofaas/helm/nanofaas" not in script, name
        assert "/home/ubuntu/nanofaas/nanofaas-cli/build/install/nanofaas-cli/bin" not in script, name


def test_host_facing_scripts_use_shared_kubeconfig_and_public_host_helpers():
    common = (SCRIPTS_DIR / "lib" / "e2e-k3s-common.sh").read_text(encoding="utf-8")
    assert "e2e_export_kubeconfig_to_host()" in common
    assert "e2e_get_public_host()" in common
    assert 'public_host=$(e2e_get_public_host)' in common

    host_cli = read_script("e2e-cli-host-platform.sh")
    assert 'e2e_export_kubeconfig_to_host "${VM_NAME}" "${KUBECONFIG_HOST}"' in host_cli
    assert "PUBLIC_HOST=$(e2e_get_public_host)" in host_cli
    assert 'python3 - "${KUBECONFIG_HOST}" "${VM_IP}"' not in host_cli
    assert 'VM_IP=""' not in host_cli


def test_experiment_scripts_resolve_host_endpoints_through_shared_helpers():
    loadtest = (REPO_ROOT / "experiments" / "e2e-loadtest.sh").read_text(encoding="utf-8")
    assert "e2e_resolve_nanofaas_url 30080" in loadtest
    assert "e2e_resolve_nanofaas_url 30090" in loadtest

    loadtest_registry = (REPO_ROOT / "experiments" / "e2e-loadtest-registry.sh").read_text(encoding="utf-8")
    assert 'REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}' in loadtest_registry
    assert 'REMOTE_HELM_DIR="${REMOTE_DIR}/helm/nanofaas"' in loadtest_registry
    assert 'e2e_sync_project_to_vm "${PROJECT_ROOT}" "${VM_NAME}" "${REMOTE_DIR}"' in loadtest_registry
    assert 'e2e_resolve_nanofaas_url 30090' in loadtest_registry

    cold_start = (REPO_ROOT / "experiments" / "e2e-cold-start-metrics.sh").read_text(encoding="utf-8")
    assert 'REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}' in cold_start
    assert 'e2e_build_control_plane_artifacts "${REMOTE_DIR}"' in cold_start
    assert 'e2e_build_function_runtime_image "${REMOTE_DIR}" "${RUNTIME_IMAGE}"' in cold_start

    runtime_ab = (REPO_ROOT / "experiments" / "e2e-runtime-ab.sh").read_text(encoding="utf-8")
    assert 'e2e_resolve_nanofaas_url 30090' in runtime_ab

    memory_ab = (REPO_ROOT / "experiments" / "e2e-memory-ab.sh").read_text(encoding="utf-8")
    assert "e2e_get_vm_ip" not in memory_ab
    assert 'e2e_resolve_nanofaas_url 30081' in memory_ab

def test_k3s_helm_uses_runtime_aware_control_plane_image_paths():
    script = read_script("e2e-k3s-helm.sh")
    assert 'LOADTEST_RUNTIMES=${LOADTEST_RUNTIMES:-java,java-lite,python,exec,go}' in script
    assert 'local allowed_runtimes=("java" "java-lite" "python" "exec" "go")' in script
    assert 'word-stats-go' in script
    assert 'json-transform-go' in script
    assert 'REMOTE_DIR=${REMOTE_DIR:-$(e2e_get_remote_project_dir)}' in script
    assert 'e2e_build_control_plane_image "${REMOTE_DIR}" "${CONTROL_IMAGE}"' in script
    assert 'helm upgrade --install nanofaas ${REMOTE_HELM_DIR} \\' in script
    assert "if [[ \"$(e2e_runtime_kind)\" == \"rust\" ]]; then" in script
    assert "Building control-plane image on host (Rust Dockerfile)" in script
    assert "CONTROL_PLANE_NATIVE_BUILD=true is ignored for rust runtime; forcing false." in script
    assert "E2E_HELM_STACK_CONTEXT_FILE=${E2E_HELM_STACK_CONTEXT_FILE:-}" in script
    assert "E2E_HELM_STACK_FORCE_KEEP_VM=${E2E_HELM_STACK_FORCE_KEEP_VM:-false}" in script
    assert "E2E_WIZARD_CONTEXT_FILE=\"${E2E_HELM_STACK_CONTEXT_FILE}\"" in script
    assert "E2E_WIZARD_FORCE_KEEP_VM=\"${E2E_HELM_STACK_FORCE_KEEP_VM}\"" in script
    assert "NANOFAAS_SYNC_INVOKE_QUEUE_ENABLED" in script
    assert "NANOFAAS_INTERNAL_SCALER_POLL_INTERVAL_MS" in script
    assert "write_helm_stack_context" in script


def test_e2e_all_logs_runtime_and_skips_unsupported_rust_suites():
    script = read_script("e2e-all.sh")
    assert "E2E_RUNTIME_KIND=$(e2e_runtime_kind)" in script
    assert "Runtime=${E2E_RUNTIME_KIND}" in script
    assert "E2E_K3S_HELM_NONINTERACTIVE=${E2E_K3S_HELM_NONINTERACTIVE:-true}" in script
    assert "CONTROL_PLANE_RUNTIME=\"${CONTROL_PLANE_RUNTIME}\"" in script
    assert "E2E_K3S_HELM_NONINTERACTIVE=\"${E2E_K3S_HELM_NONINTERACTIVE}\"" in script
    assert "E2E_HELM_STACK_CONTEXT_FILE=\"${helm_context_file}\"" in script
    assert "E2E_HELM_STACK_FORCE_KEEP_VM=true" in script
    assert "E2E_WIZARD_FORCE_RUN_LOADTEST=false" in script
    assert "E2E_WIZARD_CAPTURE_LOADTEST_CONFIG=true" in script
    assert "E2E_WIZARD_DEFER_LOADTEST_EXECUTION=true" in script
    assert "read_context_value()" in script
    assert "RUN_LOADTEST" in script
    assert "INVOCATION_MODE" in script
    assert "loadtest_modes=(\"sync\" \"async\")" in script
    assert "Phase 1 context: VM=${helm_vm_name} tag=${helm_tag} runtime=${helm_runtime}" in script
    assert "if [[ \"${E2E_RUNTIME_KIND}\" != \"rust\" ]]; then" in script
    assert "rust runtime skip:" in script
    assert "k3s-curl" in script


def test_e2e_all_describes_vm_prerequisites_in_lifecycle_aware_way():
    script = read_script("e2e-all.sh")
    assert "- ssh (for every VM-based suite)" in script
    assert "- multipass (only when E2E_VM_LIFECYCLE=multipass)" in script
    assert "E2E_VM_LIFECYCLE=external" in script


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
