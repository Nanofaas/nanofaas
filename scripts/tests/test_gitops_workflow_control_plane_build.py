from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "gitops.yml"


def test_gitops_workflow_uses_control_plane_build_wrapper() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    assert "./scripts/controlplane.sh images" in workflow
    assert "--arch all" in workflow
    assert "--flavor all" in workflow
    assert "--tag ${{ github.ref_name }}" in workflow
    assert "--push" in workflow
    assert "--fail-fast" in workflow
    assert "--arch-suffix" not in workflow
    assert "bootBuildImage" not in workflow
    assert ":control-plane:bootBuildImage" not in workflow
    assert ":function-runtime:bootBuildImage" not in workflow
    assert "docker/build-push-action" not in workflow
    assert ":latest" not in workflow
    assert "docker tag" not in workflow
    assert "docker push latest" not in workflow
    assert "docker push" not in workflow
    assert "--all-tags" not in workflow
