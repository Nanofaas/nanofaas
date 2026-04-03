from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
COMMON = REPO_ROOT / "scripts" / "lib" / "e2e-k3s-common.sh"
ANSIBLE_DIR = REPO_ROOT / "ops" / "ansible"


def test_ansible_layout_exists_for_vm_provisioning():
    assert (ANSIBLE_DIR / "ansible.cfg").exists()
    assert (ANSIBLE_DIR / "requirements.txt").exists()
    assert (ANSIBLE_DIR / "playbooks" / "provision-base.yml").exists()
    assert (ANSIBLE_DIR / "playbooks" / "provision-k3s.yml").exists()
    assert (ANSIBLE_DIR / "playbooks" / "configure-registry.yml").exists()


def test_common_script_exposes_ansible_bootstrap_and_inventory_helpers():
    script = COMMON.read_text(encoding="utf-8")

    assert "e2e_get_ansible_root()" in script
    assert "e2e_get_ansible_venv_dir()" in script
    assert "e2e_get_ansible_bin()" in script
    assert "e2e_ensure_ansible()" in script
    assert "e2e_write_ansible_inventory()" in script
    assert "e2e_run_ansible_playbook()" in script
    assert "python3 -m pip install --user -r" in script
    assert "ops/ansible" in script


def test_common_script_routes_vm_provisioning_through_ansible_playbooks():
    script = COMMON.read_text(encoding="utf-8")

    assert "playbooks/provision-base.yml" in script
    assert "playbooks/provision-k3s.yml" in script
    assert "playbooks/configure-registry.yml" in script
    assert "e2e_install_vm_dependencies()" in script
    assert "e2e_install_k3s()" in script
    assert "e2e_setup_local_registry()" in script
    assert "e2e_run_ansible_playbook" in script


def test_k3s_provisioning_resolves_latest_release_dynamically():
    common = COMMON.read_text(encoding="utf-8")
    playbook = (ANSIBLE_DIR / "playbooks" / "provision-k3s.yml").read_text(encoding="utf-8")

    assert "K3S_VERSION:-v" not in common
    assert "https://api.github.com/repos/k3s-io/k3s/releases/latest" in playbook
    assert "k3s_version_override" in playbook
    assert "tag_name" in playbook


def test_ansible_playbooks_preserve_idempotence_guards_for_helm_and_registry():
    base = (ANSIBLE_DIR / "playbooks" / "provision-base.yml").read_text(encoding="utf-8")
    registry = (ANSIBLE_DIR / "playbooks" / "configure-registry.yml").read_text(encoding="utf-8")

    assert '("v" ~ helm_version)' in base
    assert 'docker port {{ registry_container_name }} 5000/tcp' in registry


def test_base_playbook_installs_uv_for_controlplane_wrapper() -> None:
    base = (ANSIBLE_DIR / "playbooks" / "provision-base.yml").read_text(encoding="utf-8")
    assert "Install uv" in base
    assert "UV_INSTALL_DIR" in base or "command -v uv" in base
