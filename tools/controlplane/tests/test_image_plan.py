from __future__ import annotations

from pathlib import Path

import pytest

from controlplane_tool.building.image_plan import (
    DEFAULT_ARCHES,
    ImageArch,
    ImageFlavor,
    ImageMatrixCell,
    image_reference,
    plan_image_matrix,
    select_image_targets,
)


def _single_cell(
    target: str,
    *,
    arch: ImageArch = "amd64",
    flavor: ImageFlavor = "native",
) -> ImageMatrixCell:
    plan = plan_image_matrix(
        repo_root=Path("/repo"),
        targets=[target],
        tag="v1.2.3",
        arches=(arch,),
        flavors=("jvm", "native"),
        push=False,
        runtime="docker",
    )
    return next(cell for cell in plan.cells if cell.arch == arch and cell.flavor == flavor)


def test_select_targets_all_returns_current_catalog() -> None:
    assert select_image_targets("all") == sorted(
        [
            "control-plane",
            "function-runtime",
            "java-word-stats",
            "java-json-transform",
            "java-lite-word-stats",
            "java-lite-json-transform",
            "go-word-stats",
            "go-json-transform",
            "python-word-stats",
            "python-json-transform",
            "javascript-word-stats",
            "javascript-json-transform",
            "bash-word-stats",
            "bash-json-transform",
            "watchdog",
        ]
    )


def test_select_targets_csv_preserves_order() -> None:
    assert select_image_targets("watchdog,control-plane") == ["watchdog", "control-plane"]


def test_select_targets_rejects_unknown_target() -> None:
    with pytest.raises(ValueError, match="Unknown image target"):
        select_image_targets("watchdog,nope")


def test_default_arches_are_amd64_then_arm64() -> None:
    assert DEFAULT_ARCHES == ("amd64", "arm64")


def test_image_reference_for_native_cell() -> None:
    assert (
        image_reference("control-plane", "v1.2.3", "amd64", "native")
        == "ghcr.io/miciav/nanofaas/control-plane:v1.2.3-amd64-native"
    )


def test_image_reference_for_jvm_cell() -> None:
    assert (
        image_reference("java-word-stats", "v1.2.3", "arm64", "jvm")
        == "ghcr.io/miciav/nanofaas/java-word-stats:v1.2.3-arm64-jvm"
    )


def test_image_reference_for_default_cell_omits_flavor() -> None:
    assert (
        image_reference("python-word-stats", "v1.2.3", "arm64", "default")
        == "ghcr.io/miciav/nanofaas/python-word-stats:v1.2.3-arm64"
    )


def test_plan_all_expands_to_38_cells() -> None:
    plan = plan_image_matrix(
        repo_root=Path("/repo"),
        targets=select_image_targets("all"),
        tag="v1.2.3",
        arches=("amd64", "arm64"),
        flavors=("jvm", "native"),
        push=True,
        runtime="docker",
    )
    assert len(plan.cells) == 38


def test_java_lite_only_expands_native_cells() -> None:
    plan = plan_image_matrix(
        repo_root=Path("/repo"),
        targets=["java-lite-word-stats"],
        tag="v1.2.3",
        arches=("amd64", "arm64"),
        flavors=("jvm", "native"),
        push=True,
        runtime="docker",
    )
    assert [(cell.arch, cell.flavor, cell.image) for cell in plan.cells] == [
        ("amd64", "native", "ghcr.io/miciav/nanofaas/java-lite-word-stats:v1.2.3-amd64-native"),
        ("arm64", "native", "ghcr.io/miciav/nanofaas/java-lite-word-stats:v1.2.3-arm64-native"),
    ]


def test_default_targets_ignore_jvm_native_selector_and_use_default_flavor() -> None:
    plan = plan_image_matrix(
        repo_root=Path("/repo"),
        targets=["watchdog"],
        tag="v1.2.3",
        arches=("amd64", "arm64"),
        flavors=("jvm", "native"),
        push=True,
        runtime="docker",
    )
    assert [(cell.arch, cell.flavor, cell.image) for cell in plan.cells] == [
        ("amd64", "default", "ghcr.io/miciav/nanofaas/watchdog:v1.2.3-amd64"),
        ("arm64", "default", "ghcr.io/miciav/nanofaas/watchdog:v1.2.3-arm64"),
    ]


def test_single_cell_helper_returns_requested_cell() -> None:
    cell = _single_cell("control-plane", arch="amd64", flavor="jvm")

    assert cell.target == "control-plane"
    assert cell.arch == "amd64"
    assert cell.flavor == "jvm"
    assert cell.image == "ghcr.io/miciav/nanofaas/control-plane:v1.2.3-amd64-jvm"


def test_native_gradle_cell_plans_control_plane_boot_build_image() -> None:
    cell = _single_cell("control-plane", arch="amd64", flavor="native")

    assert cell.build_command.command == [
        "./gradlew",
        ":control-plane:bootBuildImage",
        "-PcontrolPlaneImage=ghcr.io/miciav/nanofaas/control-plane:v1.2.3-amd64-native",
        "-PimagePlatform=linux/amd64",
        "-PcontrolPlaneModules=all",
    ]
    assert cell.build_command.cwd == Path("/repo")
    assert cell.build_command.env["BP_OCI_SOURCE"] == "https://github.com/miciav/nanofaas"
    assert "NATIVE_IMAGE_BUILD_ARGS" in cell.build_command.env


def test_jvm_control_plane_cell_plans_jar_then_dockerfile_build() -> None:
    cell = _single_cell("control-plane", arch="amd64", flavor="jvm")

    assert cell.build_command.command == [
        "bash",
        "-lc",
        "./gradlew :control-plane:bootJar -PcontrolPlaneModules=all"
        " && docker build --platform linux/amd64"
        " --label org.opencontainers.image.source=https://github.com/miciav/nanofaas"
        " -t ghcr.io/miciav/nanofaas/control-plane:v1.2.3-amd64-jvm"
        " -f platform/control-plane/Dockerfile platform/control-plane",
    ]
    assert cell.build_command.env == {}


def test_java_jvm_function_cell_plans_function_jar_then_dockerfile_build() -> None:
    cell = _single_cell("java-word-stats", arch="amd64", flavor="jvm")

    assert cell.build_command.command == [
        "bash",
        "-lc",
        "./gradlew :functions:java:word-stats:bootJar"
        " && docker build --platform linux/amd64"
        " --label org.opencontainers.image.source=https://github.com/miciav/nanofaas"
        " -t ghcr.io/miciav/nanofaas/java-word-stats:v1.2.3-amd64-jvm"
        " -f functions/java/word-stats/Dockerfile functions/java/word-stats",
    ]


def test_java_lite_native_cell_plans_dockerfile_build() -> None:
    cell = _single_cell("java-lite-word-stats", arch="amd64", flavor="native")

    assert cell.image == "ghcr.io/miciav/nanofaas/java-lite-word-stats:v1.2.3-amd64-native"
    assert cell.build_command.command == [
        "docker",
        "build",
        "--platform",
        "linux/amd64",
        "--label",
        "org.opencontainers.image.source=https://github.com/miciav/nanofaas",
        "-t",
        "ghcr.io/miciav/nanofaas/java-lite-word-stats:v1.2.3-amd64-native",
        "-f",
        "functions/java/word-stats-lite/Dockerfile",
        ".",
    ]


def test_default_watchdog_cell_plans_dockerfile_build_with_default_tag() -> None:
    cell = _single_cell("watchdog", arch="amd64", flavor="default")

    assert cell.image == "ghcr.io/miciav/nanofaas/watchdog:v1.2.3-amd64"
    assert cell.build_command.command == [
        "docker",
        "build",
        "--platform",
        "linux/amd64",
        "--label",
        "org.opencontainers.image.source=https://github.com/miciav/nanofaas",
        "-t",
        "ghcr.io/miciav/nanofaas/watchdog:v1.2.3-amd64",
        "-f",
        "watchdog/Dockerfile",
        ".",
    ]
