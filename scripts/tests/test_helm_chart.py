from __future__ import annotations

import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CHART = REPO_ROOT / "deploy" / "helm" / "nanofaas"


def render(template: str, *extra_args: str) -> str:
    """Render a single chart template with `helm template`, returning its text."""
    result = subprocess.run(
        ["helm", "template", "nanofaas", str(CHART), "--show-only", template, *extra_args],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def render_all(*extra_args: str) -> str:
    """Render the whole chart, returning the concatenated manifest text."""
    result = subprocess.run(
        ["helm", "template", "nanofaas", str(CHART), *extra_args],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def test_control_plane_pvc_is_enabled_by_default():
    out = render("templates/control-plane-pvc.yaml")
    assert "kind: PersistentVolumeClaim" in out
    assert "name: nanofaas-control-plane-data" in out
    assert 'storage: "1Gi"' in out


def test_control_plane_pvc_size_and_storage_class_are_configurable():
    out = render(
        "templates/control-plane-pvc.yaml",
        "--set",
        "controlPlane.persistence.size=2Gi",
        "--set",
        "controlPlane.persistence.storageClass=fast",
    )
    assert 'storage: "2Gi"' in out
    assert 'storageClassName: "fast"' in out


def test_existing_claim_suppresses_new_control_plane_pvc():
    out = render_all("--set", "controlPlane.persistence.existingClaim=my-claim")
    assert "name: nanofaas-control-plane-data" not in out
    assert "claimName: \"my-claim\"" in out


def test_control_plane_deployment_mounts_registry_volume_and_sets_env():
    out = render("templates/control-plane-deployment.yaml")
    assert "mountPath: /var/lib/nanofaas" in out
    assert "name: NANOFAAS_REGISTRY_PATH" in out
    assert "value: /var/lib/nanofaas/functions.json" in out
    assert 'claimName: "nanofaas-control-plane-data"' in out


def test_control_plane_deployment_uses_existing_claim_when_set():
    out = render(
        "templates/control-plane-deployment.yaml",
        "--set",
        "controlPlane.persistence.existingClaim=my-claim",
    )
    assert 'claimName: "my-claim"' in out


def test_control_plane_runs_as_distroless_nonroot_with_fsgroup():
    out = render("templates/control-plane-deployment.yaml")
    assert "runAsUser: 65532" in out
    assert "runAsGroup: 65532" in out
    assert "fsGroup: 65532" in out


def test_control_plane_replica_count_defaults_to_one():
    out = render("templates/control-plane-deployment.yaml")
    assert "replicas: 1" in out
