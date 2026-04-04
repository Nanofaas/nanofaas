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


def test_python_runtime_primitives_module_is_importable() -> None:
    import importlib

    _ensure_tool_src_on_path()
    mod = importlib.import_module("controlplane_tool.runtime_primitives")
    assert hasattr(mod, "CommandRunner")


def test_python_control_plane_api_module_is_importable() -> None:
    import importlib

    _ensure_tool_src_on_path()
    mod = importlib.import_module("controlplane_tool.control_plane_api")
    assert hasattr(mod, "ControlPlaneApi")


def test_python_k3s_runtime_module_is_importable() -> None:
    import importlib

    _ensure_tool_src_on_path()
    mod = importlib.import_module("controlplane_tool.k3s_runtime")
    assert hasattr(mod, "K3sCurlRunner")
    assert hasattr(mod, "HelmStackRunner")


def test_python_ansible_adapter_owns_vm_provisioning_contract() -> None:
    import importlib

    _ensure_tool_src_on_path()
    mod = importlib.import_module("controlplane_tool.ansible_adapter")
    adapter_cls = mod.AnsibleAdapter
    assert hasattr(adapter_cls, "provision_base")
    assert hasattr(adapter_cls, "provision_k3s")
    assert hasattr(adapter_cls, "configure_registry")


def test_python_vm_orchestrator_owns_lifecycle_contract() -> None:
    import importlib

    _ensure_tool_src_on_path()
    mod = importlib.import_module("controlplane_tool.vm_adapter")
    orch_cls = mod.VmOrchestrator
    assert hasattr(orch_cls, "ensure_running")
    assert hasattr(orch_cls, "install_dependencies")
    assert hasattr(orch_cls, "install_k3s")
    assert hasattr(orch_cls, "setup_registry")
    assert hasattr(orch_cls, "sync_project")
    assert hasattr(orch_cls, "export_kubeconfig")
    assert hasattr(orch_cls, "remote_exec")
    assert hasattr(orch_cls, "teardown")


def test_python_vm_orchestrator_resolves_external_vm_paths() -> None:
    """External VM lifecycle is handled by VmOrchestrator (replaces e2e-k3s-common.sh)."""
    import importlib

    _ensure_tool_src_on_path()
    vm_mod = importlib.import_module("controlplane_tool.vm_models")
    VmRequest = vm_mod.VmRequest  # noqa: N806

    vm_mod2 = importlib.import_module("controlplane_tool.vm_adapter")
    VmOrchestrator = vm_mod2.VmOrchestrator  # noqa: N806

    from pathlib import Path

    vm = VmOrchestrator(Path("/repo"))
    request = VmRequest(
        lifecycle="external",
        host="vm.example.test",
        user="dev",
        home="/srv/dev",
    )
    assert vm.remote_project_dir(request) == "/srv/dev/nanofaas"
    assert vm.kubeconfig_path(request) == "/srv/dev/.kube/config"


def test_ansible_playbooks_exist_for_vm_provisioning() -> None:
    """Ansible playbooks are still the authoritative provisioning source."""
    ansible_dir = REPO_ROOT / "ops" / "ansible"
    assert (ansible_dir / "ansible.cfg").exists()
    assert (ansible_dir / "playbooks" / "provision-base.yml").exists()
    assert (ansible_dir / "playbooks" / "provision-k3s.yml").exists()
    assert (ansible_dir / "playbooks" / "configure-registry.yml").exists()
