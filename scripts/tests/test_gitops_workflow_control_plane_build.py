from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "gitops.yml"


def test_gitops_workflow_uses_control_plane_build_wrapper() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    assert "scripts/controlplane.sh image --profile all --" in workflow
    assert "./gradlew :control-plane:bootBuildImage" not in workflow
