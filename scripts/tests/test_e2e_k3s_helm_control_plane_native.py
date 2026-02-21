from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "e2e-k3s-helm.sh"


def test_k3s_helm_script_supports_native_control_plane_build_knobs():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "CONTROL_PLANE_NATIVE_BUILD" in script
    assert "CONTROL_PLANE_MODULES" in script
    assert "CONTROL_PLANE_NATIVE_IMAGE_BUILD_ARGS" in script
    assert "CONTROL_PLANE_NATIVE_ACTIVE_PROCESSORS" in script
    assert "CONTROL_PLANE_BUILD_ON_HOST" in script
    assert "CONTROL_PLANE_ONLY" in script
    assert "HOST_REBUILD_IMAGES" in script
    assert "resolve_host_control_image_ref" in script
    assert "compute_sha256_12" in script
    assert "ensure_host_image_available_from_local_cache" in script
    assert "docker image ls \"${repo}\" --format" in script
    assert "CONTROL_PLANE_CACHE_ROOT" in script
    assert "manifest.json" in script
    assert "write_control_plane_cache_manifest" in script
    assert "control_plane_cache_manifest_is_valid" in script
    assert "LOADTEST_WORKLOADS" in script
    assert "LOADTEST_RUNTIMES" in script
    assert "resolve_selected_demo_targets" in script
    assert "should_include_demo" in script
    assert "build_non_control_plane_images_on_host" in script
    assert "push_host_non_control_plane_images_to_registry" in script
    assert "Reusing existing host-built control-plane image" in script
    assert "HOST_REBUILD_IMAGES=false but missing host control-plane image" in script
    assert "Reusing existing host-built function/runtime/demo images" in script
    assert "HOST_REBUILD_IMAGES=false but one or more host images are missing" in script
    assert "HOST_CONTROL_IMAGE=\"$(resolve_host_control_image_ref)\"" in script
    assert "echo \"nanofaas/control-plane:host-${build_mode}-${fingerprint}\"" in script
    assert "echo \"${CONTROL_PLANE_CACHE_ROOT}/${build_mode}/${modules_hash}\"" in script
    assert "ensure_host_image_available_from_local_cache" in script
    assert "retagging from" in script
    assert ":control-plane:bootBuildImage" in script
    assert ":function-runtime:bootJar :examples:java:word-stats:bootJar :examples:java:json-transform:bootJar" in script
    assert "uname -m" in script
    assert "linux/arm64" in script
    assert "-PimagePlatform=" in script
    assert "paketobuildpacks/builder-jammy-java-tiny:latest" in script
    assert "NATIVE_IMAGE_BUILD_ARGS=" in script
    assert "detect_local_cpu_count" in script
    assert ":control-plane:bootJar -PcontrolPlaneModules=" in script
    assert "-J-Xmx8g" in script
    assert "-J-Xmx4g" not in script
    assert "-J-XX:ActiveProcessorCount=${active_processors}" in script
    assert "-J-XX:ActiveProcessorCount=2" not in script
    assert "-J-XX:ActiveProcessorCount=3" not in script
    assert "-J-XX:ActiveProcessorCount=4" not in script
    assert "docker save" in script
    assert "sudo docker load -i" in script
    assert "demos_enabled" in script
    assert "Control-plane-only mode" in script
    assert "E2E_K3S_HELM_NONINTERACTIVE" in script
    assert "exec bash \"${PROJECT_ROOT}/experiments/run.sh\"" in script
    assert "VM cleanup is enabled (KEEP_VM=false)" in script
    assert "Build strategy: control-plane/function-runtime/demo images on host" in script
    assert "Selected demo functions:" in script
    assert "./experiments/e2e-loadtest.sh" in script
    assert "register functions before running load tests" in script
    assert "for fn in word-stats-java word-stats-python word-stats-exec word-stats-java-lite;" not in script
