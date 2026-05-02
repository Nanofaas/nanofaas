"""
M11: e2e-k3s-common.sh is deleted.

The deleted/recoverable VM state handling it provided is now owned by
VmOrchestrator.ensure_running() in vm_adapter.py, which inspects multipass
state and calls multipass start/launch accordingly.
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


def test_vm_orchestrator_ensure_running_handles_multipass_lifecycle() -> None:
    """VmOrchestrator.ensure_running uses multipass start/launch (replaces common.sh recover logic)."""
    _ensure_tool_src_on_path()
    from controlplane_tool.vm_adapter import VmOrchestrator  # noqa: PLC0415
    from controlplane_tool.vm_models import VmRequest  # noqa: PLC0415
    from controlplane_tool.shell_backend import RecordingShell  # noqa: PLC0415

    shell = RecordingShell()
    vm = VmOrchestrator(Path("/repo"), shell=shell)
    request = VmRequest(lifecycle="multipass", name="nanofaas-e2e")

    result = vm.ensure_running(request, dry_run=True)
    assert "multipass" in result.command
