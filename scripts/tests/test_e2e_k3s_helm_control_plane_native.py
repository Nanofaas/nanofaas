from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "e2e-k3s-helm.sh"


def test_k3s_helm_script_supports_native_control_plane_build_knobs():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "CONTROL_PLANE_NATIVE_BUILD" in script
    assert "CONTROL_PLANE_MODULES" in script
    assert "CONTROL_PLANE_NATIVE_IMAGE_BUILD_ARGS" in script
    assert "CONTROL_PLANE_BUILD_ON_HOST" in script
    assert "CONTROL_PLANE_ONLY" in script
    assert ":control-plane:bootBuildImage" in script
    assert ":function-runtime:bootJar :examples:java:word-stats:bootJar :examples:java:json-transform:bootJar" in script
    assert "uname -m" in script
    assert "linux/arm64" in script
    assert "-PimagePlatform=" in script
    assert "paketobuildpacks/builder-jammy-java-tiny:latest" in script
    assert "NATIVE_IMAGE_BUILD_ARGS=" in script
    assert "docker save" in script
    assert "sudo docker load -i" in script
    assert "demos_enabled" in script
    assert "Control-plane-only mode" in script
    assert "E2E_K3S_HELM_NONINTERACTIVE" in script
    assert "e2e-control-plane-experiment.sh" in script
    assert "register functions before running load tests" in script
