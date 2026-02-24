from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "lib" / "e2e-k3s-common.sh"


def test_common_script_supports_externally_managed_ssh_vm_lifecycle():
    script = SCRIPT.read_text(encoding="utf-8")

    # Lifecycle mode knobs.
    assert "E2E_VM_LIFECYCLE" in script
    assert "e2e_get_vm_lifecycle" in script
    assert "e2e_is_external_vm_lifecycle" in script

    # External hosts can be resolved without multipass.
    assert "E2E_VM_HOST" in script
    assert "if [[ -n \"${E2E_VM_HOST:-}\" ]]; then" in script

    # In external mode, we must not require multipass.
    assert "External VM lifecycle selected" in script
    assert "e2e_require_multipass" in script

    # In external mode, ensure_vm_running validates SSH reachability and skips VM creation.
    assert "if e2e_is_external_vm_lifecycle; then" in script
    assert "Using externally managed VM host" in script
    assert "if ! e2e_ssh_exec" in script

    # Cleanup should skip multipass deletion in external mode.
    assert "Skipping VM deletion: external VM lifecycle mode" in script


def test_common_script_avoids_ubuntu_hardcoding_for_kubeconfig_and_k3s_setup():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "e2e_get_vm_user" in script
    assert "e2e_get_vm_home" in script
    assert "e2e_get_kubeconfig_path" in script
    assert "mkdir -p ${vm_home}/.kube" in script
    assert "sudo cp /etc/rancher/k3s/k3s.yaml ${kubeconfig_path}" in script
    assert "sudo chown ${vm_user}:${vm_user} ${kubeconfig_path}" in script
    assert "export KUBECONFIG=${kubeconfig_path}" in script
    assert "/home/ubuntu/.kube/config" not in script
