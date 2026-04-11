from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path
from types import MappingProxyType

from controlplane_tool.scenario_components.environment import ScenarioExecutionContext
from controlplane_tool.scenario_components.models import ScenarioComponentDefinition
from controlplane_tool.scenario_components.operations import RemoteCommandOperation, ScenarioOperation
from controlplane_tool.vm_cluster_workflows import (
    control_image,
    function_image_specs,
    runtime_image,
)


def _frozen_env() -> Mapping[str, str]:
    return MappingProxyType({})


def _dockerfile_for_runtime_kind(runtime_kind: str, family: str) -> Path:
    dockerfile_map = {
        "exec": Path(f"examples/bash/{family}/Dockerfile"),
        "go": Path(f"examples/go/{family}/Dockerfile"),
        "java-lite": Path(f"examples/java/{family}-lite/Dockerfile"),
        "python": Path(f"examples/python/{family}/Dockerfile"),
    }
    try:
        return dockerfile_map[runtime_kind]
    except KeyError as exc:  # pragma: no cover - defensive guard
        raise ValueError(f"Unsupported function runtime: {runtime_kind!r}") from exc


def plan_build_core(context: ScenarioExecutionContext) -> tuple[ScenarioOperation, ...]:
    control_plane_image = control_image(context.local_registry)
    function_runtime_image = runtime_image(context.local_registry)
    if context.runtime == "rust":
        control_context = "control-plane-rust"
        control_dockerfile = "control-plane-rust/Dockerfile"
    else:
        control_context = "control-plane"
        control_dockerfile = "control-plane/Dockerfile"

    operations: list[ScenarioOperation] = []

    if context.runtime == "rust":
        operations.append(
            RemoteCommandOperation(
                operation_id="images.build_core.boot_jars",
                summary="Build core Rust support artifacts",
                argv=(
                    "cargo",
                    "build",
                    "--release",
                    "--manifest-path",
                    "control-plane-rust/Cargo.toml",
                ),
                env=_frozen_env(),
            )
        )
    else:
        operations.append(
            RemoteCommandOperation(
                operation_id="images.build_core.boot_jars",
                summary="Build core JVM artifacts",
                argv=(
                    "./gradlew",
                    ":control-plane:bootJar",
                    ":function-runtime:bootJar",
                    "--no-daemon",
                    "-q",
                ),
                env=_frozen_env(),
            )
        )

    operations.extend(
        [
            RemoteCommandOperation(
                operation_id="images.build_core.control_image",
                summary="Build control-plane image",
                argv=(
                    "docker",
                    "build",
                    "-f",
                    control_dockerfile,
                    "-t",
                    control_plane_image,
                    control_context,
                ),
                env=_frozen_env(),
            ),
            RemoteCommandOperation(
                operation_id="images.build_core.runtime_image",
                summary="Build function-runtime image",
                argv=(
                    "docker",
                    "build",
                    "-f",
                    "function-runtime/Dockerfile",
                    "-t",
                    function_runtime_image,
                    "function-runtime",
                ),
                env=_frozen_env(),
            ),
            RemoteCommandOperation(
                operation_id="images.build_core.push_control_image",
                summary="Push control-plane image",
                argv=("docker", "push", control_plane_image),
                env=_frozen_env(),
            ),
            RemoteCommandOperation(
                operation_id="images.build_core.push_runtime_image",
                summary="Push function-runtime image",
                argv=("docker", "push", function_runtime_image),
                env=_frozen_env(),
            ),
        ]
    )
    return tuple(operations)


def plan_build_selected_functions(
    context: ScenarioExecutionContext,
) -> tuple[ScenarioOperation, ...]:
    selected_specs = function_image_specs(
        context.resolved_scenario,
        runtime_image(context.local_registry),
    )
    operations: list[ScenarioOperation] = []
    for image, runtime_kind, family in selected_specs:
        if runtime_kind == "java":
            operations.append(
                RemoteCommandOperation(
                    operation_id=f"images.build_selected_functions.{family}",
                    summary=f"Build {family} function image",
                    argv=(
                        "./gradlew",
                        f":examples:java:{family}:bootBuildImage",
                        f"-PfunctionImage={image}",
                        "--no-daemon",
                        "-q",
                    ),
                    env=_frozen_env(),
                )
            )
            continue

        operations.append(
            RemoteCommandOperation(
                operation_id=f"images.build_selected_functions.{family}",
                summary=f"Build {family} function image",
                argv=(
                    "docker",
                    "build",
                    "-f",
                    str(_dockerfile_for_runtime_kind(runtime_kind, family)),
                    "-t",
                    image,
                    ".",
                ),
                env=_frozen_env(),
            )
        )
    return tuple(operations)


BUILD_CORE = ScenarioComponentDefinition(
    component_id="images.build_core",
    summary="Build core images",
    planner=plan_build_core,
)

BUILD_SELECTED_FUNCTIONS = ScenarioComponentDefinition(
    component_id="images.build_selected_functions",
    summary="Build selected function images",
    planner=plan_build_selected_functions,
)
