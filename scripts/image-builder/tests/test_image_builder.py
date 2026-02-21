from pathlib import Path

import pytest

import image_builder as ib


def test_images_catalog_contains_all_expected_entries():
    expected = {
        "control-plane",
        "function-runtime",
        "java-word-stats",
        "java-json-transform",
        "java-lite-word-stats",
        "java-lite-json-transform",
        "python-word-stats",
        "python-json-transform",
        "watchdog",
        "bash-word-stats",
        "bash-json-transform",
    }
    assert set(ib.IMAGES) == expected
    assert len(ib.IMAGES) == 11


def test_resolve_selected_images_supports_all_option():
    assert ib.resolve_selected_images(["All"]) == sorted(ib.IMAGES.keys())


def test_get_current_version_reads_build_gradle(tmp_path: Path):
    build_gradle = tmp_path / "build.gradle"
    build_gradle.write_text("version = '1.2.3'\n", encoding="utf-8")
    assert ib.get_current_version(tmp_path) == "1.2.3"


@pytest.mark.parametrize(
    ("use_suffix", "expected"),
    [
        (True, "ghcr.io/miciav/nanofaas/watchdog:v0.10.0-arm64"),
        (False, "ghcr.io/miciav/nanofaas/watchdog:v0.10.0"),
    ],
)
def test_build_image_reference_for_non_multi_architecture(use_suffix: bool, expected: str):
    assert ib.build_image_reference("watchdog", "v0.10.0", "arm64", use_suffix) == expected


def test_build_image_reference_ignores_suffix_for_multi():
    assert (
        ib.build_image_reference("watchdog", "v0.10.0", "multi", True)
        == "ghcr.io/miciav/nanofaas/watchdog:v0.10.0"
    )


def test_gradle_command_arm64_uses_custom_builder():
    cmd = ib.build_gradle_command(
        ib.IMAGES["control-plane"],
        "ghcr.io/miciav/nanofaas/control-plane:v0.10.0-arm64",
        "arm64",
    )
    assert ":control-plane:bootBuildImage" in cmd
    assert "-PcontrolPlaneImage=ghcr.io/miciav/nanofaas/control-plane:v0.10.0-arm64" in cmd
    assert "NATIVE_IMAGE_BUILD_ARGS=" in cmd
    assert "-J-Xmx8g" in cmd
    assert "-J-XX:ActiveProcessorCount=" in cmd
    assert "-PimagePlatform=linux/arm64" in cmd
    assert "-PimageBuilder=dashaun/builder:tiny" in cmd
    assert "-PimageRunImage=paketobuildpacks/run-jammy-tiny:latest" in cmd


def test_gradle_command_amd64_uses_default_builder():
    cmd = ib.build_gradle_command(
        ib.IMAGES["control-plane"],
        "ghcr.io/miciav/nanofaas/control-plane:v0.10.0-amd64",
        "amd64",
    )
    assert "NATIVE_IMAGE_BUILD_ARGS=" in cmd
    assert "-J-Xmx8g" in cmd
    assert "-J-XX:ActiveProcessorCount=" in cmd
    assert "-PimagePlatform=linux/amd64" in cmd
    assert "-PimageBuilder=" not in cmd
    assert "-PimageRunImage=" not in cmd


def test_docker_command_for_single_arch():
    cmd = ib.build_docker_command(
        ib.IMAGES["watchdog"],
        "ghcr.io/miciav/nanofaas/watchdog:v0.10.0-arm64",
        "arm64",
    )
    assert cmd == (
        "docker build --platform linux/arm64 -t "
        "ghcr.io/miciav/nanofaas/watchdog:v0.10.0-arm64 -f watchdog/Dockerfile ."
    )


def test_docker_command_for_multi_arch_uses_buildx():
    cmd = ib.build_docker_command(
        ib.IMAGES["watchdog"],
        "ghcr.io/miciav/nanofaas/watchdog:v0.10.0",
        "multi",
    )
    assert cmd == (
        "docker buildx build --platform linux/arm64,linux/amd64 -t "
        "ghcr.io/miciav/nanofaas/watchdog:v0.10.0 -f watchdog/Dockerfile ."
    )
