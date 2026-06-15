from __future__ import annotations

from pathlib import Path

import pytest

from controlplane_tool.building.image_plan import (
    DEFAULT_ARCHES,
    image_reference,
    plan_image_matrix,
    select_image_targets,
)


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
