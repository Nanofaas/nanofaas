from __future__ import annotations

from dataclasses import FrozenInstanceError
from pathlib import Path

import pytest

from controlplane_tool.functions.catalog import list_functions
from controlplane_tool.images.plan import build_image_plan


REPO_ROOT = Path(__file__).resolve().parents[4]
REGISTRY = "registry.test:5000/nanofaas"


def _plan(**kwargs):  # noqa: ANN003, ANN202
    return build_image_plan(REPO_ROOT, "v0.18.0", registry=REGISTRY, **kwargs)


def _function_image_name(runtime: str, family: str) -> str:
    prefix = {"exec": "bash", "java-lite": "java-lite"}.get(runtime, runtime)
    return f"{prefix}-{family}"


def test_plan_contains_platform_and_every_discovered_function() -> None:
    plan = _plan()
    expected_functions = {
        _function_image_name(function.runtime, function.family)
        for function in list_functions()
        if function.example_dir is not None
    }

    assert {"control-plane", "java-warm-echo", "watchdog"}.issubset(plan.target_names)
    assert expected_functions.issubset(plan.target_names)
    assert "tool-metrics-echo" not in plan.target_names


def test_plan_expands_to_exactly_52_cells() -> None:
    assert len(_plan().cells) == 52


def test_runtime_flavors_are_expanded_by_build_capability() -> None:
    plan = _plan()
    flavors_by_target = {
        target.name: {cell.flavor for cell in plan.cells if cell.target == target}
        for target in plan.targets
    }

    spring_targets = {
        "control-plane",
        "java-warm-echo",
        "java-word-stats",
        "java-json-transform",
        "java-roman-numeral",
    }
    java_lite_targets = {
        "java-lite-word-stats",
        "java-lite-json-transform",
        "java-lite-roman-numeral",
    }
    assert {name: flavors_by_target[name] for name in spring_targets} == {
        name: {"jvm", "native"} for name in spring_targets
    }
    assert {name: flavors_by_target[name] for name in java_lite_targets} == {
        name: {"native"} for name in java_lite_targets
    }
    assert all(
        flavors == {"default"}
        for name, flavors in flavors_by_target.items()
        if name not in spring_targets | java_lite_targets
    )


def test_candidate_tags_include_architecture_and_non_default_flavor() -> None:
    plan = _plan()

    assert all(
        cell.tag
        == (
            f"v0.18.0-{cell.architecture}"
            if cell.flavor == "default"
            else f"v0.18.0-{cell.architecture}-{cell.flavor}"
        )
        for cell in plan.cells
    )
    assert all(cell.image == f"{REGISTRY}/{cell.target.name}:{cell.tag}" for cell in plan.cells)


def test_default_candidate_registry_is_stack_local_never_ghcr() -> None:
    plan = build_image_plan(REPO_ROOT, "v0.18.0", selectors=("watchdog",))

    assert plan.registry == "localhost:5000/nanofaas"
    assert all(cell.image.startswith("localhost:5000/nanofaas/") for cell in plan.cells)
    assert all("ghcr.io" not in cell.image for cell in plan.cells)


def test_all_amd64_cells_precede_all_arm64_cells() -> None:
    architectures = [cell.architecture for cell in _plan().cells]

    assert architectures == sorted(architectures, key={"amd64": 0, "arm64": 1}.get)


def test_each_discovered_function_dockerfile_maps_to_one_target() -> None:
    plan = _plan()
    function_dockerfiles = {
        function.example_dir.relative_to(REPO_ROOT) / "Dockerfile"
        for function in list_functions()
        if function.example_dir is not None
    }
    mapped = [
        target.dockerfile
        for target in plan.targets
        if target.dockerfile in function_dockerfiles
    ]

    assert set(mapped) == function_dockerfiles
    assert len(mapped) == len(function_dockerfiles)


def test_plan_partitions_into_42_bake_and_10_gradle_cells() -> None:
    plan = _plan()

    assert len(plan.bake_cells) == 42
    assert len(plan.gradle_cells) == 10
    assert {cell.build_kind for cell in plan.bake_cells} == {"bake"}
    assert {cell.build_kind for cell in plan.gradle_cells} == {"gradle"}


def test_target_selector_filters_before_cell_expansion() -> None:
    plan = _plan(selectors=("watchdog", "java-word-stats"))

    assert plan.target_names == frozenset({"watchdog", "java-word-stats"})
    assert [(cell.target.name, cell.architecture, cell.flavor) for cell in plan.cells] == [
        ("java-word-stats", "amd64", "jvm"),
        ("java-word-stats", "amd64", "native"),
        ("watchdog", "amd64", "default"),
        ("java-word-stats", "arm64", "jvm"),
        ("java-word-stats", "arm64", "native"),
        ("watchdog", "arm64", "default"),
    ]


def test_target_selector_rejects_unknown_names() -> None:
    with pytest.raises(ValueError, match="unknown image target: missing"):
        _plan(selectors=("missing",))


def test_spring_native_cells_expose_gradle_buildpack_specs() -> None:
    plan = _plan(selectors=("control-plane", "java-roman-numeral"))
    control_plane = next(
        cell
        for cell in plan.gradle_cells
        if cell.target.name == "control-plane" and cell.architecture == "amd64"
    )
    roman_numeral = next(
        cell
        for cell in plan.gradle_cells
        if cell.target.name == "java-roman-numeral" and cell.architecture == "arm64"
    )

    assert control_plane.gradle_command == (
        "./gradlew",
        ":control-plane:bootBuildImage",
        f"-PcontrolPlaneImage={control_plane.image}",
        "-PimagePlatform=linux/amd64",
        "-PcontrolPlaneModules=all",
    )
    assert roman_numeral.gradle_command == (
        "./gradlew",
        ":functions:java:roman-numeral:bootBuildImage",
        f"-PfunctionImage={roman_numeral.image}",
        "-PimagePlatform=linux/arm64",
        "-PimageBuilder=dashaun/builder:tiny",
        "-PimageRunImage=paketobuildpacks/run-jammy-tiny:latest",
    )


def test_every_spring_native_arm64_cell_overrides_builder_and_run_image() -> None:
    cells = [
        cell
        for cell in _plan().gradle_cells
        if cell.architecture == "arm64"
    ]

    assert len(cells) == 5
    assert {cell.target.name for cell in cells} == {
        "control-plane",
        "java-warm-echo",
        "java-word-stats",
        "java-json-transform",
        "java-roman-numeral",
    }
    for cell in cells:
        assert "-PimageBuilder=dashaun/builder:tiny" in cell.gradle_command
        assert (
            "-PimageRunImage=paketobuildpacks/run-jammy-tiny:latest"
            in cell.gradle_command
        )


def test_spring_jvm_cells_require_boot_jar_before_bake() -> None:
    plan = _plan(selectors=("control-plane", "java-warm-echo", "java-roman-numeral"))
    commands = {
        cell.target.name: cell.prerequisite_command
        for cell in plan.bake_cells
        if cell.architecture == "amd64"
    }

    assert commands == {
        "control-plane": (
            "./gradlew",
            ":control-plane:bootJar",
            "-PcontrolPlaneModules=all",
        ),
        "java-warm-echo": ("./gradlew", ":services:java:warm-echo:bootJar"),
        "java-roman-numeral": ("./gradlew", ":functions:java:roman-numeral:bootJar"),
    }


def test_plan_metadata_is_immutable() -> None:
    plan = _plan(selectors=("watchdog",))

    with pytest.raises(FrozenInstanceError):
        plan.cells[0].tag = "mutable"  # type: ignore[misc]
