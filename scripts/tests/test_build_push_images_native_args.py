from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "build-push-images.sh"


def test_build_push_images_script_sets_native_build_args_defaults():
    script = SCRIPT.read_text(encoding="utf-8")
    assert "NATIVE_IMAGE_BUILD_ARGS" in script
    assert "NATIVE_IMAGE_XMX=${NATIVE_IMAGE_XMX:-8g}" in script
    assert "-J-Xmx${NATIVE_IMAGE_XMX}" in script
    assert "-J-XX:ActiveProcessorCount=" in script
    assert "detect_cpu_count" in script
    assert "NATIVE_IMAGE_BUILD_ARGS=\"$RESOLVED_NATIVE_IMAGE_BUILD_ARGS\" BP_OCI_SOURCE=\"$OCI_SOURCE\" ./gradlew :control-plane:bootBuildImage" in script
    assert "NATIVE_IMAGE_BUILD_ARGS=\"$RESOLVED_NATIVE_IMAGE_BUILD_ARGS\" BP_OCI_SOURCE=\"$OCI_SOURCE\" ./gradlew :function-runtime:bootBuildImage" in script
    assert "NATIVE_IMAGE_BUILD_ARGS=\"$RESOLVED_NATIVE_IMAGE_BUILD_ARGS\" BP_OCI_SOURCE=\"$OCI_SOURCE\" ./gradlew \":examples:java:${example}:bootBuildImage\"" in script
