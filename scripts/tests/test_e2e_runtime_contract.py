from __future__ import annotations

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


def test_legacy_python_runtime_is_deleted() -> None:
    assert not (REPO_ROOT / "python-runtime").exists()


def test_legacy_root_tooling_is_deleted() -> None:
    assert not (REPO_ROOT / "tooling").exists()


# ---------------------------------------------------------------------------
# Python provisioning contract (M8+): verify that the Python substrate that
# replaced the shell contracts above is importable and coherent.
# ---------------------------------------------------------------------------

def test_helm_control_plane_template_quotes_extra_env_values() -> None:
    template = (
        REPO_ROOT / "deploy" / "helm" / "nanofaas" / "templates" / "control-plane-deployment.yaml"
    ).read_text(encoding="utf-8")
    assert "{{- range $env := . }}" in template
    assert "value: {{ $env.value | quote }}" in template
