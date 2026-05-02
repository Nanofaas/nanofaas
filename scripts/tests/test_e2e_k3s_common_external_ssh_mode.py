"""
M11: e2e-k3s-common.sh is deleted.

The external SSH VM lifecycle behavior it provided is now owned by
VmOrchestrator in tools/controlplane/src/controlplane_tool/vm_adapter.py.
"""
from pathlib import Path
import sys

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = REPO_ROOT / "scripts"


def _ensure_tool_src_on_path() -> None:
    src = str(REPO_ROOT / "tools" / "controlplane" / "src")
    if src not in sys.path:
        sys.path.insert(0, src)


def test_e2e_k3s_common_is_deleted() -> None:
    assert not (SCRIPTS_DIR / "lib" / "e2e-k3s-common.sh").exists(), (
        "e2e-k3s-common.sh still exists — delete it after Python path is green (M11)"
    )


def test_vm_orchestrator_supports_external_lifecycle() -> None:
    """VmOrchestrator handles external lifecycle in place of e2e-k3s-common.sh."""
    _ensure_tool_src_on_path()
    from controlplane_tool.vm_adapter import VmOrchestrator  # noqa: PLC0415
    from controlplane_tool.vm_models import VmRequest  # noqa: PLC0415
    from controlplane_tool.shell_backend import RecordingShell  # noqa: PLC0415

    shell = RecordingShell()
    vm = VmOrchestrator(Path("/repo"), shell=shell)
    request = VmRequest(
        lifecycle="external",
        host="vm.example.test",
        user="dev",
        home="/srv/dev",
    )

    assert vm.remote_project_dir(request) == "/srv/dev/nanofaas"
    assert vm.kubeconfig_path(request) == "/srv/dev/.kube/config"

    result = vm.ensure_running(request, dry_run=True)
    assert "vm.example.test" in " ".join(result.command)


def test_vm_orchestrator_external_sync_uses_rsync() -> None:
    _ensure_tool_src_on_path()
    from controlplane_tool.vm_adapter import VmOrchestrator  # noqa: PLC0415
    from controlplane_tool.vm_models import VmRequest  # noqa: PLC0415
    from controlplane_tool.shell_backend import RecordingShell  # noqa: PLC0415

    shell = RecordingShell()
    vm = VmOrchestrator(Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")
    result = vm.sync_project(request, dry_run=True)
    assert "rsync" in result.command[0]
    assert "vm.example.test" in " ".join(result.command)


def test_vm_orchestrator_external_teardown_skips_multipass() -> None:
    _ensure_tool_src_on_path()
    from controlplane_tool.vm_adapter import VmOrchestrator  # noqa: PLC0415
    from controlplane_tool.vm_models import VmRequest  # noqa: PLC0415
    from controlplane_tool.shell_backend import RecordingShell  # noqa: PLC0415

    shell = RecordingShell()
    vm = VmOrchestrator(Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="external", host="vm.example.test", user="dev")
    result = vm.teardown(request, dry_run=True)
    assert "multipass" not in " ".join(result.command)
    assert "Skipping" in " ".join(result.command)
