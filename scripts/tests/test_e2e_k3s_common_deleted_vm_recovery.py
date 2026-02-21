from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "lib" / "e2e-k3s-common.sh"


def test_common_script_handles_deleted_vm_state_with_recover_or_recreate():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "if [[ \"${state}\" == \"Deleted\" ]]" in script
    assert "multipass recover \"${vm_name}\"" in script
    assert "multipass purge >/dev/null 2>&1 || true" in script
    assert "e2e_create_vm \"${vm_name}\" \"${cpus}\" \"${memory}\" \"${disk}\"" in script
