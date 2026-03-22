from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "lib" / "e2e-k3s-common.sh"


def test_common_script_supports_externally_managed_ssh_vm_lifecycle():
    script = SCRIPT.read_text(encoding="utf-8")

    # Lifecycle mode knobs.
    assert "E2E_VM_LIFECYCLE" in script
    assert "e2e_get_vm_lifecycle" in script
    assert "e2e_is_external_vm_lifecycle" in script
    assert "e2e_get_vm_host" in script

    # External hosts can be resolved without multipass.
    assert "E2E_VM_HOST" in script
    assert "if [[ -n \"${E2E_VM_HOST:-}\" ]]; then" in script
    assert "e2e_require_vm_access" in script
    assert "e2e_get_vm_user" in script
    assert "e2e_get_vm_home" in script
    assert "e2e_get_kubeconfig_path" in script
    assert "e2e_get_remote_project_dir" in script
    assert "if e2e_is_external_vm_lifecycle; then" in script
    assert "Using externally managed VM host" in script
    assert "Skipping VM deletion: external VM lifecycle mode" in script



def test_common_script_avoids_ubuntu_hardcoding_for_kubeconfig_and_k3s_setup():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "kubeconfig_dir=$(dirname \"${kubeconfig_path}\")" in script
    assert "mkdir -p ${q_kubeconfig_dir}" in script
    assert "sudo cp /etc/rancher/k3s/k3s.yaml ${q_kubeconfig_path}" in script
    assert "sudo chown ${vm_user}:${vm_user} ${q_kubeconfig_path}" in script
    assert "export KUBECONFIG=${q_kubeconfig_path}" in script
    assert "/home/ubuntu/.kube/config" not in script
