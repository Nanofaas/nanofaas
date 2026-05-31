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


