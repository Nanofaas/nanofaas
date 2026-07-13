from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "gitops.yml"


def test_gitops_workflow_publishes_control_plane_and_warm_echo_images() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    assert ":control-plane:bootBuildImage" in workflow
    assert ":services:java:warm-echo:bootBuildImage" in workflow
    assert "-PwarmEchoImage=${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}/java-warm-echo:${{ github.ref_name }}" in workflow
    assert "docker push ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}/java-warm-echo:${{ github.ref_name }}" in workflow
    assert ":function-runtime:bootBuildImage" not in workflow
    assert "-PfunctionRuntimeImage=" not in workflow
    assert "docker/build-push-action" not in workflow
    assert ":latest" not in workflow
    assert "docker tag" not in workflow
    assert "docker push latest" not in workflow
    assert "--all-tags" not in workflow
