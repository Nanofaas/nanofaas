from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
ANSIBLE_DIR = REPO_ROOT / "tools" / "workflow-tasks" / "src" / "workflow_tasks" / "infra" / "ansible_assets"


# M11: e2e-k3s-common.sh deleted. Ansible bootstrap/inventory helpers are now
# owned by AnsibleAdapter in tools/controlplane/src/controlplane_tool/ansible_adapter.py.
def test_e2e_k3s_common_is_deleted_ansible_helpers_live_in_python() -> None:
    assert not (REPO_ROOT / "scripts" / "lib" / "e2e-k3s-common.sh").exists(), (
        "e2e-k3s-common.sh still exists — delete it after Python path is green (M11)"
    )
