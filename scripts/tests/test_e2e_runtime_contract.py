from __future__ import annotations

import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = REPO_ROOT / "scripts"


# ---------------------------------------------------------------------------
# M11: e2e-k3s-common.sh is deleted.
# All behaviors previously tested by sourcing that file are now owned by
# the Python adapters in tools/controlplane/src/controlplane_tool/.
# ---------------------------------------------------------------------------

def test_e2e_k3s_common_shell_is_deleted() -> None:
    """Fails if e2e-k3s-common.sh still exists (should have been deleted in M11)."""
    assert not (SCRIPTS_DIR / "lib" / "e2e-k3s-common.sh").exists(), (
        "e2e-k3s-common.sh still exists — delete it after Python path is green (M11)"
    )


# ---------------------------------------------------------------------------
# Python-runtime contract (M8+): verify that the Python substrate that
# replaced the shell contracts above is importable and coherent.
# ---------------------------------------------------------------------------

def _tool_src() -> Path:
    return REPO_ROOT / "tools" / "controlplane" / "src"


def _ensure_tool_src_on_path() -> None:
    src = str(_tool_src())
    if src not in sys.path:
        sys.path.insert(0, src)


def test_ansible_playbooks_exist_for_vm_provisioning() -> None:
    """Ansible playbooks are still the authoritative provisioning source."""
    ansible_dir = REPO_ROOT / "ops" / "ansible"
    assert (ansible_dir / "ansible.cfg").exists()
    assert (ansible_dir / "playbooks" / "provision-base.yml").exists()
    assert (ansible_dir / "playbooks" / "provision-k3s.yml").exists()
    assert (ansible_dir / "playbooks" / "ensure-registry.yml").exists()
    assert (ansible_dir / "playbooks" / "configure-k3s-registry.yml").exists()


def test_helm_control_plane_template_quotes_extra_env_values() -> None:
    template = (
        REPO_ROOT / "helm" / "nanofaas" / "templates" / "control-plane-deployment.yaml"
    ).read_text(encoding="utf-8")
    assert "{{- range $env := . }}" in template
    assert "value: {{ $env.value | quote }}" in template
