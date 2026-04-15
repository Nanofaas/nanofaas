from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
ANSIBLE_DIR = REPO_ROOT / "ops" / "ansible"


def test_ansible_layout_exists_for_vm_provisioning():
    assert (ANSIBLE_DIR / "ansible.cfg").exists()
    assert (ANSIBLE_DIR / "requirements.txt").exists()
    assert (ANSIBLE_DIR / "playbooks" / "provision-base.yml").exists()
    assert (ANSIBLE_DIR / "playbooks" / "install-k6.yml").exists()
    assert (ANSIBLE_DIR / "playbooks" / "provision-k3s.yml").exists()
    assert (ANSIBLE_DIR / "playbooks" / "ensure-registry.yml").exists()
    assert (ANSIBLE_DIR / "playbooks" / "configure-k3s-registry.yml").exists()


# M11: e2e-k3s-common.sh deleted. Ansible bootstrap/inventory helpers are now
# owned by AnsibleAdapter in tools/controlplane/src/controlplane_tool/ansible_adapter.py.
def test_e2e_k3s_common_is_deleted_ansible_helpers_live_in_python() -> None:
    assert not (REPO_ROOT / "scripts" / "lib" / "e2e-k3s-common.sh").exists(), (
        "e2e-k3s-common.sh still exists — delete it after Python path is green (M11)"
    )


def test_k3s_provisioning_resolves_latest_release_dynamically():
    playbook = (ANSIBLE_DIR / "playbooks" / "provision-k3s.yml").read_text(encoding="utf-8")

    assert "https://api.github.com/repos/k3s-io/k3s/releases/latest" in playbook
    assert "k3s_version_override" in playbook
    assert "tag_name" in playbook


def test_ansible_playbooks_preserve_idempotence_guards_for_helm_and_registry():
    base = (ANSIBLE_DIR / "playbooks" / "provision-base.yml").read_text(encoding="utf-8")
    registry = (ANSIBLE_DIR / "playbooks" / "ensure-registry.yml").read_text(encoding="utf-8")

    assert '("v" ~ helm_version)' in base
    assert 'docker port {{ registry_container_name }} 5000/tcp' in registry


def test_base_playbook_installs_uv_for_controlplane_wrapper() -> None:
    base = (ANSIBLE_DIR / "playbooks" / "provision-base.yml").read_text(encoding="utf-8")
    assert "Install uv" in base
    assert "UV_INSTALL_DIR" in base or "command -v uv" in base


def test_k6_playbook_installs_k6_for_vm_loadtests() -> None:
    playbook = (ANSIBLE_DIR / "playbooks" / "install-k6.yml").read_text(encoding="utf-8")

    assert "Install k6" in playbook
    assert "https://github.com/grafana/k6/releases" in playbook
    assert "dest: /usr/local/bin/k6" in playbook
