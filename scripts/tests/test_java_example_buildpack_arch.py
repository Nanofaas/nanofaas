from pathlib import Path

import pytest


REPO_ROOT = Path(__file__).resolve().parents[2]
EXAMPLE_BUILD_FILES = [
    REPO_ROOT / "examples" / "java" / "word-stats" / "build.gradle",
    REPO_ROOT / "examples" / "java" / "json-transform" / "build.gradle",
]


@pytest.mark.parametrize("build_file", EXAMPLE_BUILD_FILES, ids=lambda path: path.parent.name)
def test_java_example_buildpacks_default_to_host_arch_with_arm_safe_builder(build_file: Path) -> None:
    gradle = build_file.read_text(encoding="utf-8")

    assert "def isArm = System.getProperty('os.arch') in ['aarch64', 'arm64']" in gradle
    assert "def defaultBuilder = isArm ? 'dashaun/builder:tiny' : 'paketobuildpacks/builder-jammy-tiny:latest'" in gradle
    assert "if (!runImageName && !isArm)" in gradle
    assert "def defaultPlatform = (hostArch in ['aarch64', 'arm64']) ? 'linux/arm64' : 'linux/amd64'" in gradle
    assert "def targetPlatform = project.findProperty('imagePlatform') ?: System.getenv('IMAGE_PLATFORM') ?: defaultPlatform" in gradle
    assert "imagePlatform = targetPlatform" in gradle
